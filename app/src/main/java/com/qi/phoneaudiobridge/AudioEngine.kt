package com.qi.phoneaudiobridge

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.MessageDigest
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * 听说 LisTalk engine — MULTI-PC edition.
 *
 * - One session per PC; phone mic fans out to every connected PC.
 * - Speaker audio from all PCs is mixed (sum + clamp) with per-PC
 *   mute/volume before hitting the phone speaker.
 * - Paired-device registry persisted in SharedPreferences: deviceId, name,
 *   OS, last IP, pairing token, auto-connect / mic-send / speaker-receive
 *   toggles, per-device volume.
 * - Presence: PCs broadcast a BEACON every 1 s (deviceId + token hash).
 *   A paired PC with auto-connect ON is connected automatically the moment
 *   its beacon appears — no tap needed. Unpaired PCs show as new devices;
 *   first tap pairs them (pairing code from the PC screen, or trust-on-
 *   first-use inside the PC's pairing window) and they are remembered.
 */
object AudioEngine {

    /** Localized user-visible string via the app context (set by
     *  [attachContext] / [start]); empty before a context exists. */
    private fun str(resId: Int, vararg args: Any): String {
        val ctx = appContext ?: return ""
        return if (args.isEmpty()) ctx.getString(resId) else ctx.getString(resId, *args)
    }

    /** Give the engine a context for localized strings BEFORE it starts
     *  (called from MainActivity.onCreate / BridgeService.onCreate) so
     *  status / device-name strings resolve in the system language from
     *  the very first frame. Does not start the engine. */
    fun attachContext(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        if (statusText.isEmpty()) statusText = str(R.string.engine_status_not_started)
        if (playoutDiagText.isEmpty()) playoutDiagText = str(R.string.playout_diag_not_started)
    }
    private const val TAG = "AirBridge"

    // ---------------- persistent registry ----------------

    data class PairedDevice(
        val deviceId: String,
        var name: String,
        var os: String,
        var lastIp: String,
        var token: String,
        var autoConnect: Boolean = true,
        var micSend: Boolean = true,
        var speakerRecv: Boolean = true,
        var speakerVolume: Float = 0.7f,
        var muted: Boolean = false,
        var alias: String = "",
        var lastSeenMs: Long = 0L,
        /** Compact-group pin (wave 3, plan A.2.5): pinned devices sort
         *  first among the not-connected chips; expanded cards ignore it. */
        var pinned: Boolean = false
    ) {
        val displayName: String get() = alias.ifEmpty { name }
    }

    // ---------------- runtime session (one per PC) ----------------

    class Session(val ip: String) {
        @Volatile var addr: InetAddress? = null
        @Volatile var name: String = ip
        @Volatile var os: String = ""
        @Volatile var deviceId: String? = null
        @Volatile var connected = false
        @Volatile var wantConnect = false
        @Volatile var lastRxMs = 0L
        @Volatile var lastHelloMs = 0L
        @Volatile var lastBeaconMs = 0L
        @Volatile var pendingPairCode: String? = null
        // unified versioning: PC version learned from HELLO_ACK / BEACON / DISCOVER_RESP
        @Volatile var pcAppVersion: String = ""
        @Volatile var pcProtoVersion: Int = -1
        @Volatile var versionMismatch: Boolean = false
        @Volatile var pairQr = false
        // pairing awaiting the user's verification-confirm (UI_SPEC_MULTIDEVICE §4.3)
        @Volatile var pendingPairToken: String? = null
        @Volatile var pendingVerifyCode: String? = null
        // auto-connect bookkeeping (§5: failure fuse, "just auto-connected" mark)
        @Volatile var autoInitiated = false
        @Volatile var autoFailEpisodes = 0
        @Volatile var autoPaused = false
        @Volatile var episodeStartMs = 0L
        @Volatile var lastConnectedMs = 0L
        @Volatile var justAutoConnectedMs = 0L
        // leave / re-entry tracking (v2.1 settings system): presence edges
        @Volatile var wasPresent = false
        @Volatile var absentSinceMs = 0L
        @Volatile var returnAskRaised = false
        // user tapped 忽略 on the return ask: periodic probing must not
        // re-raise it (the presence-edge path honours it too) until the
        // user accepts once or the engine restarts.
        @Volatile var returnAskDismissed = false
        // mesh-recovery tracking (v2.2 defect A, policy in SessionRecovery.kt)
        @Volatile var helloAttempts = 0
        @Volatile var firstHelloAttemptMs = 0L
        @Volatile var reResolving = false
        @Volatile var resolvingSinceMs = 0L
        // camera relay (wave 4): caps this PC advertised in HELLO_ACK,
        // whether it asked for the camera stream (VIDEO_CTRL start), and
        // the >=500 ms keyframe-request rate limit for this peer.
        @Volatile var pcCaps: Set<String> = emptySet()
        @Volatile var videoWanted = false
        @Volatile var lastKeyframeReqMs = 0L
        var txSeq = 0
        var pingSeq = 0
        var rxPackets = 0L
        // stats
        var lastRxSeq = -1
        var prevTransitUs = Long.MIN_VALUE
        var jitterUs = 0.0
        @Volatile var rttMs = -1.0
        @Volatile var lossPct = 0.0
        @Volatile var jitterMs = 0.0
        var winRx = 0L
        var winLost = 0L
        var winStartMs = 0L
        // jitter buffer
        val jb = TreeMap<Int, ByteArray>()
        var jbNextSeq = -1
        var jbPrimed = false
        val jitterBufferMs: Int get() = (jitterMs * 2.5).toInt().coerceIn(20, 60)

        fun resetJitterBuffer() { synchronized(jb) { jb.clear(); jbPrimed = false; jbNextSeq = -1 } }
    }

    /** Merged view for the UI device-card list. */
    data class DeviceView(
        val deviceId: String?,
        val ip: String,
        val name: String,
        val os: String,
        val paired: Boolean,
        val online: Boolean,
        val connected: Boolean,
        val rttMs: Double,
        val lossPct: Double,
        val autoConnect: Boolean,
        val micSend: Boolean,
        val speakerRecv: Boolean,
        val speakerVolume: Float,
        val muted: Boolean,
        val lastSeenMs: Long,
        val autoPaused: Boolean,
        val justAutoConnected: Boolean,
        val pcAppVersion: String = "",
        val versionMismatch: Boolean = false,
        /** FB-12 density (wave 3, plan A.2.4): a connect attempt is in
         *  flight (HELLO sent, not yet confirmed) — renders expanded. */
        val connecting: Boolean = false,
        /** Compact-group pin (plan A.2.5); paired devices only. */
        val pinned: Boolean = false
    )

    // ---------------- UI-visible aggregate state ----------------

    @Volatile var running = false
    @Volatile var statusText = ""
    @Volatile var micEnabled = true
    @Volatile var speakerEnabled = true
    @Volatile var currentRateCode = Protocol.RATE_48K
    @Volatile var reconnectCount = 0
    @Volatile var micLevel = 0f
    @Volatile var speakerLevel = 0f

    // ---- echo suppression (three layers, see README "回声抑制") ----
    /** Layer 2/3 master toggle (UI), default ON. Layer 3 howl guard always runs. */
    @Volatile var echoSuppressionEnabled = true
    @Volatile var fxAecActive = false
    @Volatile var fxNsActive = false
    @Volatile var fxAgcActive = false
    /** True while the fallback ducker is currently pulling the mic down. */
    @Volatile var echoDuckingActive = false
    /** Current correlation-based residual-echo estimate, 0..1. */
    @Volatile var residualEcho = 0.0
    /** Howling-guard gain currently applied to mic send, dB (0 or negative). */
    @Volatile var howlGainDb = 0f
    /** Layer-2 NLMS state (v2.1: wired into the LIVE capture path). */
    @Volatile var nlmsActive = false
    @Volatile var nlmsErleDb = 0.0
    @Volatile var nlmsBulkDelay = -1
    /**
     * The live echo chain (NLMS subtraction + residual ducker + howl guard),
     * pure Kotlin — the same class the JVM harness measures. Playback frames
     * are pushed from the playout loop; capture frames are processed here
     * before fan-out.
     */
    val echoProcessor = EchoProcessor()
    private var lastCaptureProcessMs = 0L
    private var savedAudioMode = -1
    private var savedSpeakerphone = false

    // ---- audio focus pause (volume-stream fix, design §3.6) ----
    /**
     * True while the bridge is paused because audio focus was taken (a
     * real incoming call, or another app grabbing focus). This flag is
     * ONLY written by the focus listener: regaining focus clears exactly
     * this pause and never overrides a user/session pause, which live in
     * separate state (per-session autoPaused, channel toggles).
     * While set: the playout loop drains jitter buffers but renders
     * silence, and the capture loop sends NOTHING — call audio must
     * never leak to the PCs.
     */
    @Volatile private var focusPaused = false
    private val focusChangeListener = android.media.AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            android.media.AudioManager.AUDIOFOCUS_LOSS,
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (!focusPaused) {
                    focusPaused = true
                    Log.i(TAG, "audio focus lost ($change): bridge paused (downlink silent, mic not sent)")
                }
            }
            android.media.AudioManager.AUDIOFOCUS_GAIN -> {
                if (focusPaused) {
                    focusPaused = false
                    // The telephony stack ran its own mode/route while it
                    // held focus; re-assert our session configuration once
                    // (a controlled exception to the set-once-at-start
                    // rule, design §3.6).
                    try {
                        val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                        if (am != null && running) {
                            am.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
                            applyOutputRoute()
                        }
                    } catch (_: Exception) {}
                    Log.i(TAG, "audio focus regained: bridge resumed")
                }
            }
        }
    }

    // ---- audio routing & capture policy (v2.1 defects 1/2/3) ----
    /** Mic sensitivity: 0 标准 / 1 高灵敏. */
    @Volatile var micSensitivity = 0
    /** Mic mode setting: 0 语音通话 / 1 高清拾音 / 2 自动 (session-boundary only). */
    @Volatile var micMode = 0
    /** Whether the live recorder was built in HD mode (no platform fx). */
    @Volatile var builtMicHd = false
        private set
    /** AudioSource the live recorder was actually built with. */
    @Volatile var builtMicSource = -1
        private set

    /** Effective HD decision, resolved ONCE when the recorder is built. */
    private fun effectiveMicHd(): Boolean =
        micMode == 1 || (micMode == 2 && headsetOutputActive)
    /** Platform NS mode: 0 标准 (on) / 1 轻柔 (off). */
    @Volatile var nsMode = 1
    /** Double-talk detector state from the live echo chain (UI honesty). */
    @Volatile var doubleTalkActive = false
    /** Loop-guard (defect 5c): own-voice return detector on the speaker path. */
    val loopGuard = LoopGuard()
    @Volatile var loopNoteUntilMs = 0L

    // ---- playout health (v2.2 speaker-no-sound fix) ----
    // The v2.1 chain could fail SILENTLY at three points: the route pin
    // was never confirmed, AudioTrack.write()'s result was discarded,
    // and nobody watched the playback head. All three are now observed:
    // the watchdog rebuilds a dead track, route drift is re-pinned once
    // per second, and the live state is exposed for the diagnostics UI.
    val playoutWatchdog = PlayoutWatchdog()
    @Volatile var trackWriteErrors = 0
    @Volatile var trackRebuilds = 0
    @Volatile var playoutFaultText: String? = null
    @Volatile var playoutFaultUntilMs = 0L
    /** True while a playout fault note should be visible in the UI. */
    val playoutFaultActive: Boolean get() = System.currentTimeMillis() < playoutFaultUntilMs
    /** One-line live playout diagnostics (settings screen). */
    @Volatile var playoutDiagText: String = ""
    /** Times the confirmed output route drifted from the pinned target. */
    @Volatile var routeReapplyCount = 0
    /** Sticky flag: setCommunicationDevice last returned false. */
    @Volatile var routePinFailed = false
    /** deviceKey of the output we last pinned (route-drift watchdog). */
    @Volatile private var expectedOutputKey: String? = null

    private fun notePlayoutFault(text: String) {
        playoutFaultText = text
        playoutFaultUntilMs = System.currentTimeMillis() + 8000
    }
    /** True while the loop-guard note should be visible in the UI. */
    val loopGuardNoteActive: Boolean get() = System.currentTimeMillis() < loopNoteUntilMs
    private var audioFocusRequest: android.media.AudioFocusRequest? = null
    private var deviceCallback: android.media.AudioDeviceCallback? = null
    private var lastRateApplyMs = 0L
    private var silenceSinceMs = 0L
    @Volatile var txPackets = 0L
    @Volatile var rxPackets = 0L

    // ---------------- settings (v2.1: real, persisted, wired) ----------------
    // Stored in SharedPreferences "airbridge_settings" (owned by AppSettings);
    // loaded in start() and reloaded via reloadSettings() after UI changes.
    const val RECONNECT_ASK = 0   // 每次询问 (default)
    const val RECONNECT_AUTO = 1  // 总是自动
    const val RECONNECT_NEVER = 2 // 从不自动
    @Volatile var reconnectMode = RECONNECT_ASK
    @Volatile var notificationsEnabled = true
    /** User pressed 暂停 (notification / UI): all sessions dropped, auto-connect suppressed until resumed. */
    @Volatile var userPaused = false

    /** A paired PC re-appeared after a long absence and the mode is ASK:
     *  the UI banner / notification asks 连接/忽略 until the user decides. */
    data class ReturnAsk(val deviceId: String, val ip: String, val name: String, val atMs: Long)
    @Volatile var pendingReturnAsk: ReturnAsk? = null
        private set

    /** Short dropouts always auto-resume, whatever the mode. */
    private const val SHORT_DROPOUT_MS = 60_000L
    /** Cross-subnet sweep pacing (v2.2.3 FB-6): probes per 1 s adapt
     *  tick, and the idle pause between two full sweep cycles. */
    private const val SWEEP_BATCH_PER_TICK = 30
    private const val SWEEP_CYCLE_PAUSE_MS = 5_000L
    /** Absences at least this long count as "离开后回归" for the ASK prompt. */
    private const val LONG_ABSENCE_MS = 600_000L // 10 min

    fun reloadSettings() {
        val ctx = appContext ?: return
        val sp = ctx.getSharedPreferences(AppSettings.PREFS, Context.MODE_PRIVATE)
        reconnectMode = sp.getInt(AppSettings.KEY_RECONNECT_MODE, RECONNECT_ASK)
        notificationsEnabled = sp.getBoolean(AppSettings.KEY_NOTIFICATIONS, true)
        echoSuppressionEnabled = sp.getBoolean(AppSettings.KEY_ECHO, true)
        outputDeviceSel = sp.getString(AppSettings.KEY_OUTPUT_DEVICE, "auto") ?: "auto"
        inputDeviceSel = sp.getString(AppSettings.KEY_INPUT_DEVICE, "auto") ?: "auto"
        micSensitivity = sp.getInt(AppSettings.KEY_MIC_SENSITIVITY, 0)
        micMode = sp.getInt(AppSettings.KEY_MIC_MODE, 0)
        nsMode = sp.getInt(AppSettings.KEY_NS_MODE, 1)
        extraSubnetsRaw = sp.getString(AppSettings.KEY_EXTRA_SUBNETS, "") ?: ""
        if (running) {
            applyOutputRoute()
            applyInputDevice()
            // NS/AGC policy lives on the record session: re-attach live.
            try { recorder?.let { attachEffects(it.audioSessionId) } } catch (_: Exception) {}
        }
    }

    // ---------------- audio devices (headset inheritance) ----------------
    // The app inherits the phone's REAL audio devices: enumerated live
    // (API 31+ getAvailableCommunicationDevices; legacy fallback via
    // getDevices), shown by product name in Settings, applied with
    // setCommunicationDevice and CONFIRMED via the actual communication
    // device (OEMs silently reset routes — research note §1.2/§5.1).

    data class CommDevice(val type: Int, val name: String, val headset: Boolean)

    @Volatile var outputDeviceSel: String = "auto" // "auto" | "type:<t>|<name>"
    @Volatile var inputDeviceSel: String = "auto"  // "auto" | "builtin" | "type:<t>|<name>"
    /** Set when the selected output vanished and we fell back (UI note). */
    @Volatile var routeFallbackNote: String? = null
    /** A headset appeared mid-session: UI offers one-tap switch. */
    @Volatile var pendingHeadsetName: String? = null
    private var pendingHeadsetKey: String? = null
    private val knownDeviceKeys = mutableSetOf<String>()
    private var commDeviceListener: android.media.AudioManager.OnCommunicationDeviceChangedListener? = null

    private fun deviceKey(type: Int, name: String) = "type:$type|$name"

    private fun friendlyName(d: android.media.AudioDeviceInfo): String {
        val pn = try { d.productName?.toString()?.trim().orEmpty() } catch (_: Exception) { "" }
        return when (d.type) {
            android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> str(R.string.device_speaker)
            android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> str(R.string.device_earpiece)
            android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC -> {
                // Several built-in mics may exist; the platform address
                // ("bottom"/"back"/"front" on many devices) tells them apart.
                val addr = if (Build.VERSION.SDK_INT >= 28) try { d.address } catch (_: Exception) { "" } else ""
                when {
                    addr.contains("bottom", true) -> str(R.string.device_phone_mic_bottom)
                    addr.contains("back", true) -> str(R.string.device_phone_mic_back)
                    addr.contains("front", true) -> str(R.string.device_phone_mic_front)
                    addr.isNotEmpty() -> str(R.string.device_phone_mic_addr, addr)
                    else -> str(R.string.device_phone_mic)
                }
            }
            else -> pn.ifEmpty {
                when (d.type) {
                    android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> str(R.string.device_wired_headset)
                    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
                    android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER -> str(R.string.device_bt_headset)
                    else -> str(R.string.device_audio_generic)
                }
            }
        }
    }

    private fun isHeadsetType(type: Int) = type in intArrayOf(
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
        android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER,
        android.media.AudioDeviceInfo.TYPE_USB_HEADSET
    )

    /** Live list of communication output devices (deduplicated). */
    fun commDevices(): List<CommDevice> {
        val ctx = appContext ?: return emptyList()
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val raw: List<android.media.AudioDeviceInfo> =
                if (Build.VERSION.SDK_INT >= 31) am.availableCommunicationDevices
                else am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).filter {
                    it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                        it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE ||
                        isHeadsetType(it.type)
                }
            raw.map { CommDevice(it.type, friendlyName(it), isHeadsetType(it.type)) }
                .distinctBy { deviceKey(it.type, it.name) }
        } catch (_: Exception) { emptyList() }
    }

    /** Live list of selectable headset-mic input devices (phone mic is implicit). */
    fun inputDevices(): List<CommDevice> {
        val ctx = appContext ?: return emptyList()
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
                .filter {
                    it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
                }
                .map { CommDevice(it.type, friendlyName(it), true) }
                .distinctBy { deviceKey(it.type, it.name) }
        } catch (_: Exception) { emptyList() }
    }

    /** The device audio is ACTUALLY going to right now (confirmed, not wished). */
    fun actualOutputDevice(): CommDevice? {
        val ctx = appContext ?: return null
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            if (Build.VERSION.SDK_INT >= 31) {
                am.communicationDevice?.let { CommDevice(it.type, friendlyName(it), isHeadsetType(it.type)) }
            } else {
                @Suppress("DEPRECATION")
                when {
                    am.isBluetoothScoOn -> commDevices().firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                        ?: CommDevice(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO, str(R.string.device_bt_headset), true)
                    am.isWiredHeadsetOn -> commDevices().firstOrNull { isHeadsetType(it.type) }
                    am.isSpeakerphoneOn -> CommDevice(android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, str(R.string.device_speaker), false)
                    else -> CommDevice(android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, str(R.string.device_earpiece), false)
                }
            }
        } catch (_: Exception) { null }
    }

    val outputRouteText: String get() = actualOutputDevice()?.name ?: str(R.string.device_speaker)

    /** True when the confirmed output is a headset (echo risk minimal). */
    val headsetOutputActive: Boolean get() = actualOutputDevice()?.headset == true

    /**
     * Apply the output selection (defect 1 + headset inheritance).
     * MODE_IN_COMMUNICATION stays pinned for the whole session; only the
     * communication DEVICE changes. "auto" follows device presence the
     * way the system would (headset if one is connected, else speaker —
     * never silently the earpiece). A selected device that has vanished
     * falls back to the speaker with a UI note, never to silence.
     * Bluetooth headsets take the SCO communication route; the platform
     * resamples at that device boundary — the network/session rate is
     * never renegotiated (defect 2 fixed-config rule).
     */
    /** The output device the current selection WANTS (before pinning). */
    private fun desiredOutput(devices: List<CommDevice>): CommDevice? = when {
        outputDeviceSel == "auto" ->
            devices.firstOrNull { it.headset }
                ?: devices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        else -> devices.firstOrNull { deviceKey(it.type, it.name) == outputDeviceSel }
            ?: run {
                routeFallbackNote = str(R.string.route_fallback_selected_gone)
                devices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            }
    }

    fun applyOutputRoute() {
        val ctx = appContext ?: return
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val devices = commDevices()
            val target: CommDevice? = desiredOutput(devices)
            expectedOutputKey = target?.let { deviceKey(it.type, it.name) }
            if (Build.VERSION.SDK_INT >= 31) {
                if (target != null) {
                    val dev = am.availableCommunicationDevices.firstOrNull {
                        it.type == target.type && friendlyName(it) == target.name
                    }
                    // v2.1 defect: the return value was DISCARDED — a
                    // rejected pin left audio on the earpiece with no
                    // trace anywhere. Record failures; the drift check
                    // in adaptLoop keeps re-pinning until it sticks.
                    if (dev != null && !am.setCommunicationDevice(dev)) {
                        routePinFailed = true
                        Log.w(TAG, "setCommunicationDevice(${target.name}) returned false")
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                when {
                    target == null -> am.isSpeakerphoneOn = true
                    target.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> { am.isSpeakerphoneOn = true; stopLegacySco(am) }
                    target.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> { am.isSpeakerphoneOn = false; stopLegacySco(am) }
                    target.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                        am.isSpeakerphoneOn = false
                        try { am.startBluetoothSco(); am.isBluetoothScoOn = true } catch (_: Exception) {}
                    }
                    else -> { am.isSpeakerphoneOn = false; stopLegacySco(am) } // wired/USB route themselves
                }
            }
            // Volume-stream fix (§3.7): the playback track rides the MEDIA
            // stream now, and a media track's output is chosen per-track —
            // pin the track's preferred device to the SAME target the
            // communication route uses, or the selector and the actual
            // output diverge (earpiece selected, speaker sounding). Runs
            // after every route application, including the re-pin right
            // after buildTrack, so a fresh track adopts the target too.
            try {
                val t = track
                if (t != null && target != null) {
                    val outs = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                    val match = outs.firstOrNull { it.type == target.type && friendlyName(it) == target.name }
                        ?: outs.firstOrNull { it.type == target.type }
                    if (match != null) t.setPreferredDevice(match)
                }
            } catch (_: Exception) {}
            // AEC interplay: headset output -> echo risk minimal, relax the
            // suppressor (NLMS keeps running on the same final-render reference).
            echoProcessor.headsetMode = target?.headset == true
        } catch (e: Exception) {
            Log.w(TAG, "applyOutputRoute failed: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun stopLegacySco(am: android.media.AudioManager) {
        try { if (am.isBluetoothScoOn) { am.isBluetoothScoOn = false; am.stopBluetoothSco() } } catch (_: Exception) {}
    }

    /**
     * Route-drift watchdog (v2.2): once per second (from adaptLoop),
     * compare the CONFIRMED communication device against the pinned
     * target and re-pin on drift. OEM audio stacks silently reset the
     * communication device when streams start or devices change
     * (research note §1.2) — v2.1 pinned once and never verified, so a
     * reset left playback on the earpiece indefinitely.
     */
    private fun confirmOutputRoute() {
        if (Build.VERSION.SDK_INT < 31 || !running) return
        val desired = desiredOutput(commDevices()) ?: return
        val actual = actualOutputDevice() ?: return
        if (deviceKey(actual.type, actual.name) != deviceKey(desired.type, desired.name)) {
            routeReapplyCount++
            Log.w(TAG, "output route drift: actual=${actual.name} desired=${desired.name}; re-pinning (#$routeReapplyCount)")
            applyOutputRoute()
        }
    }

    /**
     * The preferred built-in mic: when the platform exposes several
     * TYPE_BUILTIN_MIC endpoints, the main (bottom, user-facing) one wins
     * (address heuristic — there is NO public vendor API that labels a
     * "best" mic; see TEST-REPORT-v2.1 for the honest limits).
     */
    private fun preferredBuiltinMic(am: android.media.AudioManager): android.media.AudioDeviceInfo? {
        val mics = am.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
            .filter { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC }
        if (mics.isEmpty()) return null
        if (Build.VERSION.SDK_INT >= 28) {
            mics.firstOrNull { try { it.address.contains("bottom", true) } catch (_: Exception) { false } }
                ?.let { return it }
        }
        return mics.first()
    }

    /** Apply the input selection to the live recorder (no rebuild). */
    fun applyInputDevice() {
        val rec = recorder ?: return
        if (Build.VERSION.SDK_INT < 28) return
        val ctx = appContext ?: return
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val inputs = am.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
            val dev: android.media.AudioDeviceInfo? = when {
                // auto + builtin both land on the phone's MAIN mic by
                // preference; the routed-device readout proves what's live.
                inputDeviceSel == "auto" || inputDeviceSel == "builtin" -> preferredBuiltinMic(am)
                else -> inputs.firstOrNull { deviceKey(it.type, friendlyName(it)) == inputDeviceSel }
            }
            rec.setPreferredDevice(dev)
        } catch (e: Exception) {
            Log.w(TAG, "applyInputDevice failed: ${e.message}")
        }
    }

    /** Actual capture device + mode (confirmed via the routed device). */
    fun actualInputText(): String {
        var name = str(R.string.device_phone_mic)
        val rec = recorder
        if (rec != null && Build.VERSION.SDK_INT >= 23) {
            try {
                rec.routedDevice?.let { name = friendlyName(it) }
            } catch (_: Exception) {}
        }
        if (rec == null) return name
        val mode = when {
            builtMicHd -> str(R.string.input_mode_hd)
            builtMicSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION -> str(R.string.input_mode_comm)
            else -> str(R.string.input_mode_standard)
        }
        return "$name · $mode"
    }

    /** Banner 切换 action: select the newly appeared headset as output. */
    fun acceptHeadsetOffer() {
        val key = pendingHeadsetKey ?: return
        outputDeviceSel = key
        appContext?.let {
            AppSettings.prefs(it).edit().putString(AppSettings.KEY_OUTPUT_DEVICE, key).apply()
        }
        pendingHeadsetName = null
        pendingHeadsetKey = null
        applyOutputRoute()
    }

    fun dismissHeadsetOffer() {
        pendingHeadsetName = null
        pendingHeadsetKey = null
    }

    /** AudioDeviceCallback body: live enumeration + plug/unplug handling. */
    private fun onAudioDevicesChanged(added: Array<out android.media.AudioDeviceInfo>?, removed: Array<out android.media.AudioDeviceInfo>?) {
        val current = commDevices()
        val currentKeys = current.map { deviceKey(it.type, it.name) }.toSet()
        // Headset appeared -> offer switch (banner); in auto mode the
        // re-apply below already follows the system-style behaviour.
        added?.forEach { d ->
            val key = deviceKey(d.type, friendlyName(d))
            if (isHeadsetType(d.type) && key !in knownDeviceKeys && running) {
                pendingHeadsetName = friendlyName(d)
                pendingHeadsetKey = key
            }
        }
        // Selected output vanished -> graceful fallback + note (in applyOutputRoute).
        removed?.forEach { d ->
            if (outputDeviceSel == deviceKey(d.type, friendlyName(d))) {
                routeFallbackNote = str(R.string.route_fallback_headset_gone)
            }
        }
        knownDeviceKeys.clear()
        knownDeviceKeys.addAll(currentKeys)
        applyOutputRoute() // restore OUR pinned selection after any system route event
    }

    fun acceptReturnAsk() {
        val ask = pendingReturnAsk ?: return
        pendingReturnAsk = null
        val s = ensureSession(ask.ip)
        s.returnAskRaised = false
        s.returnAskDismissed = false
        s.autoPaused = false
        s.autoFailEpisodes = 0
        s.episodeStartMs = 0L
        s.autoInitiated = false
        s.wantConnect = true
        s.lastHelloMs = 0L
        sendHello(s)
    }

    fun dismissReturnAsk() {
        val ask = pendingReturnAsk ?: return
        pendingReturnAsk = null
        sessions[ask.ip]?.let {
            it.returnAskRaised = false
            // hardening §5.1: the periodic probe re-runs every ~15 s; a
            // dismissed ask must stay dismissed, not re-post forever.
            it.returnAskDismissed = true
        }
    }

    /** Notification/UI 暂停: drop every session and suppress auto-connect. */
    fun pauseAll() {
        userPaused = true
        for (s in sessions.values) {
            if (s.connected) s.addr?.let { sendTo(Protocol.TYPE_BYE, ByteArray(0), it) }
            s.wantConnect = false
            s.connected = false
            s.resetJitterBuffer()
        }
        statusText = str(R.string.state_paused)
    }

    /** Resume after pauseAll(): re-arm auto-connect for present paired PCs. */
    fun resumeAll() {
        userPaused = false
        val now = System.currentTimeMillis()
        for (d in registry.values) {
            if (!d.autoConnect) continue
            val ip = presenceIp[d.deviceId] ?: continue
            if (now - (presence[d.deviceId] ?: 0L) > 3000) continue
            val s = ensureSession(ip)
            s.autoInitiated = false // explicit user intent: connect directly
            s.wantConnect = true
            s.lastHelloMs = 0L
        }
    }

    val sampleRateHz: Int get() = Protocol.rateHz(currentRateCode)
    val connectedCount: Int get() = sessions.values.count { it.connected }
    val anyConnected: Boolean get() = connectedCount > 0
    /** Worst (max) RTT among connected sessions, for the hero badge. */
    val worstRttMs: Double
        get() = sessions.values.filter { it.connected }.map { it.rttMs }.filter { it >= 0 }.maxOrNull() ?: -1.0
    val worstLossPct: Double
        get() = sessions.values.filter { it.connected }.map { it.lossPct }.maxOrNull() ?: 0.0
    val worstJitterMs: Double
        get() = sessions.values.filter { it.connected }.map { it.jitterMs }.maxOrNull() ?: 0.0
    val displayBufferMs: Int
        get() = sessions.values.filter { it.connected }.map { it.jitterBufferMs }.maxOrNull() ?: 30

    // ---------------- internals ----------------

    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null
    private var socket: DatagramSocket? = null
    @Volatile var phoneDeviceId: String = ""
    @Volatile var phoneName: String = Build.MODEL ?: "Android"

    val registry = ConcurrentHashMap<String, PairedDevice>()
    val sessions = ConcurrentHashMap<String, Session>() // keyed by IP
    private val presence = ConcurrentHashMap<String, Long>() // deviceId -> last beacon ms
    private val presenceIp = ConcurrentHashMap<String, String>() // deviceId -> ip
    /** Freshest known address per deviceId (v2.2 defect A): the ONLY
     *  source a session may be retargeted from after a roam — stored IPs
     *  are never trusted once a fresher sighting disagrees. */
    private val peerDirectory = PeerDirectory()

    // ---------------- cross-subnet sweep (v2.2.3, FB-6) ----------------
    // Broadcast/mDNS cannot cross the routers between Gray's routed
    // subnets (192.168.23/88/99.0/24). The sweep unicasts the SAME
    // DISCOVER packet to every host of the candidate /24s computed by
    // SubnetSweep.kt (local interfaces + paired last IPs + user extras);
    // a PC answers exactly as it does to broadcast. Pure logic + bounds
    // live in SubnetSweep.kt / SweepPlan (JVM-tested, tools/TestSweep.kt).
    @Volatile var extraSubnetsRaw: String = ""
    private val sweepPlan = SweepPlan()
    @Volatile private var sweepSig = ""
    private var sweepCycleDoneAtMs = 0L

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var lastLocalIps: Set<String> = emptySet()

    // ---------------- discovery hardening (locks / pinning / diag) ----------------
    // F4: without a MulticastLock most WiFi chipsets drop broadcast/
    // multicast frames before they reach our socket — the classic
    // "sweep works, automatic discovery doesn't" pattern. Both locks are
    // held for exactly the engine's lifetime (start -> stop).
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    /** Per-channel discovery counters + verdict inputs (§5.4). */
    val diag = DiscoveryDiag.Counters()
    @Volatile private var engineStartedMs = 0L
    private var lastPairedProbeMs = 0L
    /** Transport label of the network the socket is pinned to ("WIFI",
     *  "ETHERNET", or "" when unpinned) — diagnostics only. */
    @Volatile var pinnedNetworkLabel: String = ""
        private set
    /** Latest discovery verdict, recomputed every adapt tick (§5.4). */
    @Volatile var currentVerdict: DiscoveryDiag.Verdict = DiscoveryDiag.Verdict.NONE
        private set

    /** One-line discovery health for the UI / a diagnostic export:
     *  per-channel sent/received counters, the socket's pinned network
     *  and the current verdict. Plain technical text by design (same
     *  register as playoutDiagText). */
    fun discoveryDiagText(): String {
        val s = diag.snapshot()
        fun ch(c: DiscoveryDiag.Channel) = "${s.sent(c)}/${s.received(c)}"
        return "发现通道 发/收 — 广播 ${ch(DiscoveryDiag.Channel.BROADCAST)} · " +
            "mDNS ${ch(DiscoveryDiag.Channel.MDNS)} · 扫描 ${ch(DiscoveryDiag.Channel.SWEEP)} · " +
            "蓝牙 ${ch(DiscoveryDiag.Channel.BLE)} · 直连 ${ch(DiscoveryDiag.Channel.DIRECT)} · " +
            "网络 ${pinnedNetworkLabel.ifEmpty { "未绑定" }} · 判定 $currentVerdict"
    }

    private var recorder: AudioRecord? = null
    private var recorderRateHz = 0
    @Volatile private var recorderBuiltRate = -1
    private var track: AudioTrack? = null
    private var trackRateHz = 0
    @Volatile private var trackBuiltRate = -1
    private val fx = ArrayList<android.media.audiofx.AudioEffect>()
    private var goodWindows = 0
    @Volatile private var pendingRateCode = -1
    @Volatile private var pendingRateSinceMs = 0L
    private var lastRegistrySaveMs = 0L

    // ---------------- lifecycle ----------------

    fun start(context: Context) {
        appContext = context.applicationContext
        if (statusText.isEmpty()) statusText = str(R.string.engine_status_not_started)
        if (playoutDiagText.isEmpty()) playoutDiagText = str(R.string.playout_diag_not_started)
        prefs = appContext!!.getSharedPreferences("airbridge_registry", Context.MODE_PRIVATE)
        loadIdentityAndRegistry()
        reloadSettings()
        if (running) return
        try {
            val s = DatagramSocket(null)
            s.reuseAddress = true
            pinSocketToLan(s) // hardening §5.6: pin before bind (see rebindSocket)
            s.bind(java.net.InetSocketAddress(Protocol.PORT))
            s.broadcast = true
            s.soTimeout = 500
            socket = s
        } catch (e: Exception) {
            Log.e(TAG, "bind failed", e)
            statusText = str(R.string.engine_status_bind_failed, Protocol.PORT, e.message.toString())
            return
        }
        running = true
        engineStartedMs = System.currentTimeMillis()
        acquireWifiLocks()
        statusText = str(R.string.status_searching_pc)
        // ONE persistent audio configuration for the whole session (defects
        // 1+2, research note §5.1): save old mode -> MODE_IN_COMMUNICATION
        // -> audio focus -> explicit output route -> THEN build streams.
        // Nothing in this block ever runs again until stop(): no mode
        // flips, no route renegotiation mid-session.
        try {
            val am = appContext!!.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            savedAudioMode = am.mode
            @Suppress("DEPRECATION") savedSpeakerphone = am.isSpeakerphoneOn
            am.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
            // minSdk is 26 (build script), so the AudioFocusRequest path is
            // the only one. The request carries the MEDIA attributes of the
            // playback track (volume-stream fix): for the session we are a
            // media player (AUDIOFOCUS_GAIN), and the focus listener is
            // what pauses the bridge for a real phone call (§3.6) — it was
            // previously not registered at all.
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            val req = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(focusChangeListener, android.os.Handler(android.os.Looper.getMainLooper()))
                .build()
            audioFocusRequest = req
            am.requestAudioFocus(req)
            applyOutputRoute()
            // OEMs may silently reset communication routing when audio
            // devices come and go: re-apply OUR pinned route (restore, not
            // renegotiate).
            val cb = object : android.media.AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>?) { onAudioDevicesChanged(addedDevices, null) }
                override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>?) { onAudioDevicesChanged(null, removedDevices) }
            }
            deviceCallback = cb
            am.registerAudioDeviceCallback(cb, null)
            commDevices().forEach { knownDeviceKeys.add(deviceKey(it.type, it.name)) }
            if (Build.VERSION.SDK_INT >= 31) {
                // Confirmed-route tracking: the listener reports the device
                // audio ACTUALLY landed on; keep the AEC mode in sync.
                val listener = android.media.AudioManager.OnCommunicationDeviceChangedListener { dev ->
                    echoProcessor.headsetMode = dev != null && isHeadsetType(dev.type)
                }
                commDeviceListener = listener
                am.addOnCommunicationDeviceChangedListener({ it.run() }, listener)
            }
        } catch (e: Exception) {
            Log.w(TAG, "audio mode setup failed: ${e.message}")
        }
        thread(name = "ab-recv") { recvLoop() }
        thread(name = "ab-capture") { captureLoop() }
        thread(name = "ab-playout") { playoutLoop() }
        thread(name = "ab-adapt") { adaptLoop() }
        startDiscovery()
        registerNetworkCallback()
        // paired fast reconnect (v2.2.3 FB-6): aim at stored IPs right
        // away instead of waiting for a first broadcast sighting.
        tryDirectReconnectPaired(System.currentTimeMillis())
    }

    // ---------------- network-change resilience (v2.2 defect A) ----------------
    // Mesh WiFi roaming changes the phone's own IP (and sometimes the
    // PC's). A UDP socket created before the roam can keep sending via
    // the dead interface, and sessions keyed to stale IPs go nowhere.
    // On any local-address change: rebind the socket, restart mDNS,
    // re-resolve every session by deviceId against FRESH sightings, and
    // re-HELLO immediately — the stream resumes on the new network
    // without the user touching anything.

    private fun currentLocalIps(): Set<String> {
        val ctx = appContext ?: return emptySet()
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return emptySet()
        val out = HashSet<String>()
        try {
            for (n in cm.allNetworks) {
                val lp = cm.getLinkProperties(n) ?: continue
                for (la in lp.linkAddresses) {
                    val a = la.address
                    if (!a.isLoopbackAddress && a is java.net.Inet4Address) {
                        a.hostAddress?.let { out.add(it) }
                    }
                }
            }
        } catch (_: Exception) {}
        return out
    }

    private fun registerNetworkCallback() {
        val ctx = appContext ?: return
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        lastLocalIps = currentLocalIps()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = maybeNetworkChanged()
            override fun onLost(network: Network) = maybeNetworkChanged()
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = maybeNetworkChanged()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = maybeNetworkChanged()
        }
        networkCallback = cb
        try {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                    .build(),
                cb
            )
        } catch (e: Exception) {
            Log.w(TAG, "network callback register failed: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        networkCallback = null
        try {
            val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(cb)
        } catch (_: Exception) {}
    }

    private fun maybeNetworkChanged() {
        if (!running) return
        val ips = currentLocalIps()
        // An empty set mid-roam is transient; act on the new non-empty set.
        if (ips.isNotEmpty() && ips != lastLocalIps) {
            lastLocalIps = ips
            onNetworkChanged()
        }
    }

    private fun rebindSocket() {
        val old = socket
        try {
            val s = DatagramSocket(null)
            s.reuseAddress = true
            pinSocketToLan(s) // pin BEFORE bind so no packet can leak via the old default route
            s.bind(java.net.InetSocketAddress(Protocol.PORT))
            s.broadcast = true
            s.soTimeout = 500
            socket = s // recvLoop re-reads the field every iteration
        } catch (e: Exception) {
            Log.w(TAG, "socket rebind failed: ${e.message}")
            return
        }
        try { old?.close() } catch (_: Exception) {}
    }

    // ---------------- WiFi locks + LAN socket pinning (F4 / F3) ----------------

    private fun acquireWifiLocks() {
        val ctx = appContext ?: return
        try {
            val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager ?: return
            if (multicastLock == null) {
                val lock = wm.createMulticastLock("soundrelay-discovery")
                lock.setReferenceCounted(false)
                lock.acquire()
                multicastLock = lock
            }
            if (wifiLock == null) {
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= 29)
                    android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                else android.net.wifi.WifiManager.WIFI_MODE_FULL
                val lock = wm.createWifiLock(mode, "soundrelay-engine")
                lock.setReferenceCounted(false)
                lock.acquire()
                wifiLock = lock
            }
        } catch (e: Exception) {
            Log.w(TAG, "wifi locks failed: ${e.message}")
        }
    }

    private fun releaseWifiLocks() {
        try { if (multicastLock?.isHeld == true) multicastLock?.release() } catch (_: Exception) {}
        multicastLock = null
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
        wifiLock = null
        pinnedNetworkLabel = ""
    }

    /** The LAN network to pin discovery/session traffic to: WiFi or
     *  Ethernet, never a VPN (a VPN's own Network object also carries
     *  the underlying transports in its capabilities on some builds, so
     *  TRANSPORT_VPN is excluded explicitly). Null = no LAN available. */
    private fun lanNetwork(): Network? {
        val ctx = appContext ?: return null
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        var ethernet: Network? = null
        try {
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = n
            }
        } catch (_: Exception) {}
        return ethernet
    }

    /** Is the process DEFAULT network the home LAN? false only when we
     *  positively know it is not (VPN-only or cellular-only default) —
     *  that is the F3 hijack the WRONG_NETWORK verdict reports. */
    private fun defaultNetworkIsLan(): Boolean? {
        val ctx = appContext ?: return null
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        return try {
            val active = cm.activeNetwork ?: return null
            val caps = cm.getNetworkCapabilities(active) ?: return null
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> false
                else -> null
            }
        } catch (_: Exception) { null }
    }

    /** Pin the engine socket to the LAN network (§5.6) so broadcasts,
     *  sweep probes and HELLOs cannot egress into a VPN tunnel or the
     *  cellular default route while the phone shows "WiFi connected". */
    private fun pinSocketToLan(sock: DatagramSocket) {
        val n = lanNetwork()
        if (n == null) {
            pinnedNetworkLabel = ""
            return
        }
        try {
            n.bindSocket(sock)
            val ctx = appContext
            val cm = ctx?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val caps = cm?.getNetworkCapabilities(n)
            pinnedNetworkLabel = when {
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WIFI"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ETHERNET"
                else -> "LAN"
            }
        } catch (e: Exception) {
            pinnedNetworkLabel = ""
            Log.w(TAG, "socket pin to LAN failed: ${e.message}")
        }
    }

    /** Move a session to a fresh address for the SAME deviceId, carrying
     *  its connect intent and pairing state; the old address is abandoned. */
    private fun retargetSession(old: Session, newIp: String) {
        if (newIp == old.ip) return
        val ns = ensureSession(newIp)
        if (old.deviceId != null) ns.deviceId = old.deviceId
        ns.name = old.name
        if (old.os.isNotEmpty()) ns.os = old.os
        ns.wantConnect = old.wantConnect
        ns.autoInitiated = old.autoInitiated
        ns.pendingPairCode = old.pendingPairCode
        ns.pendingPairToken = old.pendingPairToken
        ns.pairQr = old.pairQr
        ns.helloAttempts = old.helloAttempts
        ns.firstHelloAttemptMs = old.firstHelloAttemptMs
        ns.wasPresent = true
        ns.lastBeaconMs = System.currentTimeMillis()
        ns.lastHelloMs = 0L // re-HELLO immediately at the new address
        old.wantConnect = false
        old.connected = false
        old.reResolving = false
        old.resetJitterBuffer()
        Log.i(TAG, "session retargeted ${old.ip} -> $newIp (deviceId=${ns.deviceId})")
    }

    private fun onNetworkChanged() {
        if (!running) return
        val now = System.currentTimeMillis()
        Log.i(TAG, "network changed, local IPs now $lastLocalIps — rebinding + re-resolving")
        statusText = str(R.string.engine_status_network_changed)
        rebindSocket()
        // mDNS browsing is scoped to the old network: restart it.
        try { mdnsDiscovery?.stop() } catch (_: Exception) {}
        try { mdnsDiscovery?.start() } catch (_: Exception) {}
        // Re-resolve every session by deviceId against fresh sightings —
        // never trust the address a session was born with after a roam.
        for (s in sessions.values.toList()) {
            val id = s.deviceId ?: continue
            val target = RecoveryPolicy.retargetIp(peerDirectory.freshIp(id, now), s.ip)
            if (target != null) retargetSession(s, target)
        }
        for (s in sessions.values) {
            if (s.wantConnect) s.lastHelloMs = 0L
            if (s.connected) { s.reResolving = true; s.resolvingSinceMs = now }
        }
        sendDiscoverBroadcast()
        // new network (v2.2.3 FB-6): sweep candidates rebuild on the
        // next adapt tick via the changed local-IP set; also re-aim
        // direct reconnect attempts at stored paired IPs immediately.
        sweepSig = ""
        tryDirectReconnectPaired(now)
    }

    // ---------------- nearby discovery (v2.1: LAN + mDNS + BLE merged) ----------------

    private var bleDiscovery: BleDiscovery? = null
    private var mdnsDiscovery: MdnsDiscovery? = null

    val bleStatusText: String get() = bleDiscovery?.statusText ?: str(R.string.ble_status_not_started)
    val mdnsStatusText: String get() = mdnsDiscovery?.statusText ?: str(R.string.mdns_status_not_started)

    private fun startDiscovery() {
        val ctx = appContext ?: return
        if (bleDiscovery == null) {
            bleDiscovery = BleDiscovery(ctx) { hint, addr, rssi -> onBleSighting(hint, addr, rssi) }
        }
        try { bleDiscovery?.start() } catch (e: Exception) { Log.w(TAG, "BLE start failed: ${e.message}") }
        if (mdnsDiscovery == null) {
            mdnsDiscovery = MdnsDiscovery(ctx) { name, ip -> onMdnsSighting(name, ip) }
        }
        try { mdnsDiscovery?.start() } catch (e: Exception) { Log.w(TAG, "mDNS start failed: ${e.message}") }
    }

    /** Called after the user grants the nearby-devices permission. */
    fun restartDiscovery() {
        try { bleDiscovery?.stop() } catch (_: Exception) {}
        try { bleDiscovery?.start() } catch (_: Exception) {}
    }

    /**
     * Pull-to-refresh (wave 3, plan A.1.4): restart the discovery layer
     * and start a fresh cross-subnet sweep cycle + paired probe right
     * away instead of waiting for their schedules. HARD CONSTRAINT from
     * the plan: this must never touch a live session — and it doesn't:
     * the BLE restart only cycles the scanner, the sweep plan only
     * re-queues DISCOVER probes (consent still decides connections
     * inside onDeviceReappeared), and the socket/sessions are untouched.
     */
    fun refreshDiscovery() {
        restartDiscovery()
        sweepPlan.beginCycle()
        sweepCycleDoneAtMs = 0L
        lastPairedProbeMs = 0L
    }

    /** Start the BLE scan if permission is now held but the scan never started
     *  (engine start usually precedes the permission grant). */
    fun ensureBleScan() {
        val ble = bleDiscovery ?: return
        if (ble.hasPermission() && !ble.scanning) restartDiscovery()
    }

    private fun stopDiscovery() {
        try { bleDiscovery?.stop() } catch (_: Exception) {}
        try { mdnsDiscovery?.stop() } catch (_: Exception) {}
    }

    /** One row of the AirDrop-style nearby sheet: transports merged by device. */
    data class NearbyDevice(
        val key: String,
        var deviceId: String?,
        var ip: String?,
        var name: String,
        var os: String,
        val sources: MutableSet<String>,
        var rssi: Int?,
        var bleAddress: String?,
        var lastSeenMs: Long
    )

    private val nearby = ConcurrentHashMap<String, NearbyDevice>()

    private fun nearbyByDeviceId(id: String): NearbyDevice? = nearby.values.firstOrNull { it.deviceId == id }
    private fun nearbyByIp(ip: String): NearbyDevice? = nearby.values.firstOrNull { it.ip == ip }

    private fun upsertNearby(deviceId: String?, ip: String?, name: String, os: String, source: String, rssi: Int?, bleAddress: String?) {
        val now = System.currentTimeMillis()
        var entry: NearbyDevice? = when {
            deviceId != null -> nearbyByDeviceId(deviceId) ?: nearby[deviceId]
            else -> null
        }
        if (entry == null && ip != null) entry = nearbyByIp(ip)
        if (entry == null && bleAddress != null) entry = nearby["ble:$bleAddress"]
        if (entry == null) {
            val key = deviceId ?: ip?.let { "ip:$it" } ?: "ble:$bleAddress"
            entry = NearbyDevice(key, deviceId, ip, name, os, mutableSetOf(), rssi, bleAddress, now)
            nearby[key] = entry
        }
        // merge: a later sighting may upgrade the key from ip/ble to deviceId
        if (deviceId != null && entry.deviceId == null) {
            entry.deviceId = deviceId
            val upgraded = NearbyDevice(deviceId, deviceId, entry.ip, entry.name, entry.os, entry.sources, entry.rssi, entry.bleAddress, entry.lastSeenMs)
            nearby.remove(entry.key)
            nearby[deviceId] = upgraded
            entry = upgraded
        }
        if (ip != null) entry.ip = ip
        if (name.isNotEmpty()) entry.name = name
        if (os.isNotEmpty()) entry.os = os
        entry.sources.add(source)
        if (rssi != null) entry.rssi = rssi
        if (bleAddress != null) entry.bleAddress = bleAddress
        entry.lastSeenMs = now
    }

    fun onBleSighting(deviceIdHint: String?, address: String, rssi: Int) {
        diag.inc(DiscoveryDiag.Channel.BLE, DiscoveryDiag.Dir.RECEIVED)
        upsertNearby(deviceIdHint, null, "", "", "BLE", rssi, address)
    }

    fun onMdnsSighting(name: String, ip: String) {
        diag.inc(DiscoveryDiag.Channel.MDNS, DiscoveryDiag.Dir.RECEIVED)
        upsertNearby(null, ip, name, "", "MDNS", null, null)
    }

    /** Fresh (<7 s, aligned with PeerDirectory.FRESH_MS) nearby devices
     *  for the pairing sheet: paired pinned first, then strongest BLE
     *  RSSI, then most recent. */
    fun nearbyDevices(): List<NearbyDevice> {
        val now = System.currentTimeMillis()
        return nearby.values
            .filter { now - it.lastSeenMs < PeerDirectory.FRESH_MS }
            .sortedWith(
                compareByDescending<NearbyDevice> { it.deviceId != null && registry.containsKey(it.deviceId) }
                    .thenByDescending { it.rssi ?: Int.MIN_VALUE }
                    .thenByDescending { it.lastSeenMs }
            )
    }

    fun stop() {
        if (!running) return
        stopCameraRelay()
        for (s in sessions.values) {
            s.addr?.let { sendTo(Protocol.TYPE_BYE, ByteArray(0), it) }
        }
        running = false
        unregisterNetworkCallback()
        releaseWifiLocks()
        stopDiscovery()
        nearby.clear()
        pendingReturnAsk = null
        sessions.clear()
        statusText = str(R.string.engine_status_stopped)
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        releaseDevices()
        playoutWatchdog.reset()
        echoProcessor.reset()
        howlGainDb = 0f
        nlmsActive = false
        nlmsBulkDelay = -1
        nlmsErleDb = 0.0
        try {
            val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            if (am != null) {
                deviceCallback?.let { try { am.unregisterAudioDeviceCallback(it) } catch (_: Exception) {} }
                deviceCallback = null
                commDeviceListener?.let { try { am.removeOnCommunicationDeviceChangedListener(it) } catch (_: Exception) {} }
                commDeviceListener = null
                pendingHeadsetName = null; pendingHeadsetKey = null
                if (Build.VERSION.SDK_INT >= 31) try { am.clearCommunicationDevice() } catch (_: Exception) {}
                audioFocusRequest?.let { try { am.abandonAudioFocusRequest(it) } catch (_: Exception) {} }
                audioFocusRequest = null
                if (savedAudioMode >= 0) {
                    am.mode = savedAudioMode
                    @Suppress("DEPRECATION") am.isSpeakerphoneOn = savedSpeakerphone
                }
            }
        } catch (_: Exception) {}
        focusPaused = false
        loopGuard.reset()
        saveRegistry()
    }

    // ---------------- registry persistence ----------------

    private fun loadIdentityAndRegistry() {
        val p = prefs ?: return
        var id = p.getString("phone_device_id", null)
        if (id == null) {
            id = UUID.randomUUID().toString().replace("-", "").substring(0, 12)
            p.edit().putString("phone_device_id", id).apply()
        }
        phoneDeviceId = id
        try {
            val arr = JSONArray(p.getString("devices", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val d = PairedDevice(
                    deviceId = o.getString("deviceId"),
                    name = o.optString("name"),
                    os = o.optString("os"),
                    lastIp = o.optString("lastIp"),
                    token = o.optString("token"),
                    autoConnect = o.optBoolean("autoConnect", true),
                    micSend = o.optBoolean("micSend", true),
                    speakerRecv = o.optBoolean("speakerRecv", true),
                    speakerVolume = o.optDouble("speakerVolume", 0.7).toFloat(),
                    muted = o.optBoolean("muted", false),
                    alias = o.optString("alias"),
                    lastSeenMs = o.optLong("lastSeenMs", 0L),
                    pinned = o.optBoolean("pinned", false)
                )
                registry[d.deviceId] = d
            }
        } catch (e: Exception) {
            Log.w(TAG, "registry load failed: ${e.message}")
        }
    }

    @Synchronized
    fun saveRegistry() {
        val p = prefs ?: return
        val arr = JSONArray()
        for (d in registry.values) {
            arr.put(JSONObject()
                .put("deviceId", d.deviceId).put("name", d.name).put("os", d.os)
                .put("lastIp", d.lastIp).put("token", d.token)
                .put("autoConnect", d.autoConnect).put("micSend", d.micSend)
                .put("speakerRecv", d.speakerRecv).put("speakerVolume", d.speakerVolume.toDouble())
                .put("muted", d.muted).put("alias", d.alias)
                .put("lastSeenMs", d.lastSeenMs).put("pinned", d.pinned))
        }
        p.edit().putString("devices", arr.toString()).apply()
        lastRegistrySaveMs = System.currentTimeMillis()
    }

    fun tokenHash(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    // ---------------- device view / UI actions ----------------

    fun deviceViews(): List<DeviceView> {
        val now = System.currentTimeMillis()
        val out = ArrayList<DeviceView>()
        val seenIps = HashSet<String>()
        // paired devices first (from registry), enriched with live session/presence
        for (d in registry.values) {
            val ip = presenceIp[d.deviceId] ?: d.lastIp
            val sess = sessions[ip]
            val online = (presence[d.deviceId]?.let { now - it < PeerDirectory.FRESH_MS } == true) || (sess?.connected == true)
            out.add(DeviceView(
                deviceId = d.deviceId, ip = ip, name = d.displayName, os = d.os,
                paired = true, online = online, connected = sess?.connected == true,
                rttMs = sess?.rttMs ?: -1.0, lossPct = sess?.lossPct ?: 0.0,
                autoConnect = d.autoConnect, micSend = d.micSend,
                speakerRecv = d.speakerRecv, speakerVolume = d.speakerVolume,
                muted = d.muted, lastSeenMs = maxOf(d.lastSeenMs, sess?.lastConnectedMs ?: 0L),
                autoPaused = sess?.autoPaused == true,
                justAutoConnected = sess?.justAutoConnectedMs?.let { now - it < 60000 } == true,
                pcAppVersion = sess?.pcAppVersion ?: "",
                versionMismatch = sess?.versionMismatch == true,
                connecting = sess?.let { isConnecting(it, now) } == true,
                pinned = d.pinned
            ))
            seenIps.add(ip)
        }
        // unpaired live sessions / discoveries
        for (s in sessions.values.sortedBy { it.name }) {
            if (s.ip in seenIps) continue
            if (s.deviceId != null && registry.containsKey(s.deviceId)) continue
            val online = now - s.lastBeaconMs < PeerDirectory.FRESH_MS || s.connected
            if (!online && !s.wantConnect) continue
            out.add(DeviceView(
                deviceId = s.deviceId, ip = s.ip, name = s.name, os = s.os,
                paired = false, online = online, connected = s.connected,
                rttMs = s.rttMs, lossPct = s.lossPct,
                autoConnect = false, micSend = true, speakerRecv = true, speakerVolume = 0.7f,
                muted = false, lastSeenMs = s.lastConnectedMs,
                autoPaused = false, justAutoConnected = false,
                pcAppVersion = s.pcAppVersion, versionMismatch = s.versionMismatch,
                connecting = isConnecting(s, now)
            ))
        }
        // spec order: streaming > online > offline; recent first within a tier
        return out.sortedWith(
            compareByDescending<DeviceView> { it.connected }
                .thenByDescending { it.online }
                .thenByDescending { it.lastSeenMs }
        )
    }

    /** Tap on an unpaired device: connect with an optional pairing code (empty = first-use pairing). */
    fun pairAndConnect(ip: String, pairCode: String?) {
        val s = ensureSession(ip)
        s.wantConnect = true
        s.autoInitiated = false
        s.pendingPairCode = pairCode?.trim()?.takeIf { it.isNotEmpty() }
        s.lastHelloMs = 0L
        sendHello(s)
    }

    /** QR pairing: we already hold the PC's token from the code; HELLO proves it via tokenHash. */
    fun pairWithToken(ip: String, deviceId: String, name: String, os: String, token: String) {
        val s = ensureSession(ip)
        s.deviceId = deviceId
        if (name.isNotEmpty()) s.name = name
        if (os.isNotEmpty()) s.os = os
        s.pendingPairToken = token
        s.pairQr = true
        s.wantConnect = true
        s.autoInitiated = false
        s.lastHelloMs = 0L
        sendHello(s)
    }

    // ---- 6-digit code search (broadcast HELLO; the PC whose code matches answers) ----
    @Volatile var codeSearch: String? = null
        private set
    @Volatile var codeSearchDeadlineMs = 0L
        private set

    fun startCodePairing(code: String) {
        codeSearch = code
        codeSearchDeadlineMs = System.currentTimeMillis() + 20000
    }

    fun cancelCodePairing() { codeSearch = null }

    /** The session (if any) whose pairing is waiting for the §4.3 verification confirm. */
    fun pendingPairSession(): Session? =
        sessions.values.firstOrNull { it.pendingPairToken != null && it.connected }

    /**
     * Register the pending pairing. Returns false when there is nothing
     * to register (session gone / no pending token / no device id) —
     * the caller must surface that instead of pretending success (B-05:
     * the confirm overlay used to close optimistically either way).
     */
    fun confirmPairing(ip: String, autoConnect: Boolean): Boolean {
        val s = sessions[ip] ?: return false
        val token = s.pendingPairToken ?: return false
        val deviceId = s.deviceId ?: return false
        val existing = registry[deviceId]
        if (existing == null) {
            registry[deviceId] = PairedDevice(
                deviceId = deviceId, name = s.name, os = s.os, lastIp = s.ip,
                token = token, autoConnect = autoConnect,
                lastSeenMs = System.currentTimeMillis()
            )
        } else {
            existing.token = token
            existing.lastIp = s.ip
            existing.name = s.name
            if (s.os.isNotEmpty()) existing.os = s.os
            existing.autoConnect = autoConnect
        }
        s.pendingPairToken = null
        s.pendingVerifyCode = null
        s.pendingPairCode = null
        saveRegistry()
        Log.i(TAG, "pairing confirmed with ${s.name} ($deviceId)")
        return true
    }

    fun cancelPairing(ip: String) {
        val s = sessions[ip] ?: return
        s.pendingPairToken = null
        s.pendingVerifyCode = null
        s.pendingPairCode = null
        disconnectDevice(ip)
    }

    /** Manual IP connect (unpaired until the PC answers with its identity). */
    fun connectManual(ip: String) {
        val s = ensureSession(ip)
        s.wantConnect = true
        s.lastHelloMs = 0L
        sendHello(s)
    }

    /** Card tap on an auto-connect-failed/paused device = manual retry (§5). */
    fun retryAutoConnect(ip: String) {
        val s = ensureSession(ip)
        s.autoPaused = false
        s.autoFailEpisodes = 0
        s.episodeStartMs = 0L
        s.autoInitiated = true
        s.wantConnect = true
        s.lastHelloMs = 0L
        sendHello(s)
    }

    /** Manual connect from a device card (auto-connect off but PC online). */
    fun connectDevice(ip: String) {
        val s = ensureSession(ip)
        s.autoInitiated = false
        s.wantConnect = true
        s.lastHelloMs = 0L
        sendHello(s)
    }

    fun disconnectDevice(ip: String) {
        val s = sessions[ip] ?: return
        s.addr?.let { sendTo(Protocol.TYPE_BYE, ByteArray(0), it) }
        s.wantConnect = false
        s.connected = false
        s.reResolving = false
        s.helloAttempts = 0
        s.firstHelloAttemptMs = 0L
        s.resetJitterBuffer()
    }

    fun setAutoConnect(deviceId: String, on: Boolean) {
        registry[deviceId]?.let { it.autoConnect = on; saveRegistry() }
        if (on) {
            presenceIp[deviceId]?.let { ip ->
                val s = ensureSession(ip)
                s.wantConnect = true
            }
        }
    }

    fun setMicSend(deviceId: String, on: Boolean) { registry[deviceId]?.let { it.micSend = on; saveRegistry() } }
    fun setSpeakerRecv(deviceId: String, on: Boolean) { registry[deviceId]?.let { it.speakerRecv = on; saveRegistry() } }
    fun setSpeakerVolume(deviceId: String, vol: Float) {
        registry[deviceId]?.let { it.speakerVolume = vol.coerceIn(0f, 1f); saveRegistry() }
    }

    fun setMuted(deviceId: String, muted: Boolean) {
        registry[deviceId]?.let { it.muted = muted; saveRegistry() }
    }

    fun renameDevice(deviceId: String, alias: String) {
        registry[deviceId]?.let { it.alias = alias.trim(); saveRegistry() }
    }

    /**
     * FB-12 "connecting" for the density forms (wave 3): an attempt is
     * genuinely in flight — the reconnect window after a drop
     * (reResolving), or HELLOs are actually going out for a wanted
     * session. Deliberately NOT just wantConnect: an auto-paused
     * session keeps wantConnect set while its fuse is tripped, and a
     * wanted session whose PC has been gone for a while is offline,
     * not connecting — both render as compact chips.
     */
    private fun isConnecting(s: Session, now: Long): Boolean =
        !s.connected && !s.autoPaused &&
            (s.reResolving || (s.wantConnect &&
                (s.lastHelloMs == 0L || now - s.lastHelloMs < 12000)))

    /** Compact-group pin (wave 3, plan A.2.5): sort order only, no
     *  effect on connection behaviour. */
    fun setPinned(deviceId: String, on: Boolean) {
        registry[deviceId]?.let { it.pinned = on; saveRegistry() }
    }

    fun pairingInfo(deviceId: String): String? {
        val d = registry[deviceId] ?: return null
        return str(R.string.pairing_info_format, d.deviceId, tokenHash(d.token).substring(0, 16), d.lastIp)
    }

    fun forgetDevice(deviceId: String) {
        registry.remove(deviceId)
        saveRegistry()
    }

    private fun ensureSession(ip: String): Session {
        return sessions.getOrPut(ip) {
            val s = Session(ip)
            try { s.addr = InetAddress.getByName(ip) } catch (_: Exception) {}
            s.winStartMs = System.currentTimeMillis()
            s
        }
    }

    private fun pairedFor(session: Session): PairedDevice? {
        session.deviceId?.let { return registry[it] }
        // match by IP (beacon may not have linked the id yet)
        return registry.values.firstOrNull { it.lastIp == session.ip }
    }

    private fun effectiveMicSend(s: Session): Boolean {
        if (!micEnabled) return false
        return pairedFor(s)?.micSend ?: true
    }

    private fun effectiveSpeakerRecv(s: Session): Boolean {
        if (!speakerEnabled) return false
        return pairedFor(s)?.speakerRecv ?: true
    }

    // ---------------- sending ----------------

    private fun sendTo(type: Int, payload: ByteArray, addr: InetAddress, seq: Int = 0, tsUs: Long = Protocol.nowUs(), flags: Int = Protocol.flags(Protocol.CODEC_PCM16, currentRateCode)) {
        val sock = socket ?: return
        val bytes = Protocol.pack(type, flags, seq, tsUs, payload)
        try { sock.send(DatagramPacket(bytes, bytes.size, addr, Protocol.PORT)) } catch (_: Exception) {}
    }

    private fun sendHello(s: Session) {
        val addr = s.addr ?: return
        val paired = pairedFor(s)
        val json = JSONObject()
            .put("app", "PhoneAudioBridge")
            .put("kind", "hello")
            .put("name", phoneName)
            .put("deviceId", phoneDeviceId)
            .put("rate", sampleRateHz)
            .put("codec", "pcm16")
            .put("appVersion", UpdateChecker.APP_VERSION)
            .put("protoVersion", Protocol.PROTO_VERSION)
            // camera-relay capability (wave 4): negotiated here, never by
            // raising PROTO_VERSION — old PCs simply never see the field.
            .put("caps", org.json.JSONArray().put(Protocol.CAP_VIDEO1))
        // The PC initializes its 手机音量 slider from OUR registry value
        // (single source of truth, §3.4) instead of a locally persisted
        // guess — report this PC's per-device gain with every HELLO.
        if (paired != null) json.put("speakerVolume", paired.speakerVolume.toDouble())
        if (paired != null && paired.token.isNotEmpty()) {
            json.put("tokenHash", tokenHash(paired.token))
        }
        s.pendingPairToken?.let { json.put("tokenHash", tokenHash(it)) }
        if (s.pairQr) json.put("pairQr", true)
        s.pendingPairCode?.let { json.put("pairCode", it) }
        sendTo(Protocol.TYPE_HELLO, json.toString().toByteArray(Charsets.UTF_8), addr)
        diag.inc(DiscoveryDiag.Channel.DIRECT, DiscoveryDiag.Dir.SENT)
        s.lastHelloMs = System.currentTimeMillis()
        // recovery bookkeeping (v2.2 defect A): attempts since the last
        // HELLO_ACK drive the retry backoff and the isolation verdict.
        if (!s.connected) {
            if (s.helloAttempts == 0) s.firstHelloAttemptMs = s.lastHelloMs
            s.helloAttempts++
        }
    }

    private fun codeSearchPacket(code: String): ByteArray {
        val json = JSONObject()
            .put("app", "PhoneAudioBridge")
            .put("kind", "hello")
            .put("name", phoneName)
            .put("deviceId", phoneDeviceId)
            .put("rate", sampleRateHz)
            .put("codec", "pcm16")
            .put("appVersion", UpdateChecker.APP_VERSION)
            .put("protoVersion", Protocol.PROTO_VERSION)
            .put("pairCode", code)
            .toString().toByteArray(Charsets.UTF_8)
        return Protocol.pack(Protocol.TYPE_HELLO, Protocol.flags(Protocol.CODEC_PCM16, currentRateCode), 0, Protocol.nowUs(), json)
    }

    /** Broadcast HELLO carrying a 6-digit pairing code; only the matching PC answers. */
    private fun sendCodeSearchHello(code: String) {
        val sock = socket ?: return
        val bytes = codeSearchPacket(code)
        try {
            sock.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("255.255.255.255"), Protocol.PORT))
            diag.inc(DiscoveryDiag.Channel.BROADCAST, DiscoveryDiag.Dir.SENT)
        } catch (_: Exception) {}
    }

    /**
     * C3 fix (v2.1): the same code-search HELLO also goes UNICAST to every
     * candidate address we already know — IPs seen via beacons/discovery in
     * this run plus every paired device's last IP — so the search still
     * lands when broadcast is filtered (AP isolation, guest networks,
     * Windows "public network" firewall profiles).
     */
    private fun sendCodeSearchUnicast(code: String) {
        val sock = socket ?: return
        val bytes = codeSearchPacket(code)
        val targets = LinkedHashSet<String>()
        targets.addAll(sessions.keys)
        targets.addAll(presenceIp.values)
        for (d in registry.values) if (d.lastIp.isNotEmpty()) targets.add(d.lastIp)
        for (ip in targets) {
            try {
                val addr = InetAddress.getByName(ip) ?: continue
                sock.send(DatagramPacket(bytes, bytes.size, addr, Protocol.PORT))
                diag.inc(DiscoveryDiag.Channel.DIRECT, DiscoveryDiag.Dir.SENT)
            } catch (_: Exception) {}
        }
    }

    private fun discoverPacket(): ByteArray {
        val json = JSONObject()
            .put("app", "PhoneAudioBridge")
            .put("kind", "discover")
            .put("name", phoneName)
            .put("deviceId", phoneDeviceId)
            .toString().toByteArray(Charsets.UTF_8)
        return Protocol.pack(Protocol.TYPE_DISCOVER, Protocol.flags(Protocol.CODEC_PCM16, currentRateCode), 0, Protocol.nowUs(), json)
    }

    private fun sendDiscoverBroadcast() {
        val sock = socket ?: return
        val bytes = discoverPacket()
        try {
            sock.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("255.255.255.255"), Protocol.PORT))
            diag.inc(DiscoveryDiag.Channel.BROADCAST, DiscoveryDiag.Dir.SENT)
        } catch (_: Exception) {}
    }

    /**
     * Cross-subnet sweep driver (v2.2.3 FB-6), called once per adapt
     * tick (1 s). Rebuilds the candidate plan when its inputs change
     * (local subnets / paired IPs / extra-subnet setting), then sends
     * at most [SWEEP_BATCH_PER_TICK] unicast DISCOVER probes — and only
     * while discovery is active (see [sweepActive]). A full cycle over
     * Gray's three subnets is ~760 probes ≈ 26 ticks; paired last IPs
     * and gateways are probed in the first tick.
     */
    /** Local IPv4 addresses split by interface kind (hardening F9):
     *  WiFi/Ethernet = physical; VPN and cellular = virtual. The sweep
     *  candidate ordering ranks physical above virtual so a pile of
     *  tunnel adapters can never crowd out real LAN subnets. */
    private fun localIpClasses(): Pair<List<Int>, List<Int>> {
        val physical = ArrayList<Int>()
        val virtual = ArrayList<Int>()
        val ctx = appContext ?: return physical to virtual
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return physical to virtual
        try {
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n)
                val isPhysical = caps != null &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
                val lp = cm.getLinkProperties(n) ?: continue
                for (la in lp.linkAddresses) {
                    val a = la.address
                    if (a.isLoopbackAddress || a !is java.net.Inet4Address) continue
                    val v = SubnetSweep.parseIpv4(a.hostAddress ?: continue) ?: continue
                    if (isPhysical) physical.add(v) else virtual.add(v)
                }
            }
        } catch (_: Exception) {}
        return physical to virtual
    }

    private fun driveSweep(nowMs: Long) {
        val (localPhysical, localVirtual) = localIpClasses()
        val localIps = localPhysical + localVirtual
        val pairedIps = registry.values.mapNotNull { SubnetSweep.parseIpv4(it.lastIp) }
        val extras = SubnetSweep.splitSpecs(extraSubnetsRaw)
        val sig = localIps.joinToString(",") + "|" + pairedIps.joinToString(",") + "|" + extras.joinToString(",")
        if (sig != sweepSig) {
            sweepSig = sig
            // priority: paired last IPs first, then PCs sighted this run —
            // a sighted PC gets a unicast DISCOVER back so the PC's own
            // nearby list learns THIS phone across the subnet boundary too.
            val prio = LinkedHashSet<Int>()
            prio.addAll(pairedIps)
            for (v in presenceIp.values) SubnetSweep.parseIpv4(v)?.let { prio.add(it) }
            sweepPlan.reset(
                SubnetSweep.candidateSubnets(localPhysical, localVirtual, pairedIps, extras),
                prio.toList(),
                localIps.toSet()
            )
            sweepCycleDoneAtMs = 0L
        }
        if (!sweepActive(nowMs)) return
        if (sweepPlan.isCycleComplete()) {
            if (sweepCycleDoneAtMs == 0L) sweepCycleDoneAtMs = nowMs
            if (nowMs - sweepCycleDoneAtMs >= SWEEP_CYCLE_PAUSE_MS) {
                sweepPlan.beginCycle()
                sweepCycleDoneAtMs = 0L
            } else return
        }
        val batch = sweepPlan.nextBatch(SWEEP_BATCH_PER_TICK)
        if (batch.isEmpty()) return
        val sock = socket ?: return
        val bytes = discoverPacket()
        for (target in batch) {
            try {
                val addr = InetAddress.getByName(SubnetSweep.formatIpv4(target)) ?: continue
                sock.send(DatagramPacket(bytes, bytes.size, addr, Protocol.PORT))
                diag.inc(DiscoveryDiag.Channel.SWEEP, DiscoveryDiag.Dir.SENT)
            } catch (_: Exception) {}
        }
    }

    /** Discovery is "active" for sweep purposes while the UI is in the
     *  foreground (device list / discover sheet), a code search runs, a
     *  session is trying to connect, or any paired PC with a stored
     *  address is unconnected. The last clause is the hardening §5.5
     *  change: the sweep is PROBING, not connecting — it stays allowed
     *  in every reconnect mode (including 从不自动), because presence
     *  learned by probing still feeds the consent machinery and the
     *  device list; it never by itself opens a session. Otherwise the
     *  sweep stays silent (battery) — broadcast discovery is unaffected. */
    private fun sweepActive(nowMs: Long): Boolean {
        if (uiInForeground || codeSearch != null) return true
        if (sessions.values.any { it.wantConnect && !it.connected }) return true
        if (!userPaused) {
            for (d in registry.values) {
                if (d.lastIp.isEmpty()) continue
                val s = sessions.values.firstOrNull { it.deviceId == d.deviceId }
                if (s == null || !s.connected) return true
            }
        }
        return false
    }

    /**
     * Paired-device fast reconnect (v2.2.3 FB-6): before any presence
     * exists (cold start, network change, a just-marked absence), aim a
     * session straight at the paired PC's stored last IP and let the
     * EXISTING consent machinery ([onDeviceReappeared]: per-device
     * auto-connect, the §5 fuse, the return-ask) decide — in parallel
     * with broadcast discovery and the sweep, which retarget the
     * session if a fresher sighting disagrees. Mode gate: 总是自动
     * always; 每次询问/从不 only for a KNOWN short absence (<60 s —
     * the dropout that just happened), never for a cold guess.
     */
    private fun tryDirectReconnectPaired(nowMs: Long) {
        if (userPaused) return
        for (d in registry.values) {
            if (!d.autoConnect || d.lastIp.isEmpty()) continue
            val existing = sessions.values.firstOrNull { it.deviceId == d.deviceId }
            if (existing != null && (existing.connected || existing.wantConnect)) continue
            if (peerDirectory.isFresh(d.deviceId, nowMs)) continue // presence flow owns it
            val knownAbsence = existing?.let { if (it.absentSinceMs > 0L) nowMs - it.absentSinceMs else -1L } ?: -1L
            val allowed = reconnectMode == RECONNECT_AUTO || knownAbsence in 1..SHORT_DROPOUT_MS
            if (!allowed) continue
            val s = ensureSession(d.lastIp)
            if (s.deviceId == null) s.deviceId = d.deviceId
            Log.i(TAG, "direct reconnect attempt to paired ${d.displayName} at stored ${d.lastIp}")
            onDeviceReappeared(s, d, if (knownAbsence >= 0L) knownAbsence else 0L, nowMs)
        }
    }

    /**
     * Periodic paired probing (hardening §5.1): every ~15 s, aim the
     * stored-IP machinery at every paired PC that is neither connected
     * nor freshly present — regardless of the cold-start restriction in
     * [tryDirectReconnectPaired]. The §3.1 hole this closes: the old
     * triggers were purely event-driven, so a PC that came online ten
     * minutes after the phone (with no network change and no presence
     * edge on the phone) was never retried. CONSENT IS UNCHANGED: the
     * decision still happens inside [onDeviceReappeared] — AUTO
     * connects, NEVER does nothing, ASK raises the return-ask (once;
     * a dismissed ask stays dismissed, see Session.returnAskDismissed).
     * The absence fed to the policy is honest: the session's absence
     * clock when known, else the registry's persisted last-seen, else
     * "long" — never the fake 0 that would read as a short dropout.
     */
    private fun probePairedStoredIps(nowMs: Long) {
        if (userPaused) return
        for (d in registry.values) {
            if (!d.autoConnect || d.lastIp.isEmpty()) continue
            val existing = sessions.values.firstOrNull { it.deviceId == d.deviceId }
            if (existing != null && (existing.connected || existing.wantConnect)) continue
            if (peerDirectory.isFresh(d.deviceId, nowMs)) continue // presence flow owns it
            val absentFor = when {
                existing != null && existing.absentSinceMs > 0L -> nowMs - existing.absentSinceMs
                d.lastSeenMs > 0L -> nowMs - d.lastSeenMs
                else -> Long.MAX_VALUE / 4
            }
            val s = ensureSession(d.lastIp)
            if (s.deviceId == null) s.deviceId = d.deviceId
            onDeviceReappeared(s, d, absentFor, nowMs)
        }
    }

    // ---------------- receive ----------------

    private fun recvLoop() {
        val buf = ByteArray(4096)
        while (running) {
            val sock = socket ?: break
            val pkt = DatagramPacket(buf, buf.size)
            try { sock.receive(pkt) } catch (_: Exception) { continue }
            val h = Protocol.unpack(buf, pkt.length) ?: continue
            val ip = pkt.address.hostAddress ?: continue
            val nowMs = System.currentTimeMillis()
            when (h.type) {
                Protocol.TYPE_BEACON, Protocol.TYPE_DISCOVER_RESP -> {
                    val json = String(buf, Protocol.HEADER_SIZE, pkt.length - Protocol.HEADER_SIZE, Charsets.UTF_8)
                    onPresence(json, ip, nowMs)
                }
                Protocol.TYPE_HELLO_ACK -> {
                    diag.inc(DiscoveryDiag.Channel.DIRECT, DiscoveryDiag.Dir.RECEIVED)
                    SubnetSweep.parseIpv4(ip)?.let { sweepPlan.noteResponse(it) }
                    val s = ensureSession(ip)
                    val wasConnected = s.connected
                    s.addr = pkt.address
                    s.connected = true
                    s.lastRxMs = nowMs
                    s.lastConnectedMs = nowMs
                    s.autoFailEpisodes = 0
                    s.episodeStartMs = 0L
                    s.helloAttempts = 0
                    s.firstHelloAttemptMs = 0L
                    s.reResolving = false
                    s.resolvingSinceMs = 0L
                    val json = String(buf, Protocol.HEADER_SIZE, pkt.length - Protocol.HEADER_SIZE, Charsets.UTF_8)
                    onHelloAck(s, json)
                    if (h.rateCode != currentRateCode) applyRate(h.rateCode)
                    statusText = str(R.string.hero_connected_title, connectedCount)
                    if (!wasConnected && s.autoInitiated && s.pendingPairToken == null) {
                        // §5: auto-connect happened — banner in UI, silent notification in background
                        s.justAutoConnectedMs = nowMs
                        onAutoConnected(s)
                    }
                    if (codeSearch != null && s.pendingPairToken != null) {
                        codeSearch = null // code search found its PC
                    }
                }
                Protocol.TYPE_AUDIO -> {
                    val s = ensureSession(ip)
                    s.addr = pkt.address
                    if (!s.connected) { s.connected = true; s.wantConnect = true }
                    s.lastRxMs = nowMs
                    s.reResolving = false
                    onAudioReceived(s, h, buf, pkt.length)
                }
                Protocol.TYPE_PING -> {
                    sendTo(Protocol.TYPE_PONG, ByteArray(0), pkt.address, seq = h.seq, tsUs = h.timestampUs)
                    sessions[ip]?.lastRxMs = nowMs
                }
                Protocol.TYPE_PONG -> {
                    val s = sessions[ip] ?: continue
                    val rtt = (Protocol.nowUs() - h.timestampUs) / 1000.0
                    if (rtt in 0.0..5000.0) s.rttMs = if (s.rttMs < 0) rtt else s.rttMs * 0.7 + rtt * 0.3
                    s.lastRxMs = nowMs
                    s.reResolving = false
                }
                Protocol.TYPE_RATE_REQ -> {
                    if (h.rateCode != currentRateCode) applyRate(h.rateCode)
                    val f = Protocol.flags(Protocol.CODEC_PCM16, currentRateCode)
                    sendTo(Protocol.TYPE_RATE_ACK, ByteArray(0), pkt.address, flags = f)
                }
                Protocol.TYPE_RATE_ACK -> {
                    if (pendingRateCode >= 0 && h.rateCode == pendingRateCode) {
                        applyRate(pendingRateCode)
                        pendingRateCode = -1
                    }
                }
                Protocol.TYPE_BYE -> {
                    sessions[ip]?.let { it.connected = false; it.resetJitterBuffer() }
                }
                Protocol.TYPE_SET_VOLUME -> {
                    // PC-side 手机音量 slider (remote control): apply at
                    // the SAME registry point the device-card slider uses
                    // (setSpeakerVolume) — one value, two handles (§3.4).
                    val s = sessions[ip]
                    if (s != null) {
                        val json = String(buf, Protocol.HEADER_SIZE, pkt.length - Protocol.HEADER_SIZE, Charsets.UTF_8)
                        val v = try { JSONObject(json).optDouble("speakerVolume", Double.NaN) } catch (_: Exception) { Double.NaN }
                        val deviceId = s.deviceId ?: pairedFor(s)?.deviceId
                        if (!v.isNaN() && deviceId != null) setSpeakerVolume(deviceId, v.toFloat())
                    }
                }
                Protocol.TYPE_VIDEO_CTRL -> {
                    // camera-relay control (wave 4): start/stop for THIS
                    // session only; the relay itself is shared and stops
                    // when no session wants it any more.
                    val s = sessions[ip]
                    if (s != null) {
                        val json = String(buf, Protocol.HEADER_SIZE, pkt.length - Protocol.HEADER_SIZE, Charsets.UTF_8)
                        handleVideoCtrl(s, json)
                    }
                }
                Protocol.TYPE_VIDEO_KEYFRAME_REQ -> {
                    // decoder on the PC lost sync: ask the encoder for an
                    // IDR, rate-limited to >=500 ms per session (plan §B.2).
                    val s = sessions[ip]
                    if (s != null && cameraRelayActive && nowMs - s.lastKeyframeReqMs >= 500) {
                        s.lastKeyframeReqMs = nowMs
                        cameraRelay?.requestKeyframe()
                    }
                }
            }
        }
    }

    private fun onPresence(json: String, ip: String, nowMs: Long) {
        diag.inc(DiscoveryDiag.Channel.BROADCAST, DiscoveryDiag.Dir.RECEIVED)
        // a sighting answers the sweep: don't re-probe this host this cycle
        SubnetSweep.parseIpv4(ip)?.let { sweepPlan.noteResponse(it) }
        val o = try { JSONObject(json) } catch (_: Exception) { return }
        val deviceId = o.optString("deviceId")
        val name = o.optString("name").ifEmpty { ip }
        val os = o.optString("os")
        val s = ensureSession(ip)
        s.name = name
        if (os.isNotEmpty()) s.os = os
        if (deviceId.isNotEmpty()) s.deviceId = deviceId
        s.lastBeaconMs = nowMs
        upsertNearby(deviceId.ifEmpty { null }, ip, name, os, "LAN", null, null)
        if (deviceId.isEmpty()) return
        presence[deviceId] = nowMs
        presenceIp[deviceId] = ip
        peerDirectory.note(deviceId, ip, nowMs)
        updateVersionInfo(s, o)
        val paired = registry[deviceId]
        if (paired != null) {
            // paired PC sighted: refresh identity, persist occasionally
            val changed = paired.lastIp != ip || paired.name != name
            paired.lastIp = ip
            paired.name = name
            if (os.isNotEmpty()) paired.os = os
            paired.lastSeenMs = nowMs
            if (changed || nowMs - lastRegistrySaveMs > 15000) saveRegistry()
            // presence edge: (re)appearance after an absence
            if (!s.wasPresent) {
                val absentFor = if (s.absentSinceMs > 0L) nowMs - s.absentSinceMs else 0L
                s.absentSinceMs = 0L
                onDeviceReappeared(s, paired, absentFor, nowMs)
            }
            s.wasPresent = true
        }
    }

    /**
     * A paired PC (re)appeared. Behaviour by settings (v2.1):
     * - userPaused or per-device auto-connect OFF: nothing.
     * - short dropout (<60 s) or first sighting this run: silent auto-resume.
     * - mode 总是自动: auto-connect.
     * - mode 从不自动: nothing (the card offers manual 连接).
     * - mode 每次询问 + long absence (>=10 min): raise the return prompt
     *   (in-app banner in foreground, notification with actions in background).
     * - mode 每次询问 + medium absence (60 s..10 min): auto-connect.
     */
    private fun onDeviceReappeared(s: Session, paired: PairedDevice, absentForMs: Long, nowMs: Long) {
        if (userPaused) return
        if (!paired.autoConnect || s.autoPaused) return
        if (s.connected || s.wantConnect) return
        when {
            absentForMs <= SHORT_DROPOUT_MS -> { s.autoInitiated = true; s.wantConnect = true }
            reconnectMode == RECONNECT_AUTO -> { s.autoInitiated = true; s.wantConnect = true }
            reconnectMode == RECONNECT_NEVER -> { /* manual only */ }
            absentForMs >= LONG_ABSENCE_MS -> if (!s.returnAskDismissed) raiseReturnAsk(s, paired, nowMs)
            else -> { s.autoInitiated = true; s.wantConnect = true }
        }
    }

    private fun raiseReturnAsk(s: Session, paired: PairedDevice, nowMs: Long) {
        if (s.returnAskRaised) return
        s.returnAskRaised = true
        pendingReturnAsk = ReturnAsk(paired.deviceId, s.ip, paired.displayName, nowMs)
        Log.i(TAG, "return ask raised for ${paired.displayName} (${s.ip})")
        if (!uiInForeground && notificationsEnabled) postReturnAskNotification(paired.displayName)
    }

    private fun postReturnAskNotification(name: String) {
        try {
            val ctx = appContext ?: return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel("airbridge_ask", str(R.string.notif_channel_return_ask), android.app.NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            val openIntent = android.content.Intent(ctx, MainActivity::class.java)
            val openPi = android.app.PendingIntent.getActivity(
                ctx, 3, openIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            fun actionPi(action: String, req: Int): android.app.PendingIntent {
                val i = android.content.Intent(ctx, BridgeService::class.java).setAction(action)
                return if (Build.VERSION.SDK_INT >= 26)
                    android.app.PendingIntent.getForegroundService(ctx, req, i, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
                else
                    android.app.PendingIntent.getService(ctx, req, i, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            }
            val n = android.app.Notification.Builder(ctx, "airbridge_ask")
                .setContentTitle("听说 LisTalk")
                .setContentText(str(R.string.return_ask_text, name))
                .setSmallIcon(android.R.drawable.stat_sys_speakerphone)
                .setContentIntent(openPi)
                .addAction(android.app.Notification.Action.Builder(null, str(R.string.action_connect), actionPi(BridgeService.ACTION_RETURN_CONNECT, 31)).build())
                .addAction(android.app.Notification.Action.Builder(null, str(R.string.action_ignore), actionPi(BridgeService.ACTION_RETURN_IGNORE, 32)).build())
                .setAutoCancel(true)
                .build()
            nm.notify(3, n)
        } catch (e: Exception) {
            Log.w(TAG, "return-ask notification failed: ${e.message}")
        }
    }

    /** Cancel the return-ask notification (user decided in-app). */
    fun clearReturnAskNotification() {
        try {
            val ctx = appContext ?: return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(3)
        } catch (_: Exception) {}
    }

    /** Unified versioning: learn the PC's appVersion/protoVersion from any
     *  of HELLO_ACK / BEACON / DISCOVER_RESP and flag a mismatch — the UI
     *  must surface it, never fail silently. Mismatch = protoVersion != 2
     *  or appVersion major.minor != the phone's (UpdateChecker.APP_VERSION). */
    private fun updateVersionInfo(s: Session, o: JSONObject) {
        val av = o.optString("appVersion")
        if (av.isNotEmpty()) s.pcAppVersion = av
        val pv = o.optInt("protoVersion", -1)
        if (pv > 0) s.pcProtoVersion = pv
        fun majorMinor(v: String): Pair<Int, Int> {
            val parts = v.split(".")
            return (parts.getOrNull(0)?.toIntOrNull() ?: 0) to (parts.getOrNull(1)?.toIntOrNull() ?: 0)
        }
        s.versionMismatch =
            (s.pcProtoVersion > 0 && s.pcProtoVersion != Protocol.PROTO_VERSION) ||
            (s.pcAppVersion.isNotEmpty() && majorMinor(s.pcAppVersion) != majorMinor(UpdateChecker.APP_VERSION))
    }

    private fun onHelloAck(s: Session, json: String) {
        val o = try { JSONObject(json) } catch (_: Exception) { return }
        val deviceId = o.optString("deviceId")
        if (deviceId.isNotEmpty()) s.deviceId = deviceId
        val name = o.optString("name")
        if (name.isNotEmpty()) s.name = name
        val os = o.optString("os")
        if (os.isNotEmpty()) s.os = os
        updateVersionInfo(s, o)
        // camera-relay capability of this PC (wave 4): video is enabled
        // for a session only when BOTH ends advertised "video1".
        val capsArr = o.optJSONArray("caps")
        if (capsArr != null) {
            val caps = HashSet<String>()
            for (i in 0 until capsArr.length()) capsArr.optString(i).takeIf { it.isNotEmpty() }?.let { caps.add(it) }
            s.pcCaps = caps
            // a PC that (re)connected mid-stream and wants video gets the
            // config for the already-running relay right away.
            if (s.videoWanted && cameraRelayActive) sendVideoConfigTo(s)
        }
        val verify = o.optString("verifyCode")
        if (verify.isNotEmpty()) s.pendingVerifyCode = verify
        val token = o.optString("token")
        if (token.isNotEmpty() && deviceId.isNotEmpty()) {
            val existing = registry[deviceId]
            if (existing != null && existing.token == token) {
                // already paired with this exact token: silent refresh, no confirm gate
                existing.lastIp = s.ip
                existing.name = s.name
                if (s.os.isNotEmpty()) existing.os = s.os
                existing.lastSeenMs = System.currentTimeMillis()
                s.pendingPairToken = null
                s.pendingPairCode = null
                saveRegistry()
            } else {
                // new pairing: hold as PENDING until the user passes the §4.3
                // verification confirm on the phone.
                s.pendingPairToken = token
                Log.i(TAG, "pairing pending confirm with ${s.name} ($deviceId)")
            }
        }
        s.pairQr = false
    }

    // ---------------- camera relay (wave 4) ----------------
    // Capture/encode lives in CameraRelay; this section owns the wire.
    // Encoded AUs land in a bounded queue and a dedicated sender thread
    // packetizes + fans them out, so video can never block the audio
    // send path (plan §B.2 hard rule). Under pressure the queue drops
    // VIDEO frames — audio is never dropped or delayed for video.

    @Volatile var cameraRelayActive = false
        private set
    @Volatile private var cameraRelay: CameraRelay? = null
    @Volatile private var videoConfigJson: String? = null
    @Volatile private var lastCameraError: String? = null

    private class VideoFrame(val au: ByteArray, val keyframe: Boolean, val captureUs: Long)

    private val videoTxQueue = java.util.concurrent.ArrayBlockingQueue<VideoFrame>(60)
    private var videoTxThread: Thread? = null
    @Volatile private var videoFrameId = 0
    @Volatile private var videoTxSeq = 0

    // counters (in-app diagnostics + bench verification via logcat)
    @Volatile var videoFramesEncoded = 0L
        private set
    @Volatile var videoFramesPacketized = 0L
        private set
    @Volatile var videoPacketsSent = 0L
        private set
    @Volatile var videoDroppedQueue = 0L
        private set
    @Volatile var videoDroppedNoTarget = 0L
        private set

    val videoStatsText: String
        get() = "encoded=$videoFramesEncoded packetized=$videoFramesPacketized sentPkts=$videoPacketsSent " +
            "droppedQueue=$videoDroppedQueue droppedNoTarget=$videoDroppedNoTarget active=$cameraRelayActive"

    private val videoListener = object : CameraRelay.Listener {
        override fun onConfig(configJson: JSONObject, csdAnnexB: ByteArray) {
            videoConfigJson = configJson.toString()
            // every session that asked for video gets the config (sent
            // x3 against UDP loss, plan §B.2); late joiners get it from
            // onHelloAck / their own VIDEO_CTRL start.
            for (s in sessions.values) if (s.videoWanted) sendVideoConfigTo(s)
        }

        override fun onEncodedFrame(au: ByteArray, keyframe: Boolean, captureUs: Long) {
            videoFramesEncoded++
            if (!videoTxQueue.offer(VideoFrame(au, keyframe, captureUs))) videoDroppedQueue++
        }

        override fun onError(reason: String) {
            Log.w(TAG, "VideoRelay error: $reason")
            lastCameraError = reason
            val wasActive = cameraRelayActive
            cameraRelayActive = false
            cameraRelay = null
            if (wasActive) for (s in sessions.values) if (s.videoWanted) sendVideoState(s, false, reason)
            refreshServiceForegroundType()
        }
    }

    @Synchronized
    fun startCameraRelay(quality: String = "720p") {
        if (cameraRelayActive) return
        val ctx = appContext
        if (ctx == null) {
            lastCameraError = "no context"
            return
        }
        if (ctx.checkSelfPermission(android.Manifest.permission.CAMERA) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            lastCameraError = "camera permission not granted"
            Log.w(TAG, "VideoRelay: CAMERA permission not granted")
            return
        }
        val relay = CameraRelay(ctx, videoListener)
        cameraRelay = relay
        videoConfigJson = null
        videoFrameId = 0
        videoTxSeq = 0
        cameraRelayActive = true
        ensureVideoTxThread()
        relay.start(quality)
        refreshServiceForegroundType()
        Log.i(TAG, "VideoRelay started (quality=$quality)")
    }

    @Synchronized
    fun stopCameraRelay() {
        if (!cameraRelayActive && cameraRelay == null) return
        cameraRelayActive = false
        val relay = cameraRelay
        cameraRelay = null
        videoConfigJson = null
        videoTxQueue.clear()
        try { relay?.stop() } catch (_: Exception) {}
        refreshServiceForegroundType()
        Log.i(TAG, "VideoRelay stopped: $videoStatsText")
    }

    /** The one UI hook for wave 4 stage 1 (MainActivity's toggle
     * button); the full camera UI is wave 3. Returns the new state. */
    fun toggleCameraRelay(): Boolean {
        if (cameraRelayActive) {
            stopCameraRelay()
            return false
        }
        startCameraRelay()
        return cameraRelayActive
    }

    private fun ensureVideoTxThread() {
        if (videoTxThread?.isAlive == true) return
        val t = Thread({
            var lastLogMs = 0L
            while (running) {
                val frame = try {
                    videoTxQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    null
                }
                if (frame == null) {
                    if (!cameraRelayActive && videoTxQueue.isEmpty()) break
                    continue
                }
                val payloads = try {
                    VideoPackets.splitPayloads(videoFrameId, frame.au, frame.keyframe)
                } catch (_: Exception) {
                    continue
                }
                videoFrameId++
                videoFramesPacketized++
                val targets = sessions.values.filter { it.connected && it.videoWanted && it.addr != null }
                val now = System.currentTimeMillis()
                if (now - lastLogMs >= 2000) {
                    lastLogMs = now
                    Log.i(TAG, "VideoRelay $videoStatsText targets=${targets.size}")
                }
                if (targets.isEmpty()) {
                    videoDroppedNoTarget++
                    continue
                }
                var seq = videoTxSeq
                for (payload in payloads) {
                    for (s in targets) {
                        // flags = 0: the header's codec/rate bits are an
                        // audio concept; video types don't use them.
                        sendTo(Protocol.TYPE_VIDEO_DATA, payload, s.addr!!, seq = seq, tsUs = frame.captureUs, flags = 0)
                        videoPacketsSent++
                    }
                    seq++
                }
                videoTxSeq = seq
            }
        }, "sr-video-tx")
        t.isDaemon = true
        videoTxThread = t
        t.start()
    }

    private fun sendVideoConfigTo(s: Session) {
        val addr = s.addr ?: return
        val json = videoConfigJson ?: return
        // plan §B.2: the config is small and priceless (nothing decodes
        // without the CSD) — send it 3x against UDP loss.
        repeat(3) { sendTo(Protocol.TYPE_VIDEO_CONFIG, json.toByteArray(Charsets.UTF_8), addr) }
    }

    private fun sendVideoState(s: Session, streaming: Boolean, reason: String? = null) {
        val addr = s.addr ?: return
        val json = JSONObject().put("action", "state").put("streaming", streaming)
        if (reason != null) json.put("reason", reason)
        sendTo(Protocol.TYPE_VIDEO_CTRL, json.toString().toByteArray(Charsets.UTF_8), addr)
    }

    private fun handleVideoCtrl(s: Session, json: String) {
        val o = try { JSONObject(json) } catch (_: Exception) { return }
        when (o.optString("action")) {
            "start" -> {
                // capability gate (plan §B.2): only a PC that advertised
                // video1 in its HELLO_ACK may start the camera.
                if (Protocol.CAP_VIDEO1 !in s.pcCaps) {
                    sendVideoState(s, false, "pc did not advertise video1")
                    return
                }
                s.videoWanted = true
                startCameraRelay(o.optString("quality", "720p"))
                sendVideoState(s, cameraRelayActive, if (cameraRelayActive) null else lastCameraError)
                if (cameraRelayActive) sendVideoConfigTo(s)
            }
            "stop" -> {
                s.videoWanted = false
                if (sessions.values.none { it.videoWanted }) stopCameraRelay()
                sendVideoState(s, false)
            }
        }
    }

    /** Android 14: an FGS that uses the camera must carry the camera
     * foreground-service type. Ask BridgeService to re-post its
     * foreground notification with the right type set (it reads
     * [cameraRelayActive]). A no-op while the service isn't running —
     * in Activity-bound mode the app is in the foreground, where
     * camera access needs no FGS type. */
    private fun refreshServiceForegroundType() {
        if (!BridgeService.isRunning) return
        val ctx = appContext ?: return
        try {
            ctx.startService(android.content.Intent(ctx, BridgeService::class.java).setAction(BridgeService.ACTION_REFRESH_FG))
        } catch (_: Exception) {}
    }

    // ---- auto-connect event (§5: quiet banner in-app, silent notification in background) ----
    @Volatile var uiInForeground = false
    @Volatile var lastAutoEventText: String = ""
    @Volatile var lastAutoEventAtMs = 0L
    @Volatile var lastAutoEventIps: List<String> = emptyList()

    private fun onAutoConnected(s: Session) {
        val now = System.currentTimeMillis()
        val recent = sessions.values.filter { it.justAutoConnectedMs > 0 && now - it.justAutoConnectedMs < 4000 }
        val names = recent.map { pairedFor(it)?.displayName ?: it.name }
        lastAutoEventText = if (names.size > 1) {
            str(R.string.auto_event_multi, names.size, names.joinToString(str(R.string.list_separator)))
        } else {
            str(R.string.auto_event_single, pairedFor(s)?.displayName ?: s.name)
        }
        lastAutoEventIps = recent.map { it.ip }
        lastAutoEventAtMs = now
        if (!uiInForeground && notificationsEnabled) postAutoConnectNotification(lastAutoEventText)
    }

    private fun postAutoConnectNotification(text: String) {
        try {
            val ctx = appContext ?: return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel("airbridge_auto", str(R.string.notif_channel_auto), android.app.NotificationManager.IMPORTANCE_LOW)
                )
            }
            val intent = android.content.Intent(ctx, MainActivity::class.java)
            val pi = android.app.PendingIntent.getActivity(
                ctx, 2, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val n = android.app.Notification.Builder(ctx, "airbridge_auto")
                .setContentTitle("听说 LisTalk")
                .setContentText(str(R.string.auto_connected_notif_text, text))
                .setSmallIcon(android.R.drawable.stat_sys_speakerphone)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(2, n)
        } catch (e: Exception) {
            Log.w(TAG, "auto-connect notification failed: ${e.message}")
        }
    }

    private fun onAudioReceived(s: Session, h: Protocol.Header, buf: ByteArray, len: Int) {
        if (s.lastRxSeq >= 0) {
            val gap = h.seq - s.lastRxSeq
            if (gap > 1) { val lost = (gap - 1).toLong(); s.winLost += lost }
        }
        if (s.lastRxSeq < 0 || h.seq > s.lastRxSeq) s.lastRxSeq = h.seq
        val transit = Protocol.nowUs() - h.timestampUs
        if (s.prevTransitUs != Long.MIN_VALUE) {
            val d = abs(transit - s.prevTransitUs).toDouble()
            s.jitterUs += (d - s.jitterUs) / 16.0
        }
        s.prevTransitUs = transit
        s.rxPackets++; s.winRx++
        rxPackets++
        val payload = ByteArray(len - Protocol.HEADER_SIZE)
        System.arraycopy(buf, Protocol.HEADER_SIZE, payload, 0, payload.size)
        synchronized(s.jb) {
            if (s.jbNextSeq == -1 || h.seq < s.jbNextSeq - 200) { s.jbNextSeq = h.seq; s.jbPrimed = false }
            if (h.seq >= s.jbNextSeq) {
                s.jb[h.seq] = payload
                while (s.jb.size > 20) { s.jb.pollFirstEntry(); s.jbNextSeq = s.jb.firstKey() }
            }
        }
    }

    /** Pop one frame from a session's jitter buffer; null = conceal with silence. */
    private fun popFrame(s: Session): ByteArray? {
        synchronized(s.jb) {
            if (!s.jbPrimed) {
                val targetFrames = (s.jitterBufferMs / 10).coerceIn(2, 6)
                if (s.jb.size >= targetFrames && s.jb.isNotEmpty()) {
                    s.jbNextSeq = s.jb.firstKey()
                    s.jbPrimed = true
                } else return null
            }
            val frame = s.jb.remove(s.jbNextSeq)
            s.jbNextSeq++
            return frame
        }
    }

    // ---------------- adaptation / presence loop ----------------

    private fun adaptLoop() {
        var lastDiscoverMs = 0L
        while (running) {
            try { Thread.sleep(1000) } catch (_: Exception) { break }
            if (!running) break
            val nowMs = System.currentTimeMillis()
            if (!anyConnected) { micLevel *= 0.5f; speakerLevel *= 0.5f }

            // keep discovering (PCs also beacon, but broadcast covers quiet ones)
            if (nowMs - lastDiscoverMs >= 2000) {
                sendDiscoverBroadcast()
                lastDiscoverMs = nowMs
            }

            // cross-subnet unicast sweep (v2.2.3 FB-6): the only
            // discovery channel that crosses routers between subnets
            driveSweep(nowMs)

            // periodic paired probing (hardening §5.1): stored-IP
            // attempts are no longer only event-driven — a paired PC
            // that comes online later is retried every ~15 s, with the
            // consent decision still inside onDeviceReappeared.
            if (DiscoveryDiag.ProbeSchedule.isDue(lastPairedProbeMs, nowMs)) {
                lastPairedProbeMs = nowMs
                probePairedStoredIps(nowMs)
            }

            // route-drift watchdog: re-pin if the OEM stack moved the
            // confirmed communication device away from our target.
            confirmOutputRoute()

            // presence staleness edges (v2.1): a session whose beacons stop
            // becomes "absent" — the absence clock for the re-entry logic.
            for (s in sessions.values) {
                if (s.wasPresent && s.lastBeaconMs > 0L && nowMs - s.lastBeaconMs > PeerDirectory.FRESH_MS) {
                    s.wasPresent = false
                    s.absentSinceMs = s.lastBeaconMs
                    // absence edge (v2.2.3 FB-6): immediately aim a
                    // direct unicast attempt at the stored IP instead of
                    // waiting for a broadcast that may never cross.
                    tryDirectReconnectPaired(nowMs)
                }
            }

            // 6-digit code pairing search: broadcast + unicast fan-out (C3)
            // until a PC answers or the deadline passes
            val searching = codeSearch
            if (searching != null) {
                if (nowMs > codeSearchDeadlineMs) {
                    codeSearch = null
                } else {
                    sendCodeSearchHello(searching)
                    sendCodeSearchUnicast(searching)
                }
            }

            var anyBad = false
            var allGood = connectedCount > 0
            for (s in sessions.values) {
                s.jitterMs = s.jitterUs / 1000.0
                // window loss every ~2 s
                if (nowMs - s.winStartMs >= 2000) {
                    val total = s.winRx + s.winLost
                    s.lossPct = if (total > 0) s.winLost * 100.0 / total else 0.0
                    s.winRx = 0; s.winLost = 0; s.winStartMs = nowMs
                }
                if (s.connected) {
                    // keepalive
                    val addr = s.addr
                    if (addr != null) {
                        s.pingSeq++
                        sendTo(Protocol.TYPE_PING, ByteArray(0), addr, seq = s.pingSeq)
                    }
                    // dead-peer detection (v2.2 defect A): >2 s of silence
                    // enters the re-resolving state IMMEDIATELY — burst a
                    // DISCOVER, retarget from any fresher sighting, and let
                    // the status line say 正在重新连接… instead of silently
                    // sending audio into the void. The 5 s drop below then
                    // formally closes the session.
                    if (s.lastRxMs > 0 && nowMs - s.lastRxMs > RecoveryPolicy.DEAD_PEER_MS && !s.reResolving) {
                        s.reResolving = true
                        s.resolvingSinceMs = nowMs
                        sendDiscoverBroadcast()
                        val id = s.deviceId
                        if (id != null) {
                            RecoveryPolicy.retargetIp(peerDirectory.freshIp(id, nowMs), s.ip)
                                ?.let { retargetSession(s, it) }
                        }
                    }
                    if (s.lastRxMs > 0 && nowMs - s.lastRxMs > 5000) {
                        s.connected = false
                        reconnectCount++
                        s.resetJitterBuffer()
                        statusText = str(R.string.engine_status_disconnected_reconnecting, s.name)
                    }
                    if (s.lossPct > 5.0 || s.jitterMs > 15.0) anyBad = true
                    if (!(s.lossPct < 1.0 && s.jitterMs < 5.0)) allGood = false
                } else if (s.wantConnect) {
                    // (re)connect while the PC is (recently) present
                    val present = nowMs - s.lastBeaconMs < 10000 || s.lastBeaconMs == 0L
                    // §5 fuse: an auto-connect episode that fails for 10 s counts
                    // once; 3 failed episodes pause auto-retry until the PC has
                    // been gone >10 s (a fresh appearance resets the fuse).
                    if (s.autoInitiated) {
                        if (!present && nowMs - s.lastBeaconMs > 10000) {
                            s.autoPaused = false
                            s.autoFailEpisodes = 0
                            s.episodeStartMs = 0L
                        }
                        if (present) {
                            if (s.episodeStartMs == 0L) s.episodeStartMs = nowMs
                            if (nowMs - s.episodeStartMs > 10000) {
                                s.autoFailEpisodes++
                                s.episodeStartMs = 0L
                                if (s.autoFailEpisodes >= 3) {
                                    s.autoPaused = true
                                    Log.w(TAG, "auto-connect paused for ${s.name} after 3 failed episodes")
                                }
                            }
                        }
                    }
                    if (present && !s.autoPaused) {
                        // re-resolve before every retry (v2.2 defect A): a
                        // fresher sighting for this deviceId wins over the
                        // address this session was born with.
                        val id = s.deviceId
                        val retargeted = if (id != null) {
                            RecoveryPolicy.retargetIp(peerDirectory.freshIp(id, nowMs), s.ip)
                                ?.let { retargetSession(s, it); true } ?: false
                        } else false
                        // exponential backoff, capped (SessionRecovery.kt)
                        if (!retargeted && nowMs - s.lastHelloMs >= RecoveryPolicy.helloBackoffMs(s.helloAttempts)) {
                            sendHello(s)
                        }
                    }
                }
            }

            // a peer that vanished entirely is "searching", not "reconnecting"
            for (s in sessions.values) {
                if (!s.connected && s.reResolving && nowMs - maxOf(s.lastRxMs, s.lastBeaconMs) > 10000) {
                    s.reResolving = false
                }
            }

            // isolation verdict (v2.2 defect A): the PC is VISIBLE (fresh
            // beacons) yet repeated HELLOs go unanswered — that is not
            // "not found". Hardening (F13): the status names BOTH causes
            // (router isolation OR the PC's firewall / auto-reconnect
            // setting) — the old text blamed the router alone, which was
            // wrong whenever the PC firewall was at fault. Suppressed
            // while a pairing confirm is pending (the PC may be waiting
            // on us).
            var isolatedName: String? = null
            for (s in sessions.values) {
                if (s.connected || s.pendingPairToken != null || codeSearch != null) continue
                val id = s.deviceId ?: continue
                if (s.helloAttempts == 0) continue
                val resolvingFor = if (s.firstHelloAttemptMs > 0) nowMs - s.firstHelloAttemptMs else 0L
                val silence = if (s.lastRxMs > 0) nowMs - s.lastRxMs else Long.MAX_VALUE / 4
                val st = RecoveryPolicy.linkState(silence, peerDirectory.isFresh(id, nowMs), s.helloAttempts, resolvingFor)
                if (st == RecoveryPolicy.LinkState.ISOLATED) {
                    isolatedName = pairedFor(s)?.displayName ?: s.name
                    break
                }
            }

            // global rate adaptation: worst link governs the shared capture rate.
            // HYSTERESIS (defect 2): a negotiated change is applied only at a
            // silence/idle boundary and at most once per 20 s — the device
            // streams themselves are never rebuilt (fixed 48 kHz), so an
            // apply is now just a software resample-factor change, and even
            // that never lands mid-sound.
            if (micLevel < 0.02f && speakerLevel < 0.02f) {
                if (silenceSinceMs == 0L) silenceSinceMs = nowMs
            } else silenceSinceMs = 0L
            val idleBoundary = !anyConnected || (silenceSinceMs > 0L && nowMs - silenceSinceMs >= 1000)
            if (pendingRateCode >= 0 && nowMs - pendingRateSinceMs > 1500 &&
                nowMs - lastRateApplyMs >= 20_000 && idleBoundary) {
                applyRate(pendingRateCode)
                pendingRateCode = -1
            } else if (pendingRateCode < 0 && anyConnected) {
                if (anyBad && currentRateCode < Protocol.RATE_16K) {
                    requestRate(currentRateCode + 1)
                    goodWindows = 0
                } else if (allGood && currentRateCode > Protocol.RATE_48K) {
                    goodWindows++
                    if (goodWindows >= 15) { requestRate(currentRateCode - 1); goodWindows = 0 }
                } else {
                    goodWindows = 0
                }
            }

            // prune dead unpaired sessions (never paired, gone >60 s, not wanted)
            val it = sessions.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                val s = e.value
                val paired = s.deviceId != null && registry.containsKey(s.deviceId)
                if (!paired && !s.connected && !s.wantConnect && nowMs - maxOf(s.lastBeaconMs, s.lastRxMs) > 60000) {
                    it.remove()
                }
            }
            // discovery verdict (hardening §5.4): rule-based over the
            // per-channel counters, so "nothing found" says WHY instead
            // of spinning silently. Selection logic is the JVM-proven
            // pure core (DiscoveryDiag.kt); wording lives in strings.xml.
            val diagSnap = diag.snapshot()
            val verdict = DiscoveryDiag.VerdictPolicy.select(
                nowMs = nowMs, startedMs = engineStartedMs,
                totalSent = diagSnap.totalSent, totalReceived = diagSnap.totalReceived,
                hasPaired = registry.isNotEmpty(),
                visibleUnreachable = isolatedName != null,
                anyAutoPaused = sessions.values.any { it.autoPaused },
                onLanNetwork = defaultNetworkIsLan(),
                sweepGatedOff = false // the phone sweep has no UI gate (§5.5)
            )
            currentVerdict = verdict
            statusText = when {
                verdict == DiscoveryDiag.Verdict.VISIBLE_UNREACHABLE ->
                    str(R.string.engine_status_isolated, isolatedName ?: "")
                verdict == DiscoveryDiag.Verdict.WRONG_NETWORK ->
                    str(R.string.engine_status_wrong_network)
                verdict == DiscoveryDiag.Verdict.SEND_NO_RECEIVE ->
                    str(R.string.engine_status_send_no_receive)
                sessions.values.any { it.reResolving } -> str(R.string.engine_status_reconnecting)
                connectedCount > 0 -> str(R.string.hero_connected_title, connectedCount)
                verdict == DiscoveryDiag.Verdict.AUTO_PAUSED ->
                    str(R.string.engine_status_auto_paused)
                sessions.values.any { it.wantConnect } -> str(R.string.engine_status_connecting)
                else -> str(R.string.status_searching_pc)
            }
        }
    }

    private fun requestRate(rateCode: Int) {
        pendingRateCode = rateCode
        pendingRateSinceMs = System.currentTimeMillis()
        val f = Protocol.flags(Protocol.CODEC_PCM16, rateCode)
        for (s in sessions.values) {
            if (s.connected) s.addr?.let { sendTo(Protocol.TYPE_RATE_REQ, ByteArray(0), it, flags = f) }
        }
    }

    private fun applyRate(rateCode: Int) {
        if (rateCode == currentRateCode) return
        currentRateCode = rateCode
        lastRateApplyMs = System.currentTimeMillis()
        echoProcessor.reset() // NLMS delays/taps are in samples: re-lock at the new rate
        echoProcessor.sampleRateHz = Protocol.rateHz(rateCode) // HPF coefficient follows the frame rate
        loopGuard.reset()     // guard history is in samples at the old rate
        for (s in sessions.values) {
            s.lastRxSeq = -1
            s.prevTransitUs = Long.MIN_VALUE
            s.resetJitterBuffer()
        }
    }

    // ---------------- capture: phone mic -> ALL connected PCs (fan-out) ----------------

    private fun buildRecorder(): Boolean {
        releaseRecorder()
        val wanted = sampleRateHz
        // Mic mode is resolved ONCE here (session boundary — defect 2
        // fixed-config rule): 语音通话 = VOICE_COMMUNICATION + platform fx;
        // 高清拾音 = VOICE_RECOGNITION (UNPROCESSED fallback) + our NLMS
        // chain only; 自动 picks by the current output device.
        val hd = effectiveMicHd()
        val sources = if (hd) intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        ) else intArrayOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        )
        // Device rate is FIXED at 48 kHz for the whole session (research
        // note §5.1.3): negotiated-rate changes are absorbed by the
        // software downsample factor in captureLoop, the AudioRecord is
        // never rebuilt mid-session (defect 2).
        for (source in sources) {
            for (rate in intArrayOf(48000, wanted)) {
                try {
                    val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    if (minBuf <= 0) continue
                    val rec = AudioRecord.Builder()
                        .setAudioSource(source)
                        .setAudioFormat(AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                        .setBufferSizeInBytes(maxOf(minBuf, Protocol.frameSamples(Protocol.rateCodeForHz(rate)) * 2 * 4))
                        .build()
                    if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); continue }
                    builtMicHd = hd && source != MediaRecorder.AudioSource.VOICE_COMMUNICATION
                    builtMicSource = source
                    attachEffects(rec.audioSessionId)
                    rec.startRecording()
                    recorder = rec
                    recorderRateHz = rate
                    echoProcessor.sampleRateHz = sampleRateHz
                    // Point the mic array at the user where supported (API 29+).
                    if (Build.VERSION.SDK_INT >= 29) {
                        try { rec.setPreferredMicrophoneDirection(AudioRecord.MIC_DIRECTION_TOWARDS_USER) } catch (_: Exception) {}
                    }
                    applyInputDevice() // main-mic / headset-mic preference
                    Log.i(TAG, "recorder built: source=$source rate=$rate hd=$builtMicHd routed=${try { rec.routedDevice?.productName } catch (_: Exception) { null }}")
                    return true
                } catch (e: Exception) {
                    Log.w(TAG, "recorder src=$source @${rate}Hz failed: ${e.message}")
                }
            }
        }
        return false
    }

    private fun attachEffects(sessionId: Int) {
        releaseEffects()
        if (builtMicHd) {
            // 高清拾音 mode: NO platform AEC/NS/AGC — the chain is our NLMS
            // only, for best fidelity (headset / quiet environments).
            fxAecActive = false; fxNsActive = false; fxAgcActive = false
            Log.i(TAG, "audiofx: skipped (HD mic mode, self chain only)")
            return
        }
        // Layer 1: platform AEC/NS/AGC on the VOICE_COMMUNICATION session.
        // isAvailable() differs per device — record what actually engaged so
        // the UI can honestly show 系统AEC已启用 vs 降级模式.
        fxAecActive = false; fxNsActive = false; fxAgcActive = false
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(sessionId)?.let { it.enabled = true; fx.add(it); fxAecActive = it.enabled }
            }
        } catch (_: Exception) {}
        try {
            // NS is selectable (defect 3): 轻柔 mode leaves it OFF — platform
            // NS is a prime suspect for eating quiet speech on several OEMs.
            if (nsMode == 0 && NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(sessionId)?.let { it.enabled = true; fx.add(it); fxNsActive = it.enabled }
            }
        } catch (_: Exception) {}
        try {
            // 高灵敏 mode turns platform AGC OFF: an OEM AGC with a noise
            // gate crushes soft speech, and it would fight the post-chain
            // digital gain in captureLoop (gain must sit AFTER the whole
            // echo chain anyway — research note §5.3 order).
            if (micSensitivity == 0 && AutomaticGainControl.isAvailable()) {
                AutomaticGainControl.create(sessionId)?.let { it.enabled = true; fx.add(it); fxAgcActive = it.enabled }
            }
        } catch (_: Exception) {}
        Log.i(TAG, "audiofx: AEC=$fxAecActive NS=$fxNsActive AGC=$fxAgcActive")
    }

    private fun releaseEffects() { for (e in fx) try { e.release() } catch (_: Exception) {}; fx.clear() }
    private fun releaseRecorder() { try { recorder?.stop() } catch (_: Exception) {}; try { recorder?.release() } catch (_: Exception) {}; recorder = null; releaseEffects() }

    private fun captureLoop() {
        val readBuf = ShortArray(480 * 3)
        while (running) {
            val targets = sessions.values.filter { it.connected && effectiveMicSend(it) }
            if (targets.isEmpty()) { Thread.sleep(15); continue }
            if (recorder == null) {
                if (!buildRecorder()) { Thread.sleep(300); continue }
                recorderBuiltRate = currentRateCode
                // Starting the record stream is one of the events that
                // makes OEM stacks reset the communication device
                // (research note §1.2): re-pin OUR route afterwards.
                applyOutputRoute()
            }
            val rec = recorder ?: continue
            val frameSamples = Protocol.frameSamples(currentRateCode)
            val factor = if (recorderRateHz > 0) recorderRateHz / sampleRateHz else 1
            val need = frameSamples * maxOf(1, factor)
            var filled = 0
            while (filled < need && running) {
                val n = try { rec.read(readBuf, filled, need - filled) } catch (_: Exception) { -1 }
                if (n <= 0) break
                filled += n
            }
            if (filled < need) continue
            val frame = ShortArray(frameSamples)
            if (factor <= 1) {
                System.arraycopy(readBuf, 0, frame, 0, frameSamples)
            } else {
                for (i in 0 until frameSamples) {
                    var sum = 0
                    for (k in 0 until factor) sum += readBuf[i * factor + k]
                    frame[i] = (sum / factor).toShort()
                }
            }
            // Echo chain (v2.1, all on the phone): platform AEC (layer 1,
            // attachEffects) -> NLMS subtraction on the playback reference
            // (layer 2) -> residual ducker (2b) -> howl guard (layer 3).
            // Runs on the raw frame before encoding; the mic meter below
            // therefore shows the level actually sent to the PCs. A capture
            // gap >400 ms invalidates the NLMS alignment — reset so it
            // re-locks instead of subtracting against a stale timeline.
            val procNowMs = System.currentTimeMillis()
            if (lastCaptureProcessMs > 0L && procNowMs - lastCaptureProcessMs > 400) echoProcessor.reset()
            lastCaptureProcessMs = procNowMs
            val er = echoProcessor.processCapture(frame, echoSuppressionEnabled, procNowMs)
            residualEcho = er.residualEcho
            echoDuckingActive = er.duckingActive
            howlGainDb = er.howlGainDb
            nlmsActive = er.nlmsActive
            nlmsErleDb = er.nlmsErleDb
            nlmsBulkDelay = er.nlmsBulkDelay
            doubleTalkActive = er.doubleTalk
            // Mic sensitivity （高灵敏）: digital gain AFTER the whole echo
            // chain (research note: no gain before AEC — it would break the
            // reference/capture linearity), paired with platform AGC off.
            if (micSensitivity == 1) {
                for (i in frame.indices) {
                    frame[i] = (frame[i] * 2.5).toInt().coerceIn(-32768, 32767).toShort()
                }
            }
            val payload = ByteArray(frameSamples * 2)
            var sumSq = 0.0
            for (i in frame.indices) {
                payload[i * 2] = (frame[i].toInt() and 0xFF).toByte()
                payload[i * 2 + 1] = ((frame[i].toInt() shr 8) and 0xFF).toByte()
                sumSq += frame[i].toDouble() * frame[i].toDouble()
            }
            val rms = kotlin.math.sqrt(sumSq / frameSamples) / 32768.0
            val lvl = (rms * 3.0).toFloat().coerceIn(0f, 1f)
            micLevel = maxOf(lvl, micLevel * 0.72f)
            // Focus pause (§3.6): while a real call holds audio focus the
            // capture chain keeps running locally, but NOTHING is sent —
            // call audio must never leak to the PCs. The loop-guard
            // history is likewise fed only with frames actually sent.
            if (!focusPaused) {
                // Loop-guard history: the exact samples about to be sent.
                loopGuard.pushSentMic(frame)
                // fan-out: same frame, per-session sequence numbers
                for (s in targets) {
                    val addr = s.addr ?: continue
                    s.txSeq++
                    txPackets++
                    sendTo(Protocol.TYPE_AUDIO, payload, addr, seq = s.txSeq)
                }
            }
        }
        releaseRecorder()
    }

    // ---------------- playout: mix ALL PCs -> phone speaker ----------------

    private fun buildTrack(): Boolean {
        releaseTrack()
        val wanted = sampleRateHz
        // Device rate FIXED at 48 kHz for the whole session (see
        // buildRecorder): the AudioTrack is never rebuilt mid-session.
        for (rate in intArrayOf(48000, wanted)) {
            try {
                val frameBytes = Protocol.frameSamples(Protocol.rateCodeForHz(rate)) * 2
                val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minBuf <= 0) continue
                val builder = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        // MEDIA usage (volume-stream fix): playback is the
                        // system media stream, so the hardware volume keys,
                        // the system panel and the in-app slider all drive
                        // STREAM_MUSIC — the old VOICE_COMMUNICATION usage
                        // pinned playback to the voice-call stream, where
                        // the keys never reached it. NOTE (design §5): the
                        // platform AEC's reference coupling may weaken on
                        // some OEMs now that playback left the voice stream;
                        // our NLMS reference tap is post-render and stream-
                        // independent, but layer-1 ERLE must be re-verified
                        // per device (real-device gate, not assumed).
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(maxOf(minBuf, frameBytes * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                if (Build.VERSION.SDK_INT >= 26) builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                val t = builder.build()
                if (t.state != AudioTrack.STATE_INITIALIZED) { t.release(); continue }
                t.play()
                track = t
                trackRateHz = rate
                playoutWatchdog.onTrackStarted(System.currentTimeMillis())
                return true
            } catch (e: Exception) {
                Log.w(TAG, "track @${rate}Hz failed: ${e.message}")
            }
        }
        return false
    }

    private fun releaseTrack() { try { track?.stop() } catch (_: Exception) {}; try { track?.release() } catch (_: Exception) {}; track = null }

    private fun playoutLoop() {
        var lastHeadSampleMs = 0L
        while (running) {
            val sources = sessions.values.filter { it.connected && effectiveSpeakerRecv(it) }
            if (sources.isEmpty()) { Thread.sleep(15); continue }
            if (track == null) {
                if (!buildTrack()) { Thread.sleep(300); continue }
                trackBuiltRate = currentRateCode
                applyOutputRoute() // stream creation can reset OEM routing: re-pin
            }
            val t = track ?: continue
            val frameSamples = Protocol.frameSamples(currentRateCode)
            // MIX INPUTS (defect 5a audit): the ONLY inputs to this mix are
            // network speaker frames popped from each session's jitter
            // buffer. Locally captured mic frames are NEVER an input — not
            // directly, and not via the echo reference (pushPlayback is a
            // read-only tap INTO the canceller; nothing reads it back into
            // the render path). Mic frames leave the phone only as network
            // packets in captureLoop.
            val mix = IntArray(frameSamples)
            for (s in sources) {
                val payload = popFrame(s) ?: continue
                val shorts = ShortArray(frameSamples)
                var i = 0
                while (i < frameSamples && i * 2 + 1 < payload.size) {
                    val v = (payload[i * 2].toInt() and 0xFF) or (payload[i * 2 + 1].toInt() shl 8)
                    shorts[i] = v.toShort()
                    i++
                }
                // Focus pause (§3.6): the frame was still popped — the
                // jitter buffer keeps draining so nothing stale piles up
                // during a call — but it is NOT mixed; the write path
                // below renders silence, keeping the track fed and the
                // NLMS reference timeline sample-continuous.
                if (focusPaused) continue
                // Loop-guard (defect 5c): if this frame is our OWN mic
                // voice returned by the PC (contaminated capture source),
                // drop it and surface the status note.
                if (loopGuard.checkIncoming(shorts)) {
                    loopNoteUntilMs = System.currentTimeMillis() + 6000
                    continue
                }
                val pd = pairedFor(s)
                val vol = if (pd?.muted == true) 0.0 else (pd?.speakerVolume ?: 0.7f).toDouble()
                for (j in 0 until frameSamples) mix[j] += (shorts[j] * vol).toInt()
            }
            // v2.1: a frame is written EVERY tick (silence when no source
            // had one) — the speaker pipeline never underruns, and the echo
            // reference timeline stays sample-continuous, which the NLMS
            // alignment depends on.
            // FINAL RENDER FRAME at track rate: mix + per-device volume +
            // clamp + upsample all applied. The echo reference below is
            // derived from THIS signal — the exact samples handed to
            // AudioTrack.write (research note §5.2.6: reference tap at the
            // final mixed render point, post everything).
            val factor = if (trackRateHz > 0) trackRateHz / sampleRateHz else 1
            val f = maxOf(1, factor)
            val finalShorts = ShortArray(frameSamples * f)
            var sumSq = 0.0
            for (i in 0 until frameSamples) {
                val clamped = mix[i].coerceIn(-32768, 32767).toShort()
                sumSq += clamped.toDouble() * clamped.toDouble()
                if (f <= 1) finalShorts[i] = clamped
                else for (k in 0 until f) finalShorts[i * f + k] = clamped
            }
            val refFrame = if (f <= 1) finalShorts
                else ShortArray(frameSamples) { finalShorts[it * f] } // ZOH decimation = exact mix
            echoProcessor.pushPlayback(refFrame)
            val rms = kotlin.math.sqrt(sumSq / frameSamples) / 32768.0
            val lvl = (rms * 3.0).toFloat().coerceIn(0f, 1f)
            speakerLevel = maxOf(lvl, speakerLevel * 0.72f)
            val out = ByteArray(finalShorts.size * 2)
            for (i in finalShorts.indices) {
                out[i * 2] = (finalShorts[i].toInt() and 0xFF).toByte()
                out[i * 2 + 1] = ((finalShorts[i].toInt() shr 8) and 0xFF).toByte()
            }
            // v2.1 defect: write()'s return value was DISCARDED and the
            // exception path just slept 5 ms and kept writing into the
            // same dead track forever — total silence, no trace. A
            // failed write now rebuilds the track, and the watchdog
            // below catches the sneakier case (writes "succeed" but
            // the play head never advances).
            val written = try { t.write(out, 0, out.size) } catch (_: Exception) { -1 }
            if (written < 0) {
                trackWriteErrors++
                Log.e(TAG, "AudioTrack.write -> $written; rebuilding track")
                notePlayoutFault(str(R.string.playout_fault_interrupted))
                releaseTrack()
                trackRebuilds++
                Thread.sleep(50)
                continue
            }
            playoutWatchdog.onWrite(finalShorts.size)
            // Health sampling ~4x/s: head liveness + diagnostics line.
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastHeadSampleMs >= 250) {
                lastHeadSampleMs = nowMs
                val head = try { t.playbackHeadPosition } catch (_: Exception) { -1 }
                if (head >= 0 && playoutWatchdog.onHeadSample(head, nowMs)) {
                    Log.e(TAG, "playback head stalled at $head while writing; rebuilding track")
                    notePlayoutFault(str(R.string.playout_fault_stalled))
                    releaseTrack()
                    trackRebuilds++
                    continue
                }
                val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                val vol = am?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) ?: -1
                val volMax = am?.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) ?: -1
                val stateName = when (try { t.playState } catch (_: Exception) { -1 }) {
                    AudioTrack.PLAYSTATE_PLAYING -> str(R.string.track_state_playing)
                    AudioTrack.PLAYSTATE_PAUSED -> str(R.string.state_paused)
                    AudioTrack.PLAYSTATE_STOPPED -> str(R.string.engine_status_stopped)
                    else -> str(R.string.track_state_unknown)
                }
                playoutDiagText = str(
                    R.string.playout_diag_format,
                    actualOutputDevice()?.name ?: "?", stateName, head,
                    trackWriteErrors, trackRebuilds, routeReapplyCount, vol, volMax
                )
            }
        }
        releaseTrack()
    }

    private fun releaseDevices() { releaseRecorder(); releaseTrack() }
}
