package com.qi.phoneaudiobridge

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.database.ContentObserver
import android.provider.Settings
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * 听说 LisTalk — multi-PC home (UI_SPEC.md + UI_SPEC_MULTIDEVICE.md):
 * overview hero, one card per PC (three states, per-device toggles,
 * auto-connect switch that stays live on offline cards), unpaired rows,
 * add-a-PC card, and the pairing overlays (QR scan / 6-digit code /
 * verification confirm) — all in this single Activity.
 */
class MainActivity : Activity() {

    /** Per-app language (board item #2): pre-33 wrap; on 33+ the
     * framework LocaleManager (see AppLocale.apply) governs. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    private val accent = Color.parseColor("#2CE8A5")
    private val accentDim = Color.parseColor("#12362A")
    private val sky = Color.parseColor("#45C7F2")
    private val skyDim = Color.parseColor("#12303F")
    private val warn = Color.parseColor("#FFB224")
    private val error = Color.parseColor("#FF5C5C")
    private val text1 = Color.parseColor("#F2F5F9")
    private val text2 = Color.parseColor("#9AA7B8")
    private val text3 = Color.parseColor("#5F6D80")
    private val surfaceIn = Color.parseColor("#0F141C")
    private val stroke = Color.parseColor("#26303F")
    private val onAccent = Color.parseColor("#052417")

    private lateinit var tvStatus: TextView
    private lateinit var viewStatusDot: View
    private lateinit var bannerReconnect: TextView
    private lateinit var bannerAuto: LinearLayout
    private lateinit var tvBannerAutoText: TextView
    private lateinit var tvHeroEyebrow: TextView
    private lateinit var tvHeroTitle: TextView
    private lateinit var tvHeroSub: TextView
    private lateinit var tvLatencyBadge: TextView
    private lateinit var meterBlock: LinearLayout
    private lateinit var meterMic: LinearLayout
    private lateinit var meterSpk: LinearLayout
    private lateinit var tvMicDb: TextView
    private lateinit var tvSpkDb: TextView
    private lateinit var chipRow: LinearLayout
    private lateinit var chipRate: TextView
    private lateinit var tvStats: TextView
    private lateinit var channelSection: LinearLayout
    private lateinit var deviceContainer: LinearLayout
    private lateinit var tvMicState: TextView
    private lateinit var tvSpkState: TextView
    private lateinit var tvLoopGuardNote: TextView
    private lateinit var bannerHeadset: View
    private lateinit var tvHeadsetText: TextView
    private var lastDeviceSignature = ""
    private lateinit var tvNoDevice: TextView
    private lateinit var tvElapsed: TextView
    private lateinit var btnStart: Button
    private lateinit var btnCameraRelay: Button

    // settings + discovery overlays (v2.1)
    private lateinit var overlaySettings: LinearLayout
    private lateinit var overlayDiscover: LinearLayout
    private lateinit var discoverContainer: LinearLayout
    private lateinit var tvDiscoverStatus: TextView
    private lateinit var tvDiscoverEmpty: TextView
    private lateinit var bannerReturnAsk: LinearLayout
    private lateinit var tvReturnAskText: TextView
    private var lastDiscoverSignature = ""
    private var usingService = false
    private var syncingEchoSwitches = false

    // pairing overlays
    private lateinit var overlayScan: FrameLayout
    private lateinit var overlayCode: LinearLayout
    private lateinit var overlayConfirm: LinearLayout
    private lateinit var tvScanHint: TextView
    private lateinit var etPairCode: EditText
    private lateinit var tvCodeError: TextView
    private lateinit var btnCodeSubmit: Button
    private var qrScanner: QrScanner? = null

    private val micSegs = ArrayList<View>()
    private val spkSegs = ArrayList<View>()
    private val handler = Handler(Looper.getMainLooper())

    // Playback-volume slider (volume-stream fix): bound to the system
    // MEDIA stream — the same index the hardware keys move. The observer
    // keeps the slider following key/panel changes (it previously bound
    // the voice-call stream and was initialized once, never refreshed).
    private var seekPlaybackVolume: SeekBar? = null
    private val volumeObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) { refreshPlaybackVolumeSeek() }
    }

    private fun refreshPlaybackVolumeSeek() {
        val seek = seekPlaybackVolume ?: return
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (seek.progress != cur) seek.progress = cur
    }
    private var polling = false
    private var connectedSinceMs = 0L
    private var searchStartMs = 0L
    private var wasRunning = false
    private var lastCardSignature = ""
    private var lastShownAutoEventAt = 0L
    private var bannerShownAtMs = 0L
    private var bannerIps: List<String> = emptyList()
    private var flashDeviceId: String? = null
    private var flashUntilMs = 0L

    private val shownVersionWarnings = HashSet<String>()

    // pairing flow state
    private var pairTarget: AudioEngine.DeviceView? = null
    private var awaitingIp: String? = null
    private var pairSubmittedAtMs = 0L
    private var confirmIp: String? = null
    private var confirmShown = false

    // ---- wave 3 interaction layer (plan Part A) ----
    // The single overlay stack behind all three back channels; the pure
    // rules live in InteractionPolicy (JVM-tested).
    private val overlayStack = InteractionPolicy.OverlayStack()
    private val overlayInterpolator = PathInterpolator(0.3f, 0.8f, 0.35f, 1f)
    // Registered (API 33+) only while an overlay is open — at home the
    // system default back behaviour applies (plan A.1.2). Held as Any
    // because the callback classes do not exist below API 33.
    private var backInvokedCallback: Any? = null
    private var backGestureView: View? = null

    // FB-12 density: ids the user manually expanded (exclusive — at most
    // one), the previous poll's connected set (for disconnect pruning),
    // the last rendered form per device (for transition fades), and a
    // pending height animation for a manual expand/collapse.
    private var manualExpandedIds: MutableSet<String> = mutableSetOf()
    private var prevConnectedIds: Set<String> = emptySet()
    private var lastForms = HashMap<String, InteractionPolicy.DeviceForm>()
    private var pendingFormAnim: Pair<String, Int>? = null
    private var listAnimUntilMs = 0L
    private var anyConnectedNow = false

    // Pull-to-refresh (A.1.4): pill view + in-flight bookkeeping. The
    // refresh completes when the nearby set changes and then settles,
    // or after a 3 s timeout — whichever comes first.
    private lateinit var homeScroll: ScrollView
    private lateinit var tvPullRefresh: TextView
    private var pullRefreshing = false
    private var pullRefreshStartMs = 0L
    private var pullRefreshSettledSinceMs = 0L

    // B-05 verification watch: after the user taps 确认配对, the pairing
    // only counts as succeeded once the session demonstrably stays up
    // for InteractionPolicy.VERIFY_OK_MS (see pollPairingProgress).
    private var confirmVerifyIp: String? = null
    private var confirmVerifyStartMs = 0L
    private var confirmVerifyFailed = false
    private var confirmVerifyName: String = ""
    private var confirmVerifyAuto: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AudioEngine.attachContext(applicationContext)
        setContentView(R.layout.activity_main)
        // Foreground fallback (design §3.3 scheme D): while this Activity
        // is up, the hardware volume keys target the media stream — the
        // stream playback actually rides — even on OEMs that would
        // otherwise pin the keys to the voice-call stream in comm mode.
        volumeControlStream = AudioManager.STREAM_MUSIC
        bindViews()
        buildMeter(meterMic, micSegs)
        buildMeter(meterSpk, spkSegs)
        wireMainButtons()
        wirePairingOverlays()
        wireSettingsUi()
        wireDiscoverUi()
        wireUpdateUi()
        wirePullToRefresh()
        checkUpdate(manual = false)
    }

    // ---------------- wave 3: overlay stack + back channels ----------------

    private fun overlayView(o: InteractionPolicy.Overlay): View = when (o) {
        InteractionPolicy.Overlay.SETTINGS -> overlaySettings
        InteractionPolicy.Overlay.DISCOVER -> overlayDiscover
        InteractionPolicy.Overlay.SCAN -> overlayScan
        InteractionPolicy.Overlay.CODE -> overlayCode
        InteractionPolicy.Overlay.CONFIRM -> overlayConfirm
    }

    /** Show one overlay — any other closes instantly (the stack holds
     *  exactly one, plan A.1.1) — with the standard 260 ms slide/fade-in. */
    private fun showOverlay(o: InteractionPolicy.Overlay) {
        overlayStack.show(o)
        for (kind in InteractionPolicy.Overlay.values()) {
            val v = overlayView(kind)
            v.animate().cancel()
            if (kind == o) {
                v.visibility = View.VISIBLE
                v.alpha = 0f
                v.translationX = dp(16).toFloat()
                v.animate().alpha(1f).translationX(0f)
                    .setDuration(260).setInterpolator(overlayInterpolator).start()
            } else {
                v.visibility = View.GONE
                v.alpha = 1f
                v.translationX = 0f
            }
        }
        syncBackCallbackRegistration()
    }

    /** Close every overlay immediately (success paths that already ran
     *  their own side effects, e.g. finishConfirmSuccess). */
    private fun hideAllOverlays() {
        overlayStack.clear()
        for (kind in InteractionPolicy.Overlay.values()) {
            val v = overlayView(kind)
            v.animate().cancel()
            v.visibility = View.GONE
            v.alpha = 1f
            v.translationX = 0f
        }
        syncBackCallbackRegistration()
    }

    /** Back on the top overlay: per-overlay side effects first, then the
     *  260 ms slide/fade-out (plan A.1.3 level 1 + A.1.5). */
    private fun closeTopOverlay() {
        val top = overlayStack.back() ?: return
        applyOverlayCloseSideEffects(top)
        val v = overlayView(top)
        v.animate().cancel()
        v.animate().alpha(0f).translationX(dp(16).toFloat())
            .setDuration(260).setInterpolator(overlayInterpolator)
            .withEndAction {
                // A reopen during the out-animation wins: only settle
                // into GONE if this view is not the current overlay.
                if (overlayStack.current?.let { overlayView(it) } != v) {
                    v.visibility = View.GONE
                    v.alpha = 1f
                    v.translationX = 0f
                }
            }
            .start()
        syncBackCallbackRegistration()
    }

    /** What "leaving this overlay via Back" means for each overlay.
     *  Correctness-critical (plan A.1.3 table): scan must power the
     *  camera down, confirm must not strand a pairing half-done, code
     *  must NOT cancel the in-flight search (it self-times-out). */
    private fun applyOverlayCloseSideEffects(top: InteractionPolicy.Overlay) {
        when (top) {
            InteractionPolicy.Overlay.SCAN -> stopScanner()
            InteractionPolicy.Overlay.CONFIRM -> {
                if (confirmVerifyIp != null) {
                    // Pairing already registered — backing out only
                    // stops watching it (B-05 watch), nothing to cancel.
                    confirmVerifyIp = null
                    confirmVerifyFailed = false
                } else {
                    confirmIp?.let { AudioEngine.cancelPairing(it) }
                }
                confirmShown = false
                awaitingIp = null
                pairSubmittedAtMs = 0L
            }
            InteractionPolicy.Overlay.CODE,
            InteractionPolicy.Overlay.SETTINGS,
            InteractionPolicy.Overlay.DISCOVER -> Unit // hide only
        }
    }

    private fun stopScanner() {
        qrScanner?.stop()
        qrScanner = null
    }

    /** The one back handler behind all three channels (plan A.1.3). */
    private fun handleBack() {
        if (overlayStack.current != null) {
            closeTopOverlay()
            if (Build.VERSION.SDK_INT >= 30) {
                findViewById<View>(android.R.id.content)
                    .performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            }
        } else {
            // Level 2: background the task — never finish(). The engine
            // stops only via 全部停止 / notification disconnect (see
            // the onDestroy note).
            moveTaskToBack(true)
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // API 26–32 channel. On API 33+ the dispatcher callbacks below
        // take over while an overlay is open; at home the system
        // default applies there and never reaches this override.
        handleBack()
    }

    /** Keep the API 33+ back callbacks registered exactly while an
     *  overlay is open (plan A.1.2): at home the system default —
     *  including its own predictive animation — stays in charge. */
    private fun syncBackCallbackRegistration() {
        if (Build.VERSION.SDK_INT < 33) return
        val want = overlayStack.current != null
        if (want == (backInvokedCallback != null)) return
        if (want) {
            val cb = if (Build.VERSION.SDK_INT >= 34) newBackAnimationCallback()
                     else newBackInvokedCallback()
            backInvokedCallback = cb
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                cb as android.window.OnBackInvokedCallback)
        } else {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(
                backInvokedCallback as android.window.OnBackInvokedCallback)
            backInvokedCallback = null
        }
    }

    private fun newBackInvokedCallback(): android.window.OnBackInvokedCallback =
        android.window.OnBackInvokedCallback { handleBack() }

    /** Predictive back (API 34, plan A.1.2): the open overlay follows
     *  the edge gesture (slides right ≤12% of its width, fades toward
     *  0.85); a cancelled gesture springs it back in 180 ms. No custom
     *  edge-arrow bubble is drawn — the system provides it (A.4). */
    private fun newBackAnimationCallback(): android.window.OnBackAnimationCallback =
        object : android.window.OnBackAnimationCallback {
            override fun onBackStarted(event: android.window.BackEvent) {
                backGestureView = overlayStack.current?.let { overlayView(it) }
            }

            override fun onBackProgressed(event: android.window.BackEvent) {
                val v = backGestureView ?: return
                if (event.progress < 0.02f) return // ignore touch-down noise
                v.translationX = event.progress * v.width * 0.12f
                v.alpha = 1f - 0.15f * event.progress
            }

            override fun onBackCancelled() {
                val v = backGestureView ?: return
                backGestureView = null
                v.animate().translationX(0f).alpha(1f)
                    .setDuration(180).setInterpolator(overlayInterpolator).start()
            }

            override fun onBackInvoked() {
                backGestureView = null
                handleBack()
            }
        }

    // ---------------- wave 3: pull-to-refresh (plan A.1.4) ----------------

    /** Observing touch listener on the home ScrollView — returns false
     *  for every event so normal scrolling/clicks are never stolen; it
     *  only watches for a downward pull that starts at scrollY == 0. */
    private fun wirePullToRefresh() {
        var downY = 0f
        var pulling = false
        var crossed = false
        homeScroll.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = ev.y
                    pulling = false
                    crossed = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!pulling && homeScroll.scrollY == 0 && ev.y - downY > dp(8) &&
                        InteractionPolicy.pullRefreshAllowed(
                            AudioEngine.running, anyConnectedNow,
                            overlayStack.current != null, pullRefreshing)) {
                        pulling = true
                    }
                    if (pulling) {
                        val dy = ev.y - downY
                        tvPullRefresh.visibility = View.VISIBLE
                        tvPullRefresh.translationY =
                            (dy * 0.5f).coerceAtMost(dp(72).toFloat())
                        val over = dy >= dp(InteractionPolicy.PULL_THRESHOLD_DP)
                        tvPullRefresh.text = getString(
                            if (over) R.string.pull_refresh_release
                            else R.string.pull_refresh_pull)
                        if (over && !crossed) {
                            crossed = true
                            tvPullRefresh.performHapticFeedback(
                                HapticFeedbackConstants.VIRTUAL_KEY)
                        }
                        if (!over) crossed = false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (pulling) {
                        pulling = false
                        if (ev.y - downY >= dp(InteractionPolicy.PULL_THRESHOLD_DP)) {
                            startPullRefresh()
                        } else {
                            hidePullPill()
                        }
                    }
                }
            }
            false
        }
    }

    private fun startPullRefresh() {
        pullRefreshing = true
        pullRefreshStartMs = System.currentTimeMillis()
        pullRefreshSettledSinceMs = 0L
        tvPullRefresh.visibility = View.VISIBLE
        tvPullRefresh.text = getString(R.string.pull_refresh_refreshing)
        // Discovery restart ONLY — sessions/sockets are never touched
        // (AudioEngine.refreshDiscovery contract, plan A.1.4).
        AudioEngine.refreshDiscovery()
        searchStartMs = pullRefreshStartMs // restart the empty-state timer
    }

    /** Completion, driven from refreshUi's signature check: the nearby
     *  set changed and then settled for 600 ms, or 3 s elapsed —
     *  whichever comes first (plan A.1.4). */
    private fun pollPullRefresh(now: Long, cardsChanged: Boolean) {
        if (!pullRefreshing) return
        if (cardsChanged) {
            pullRefreshSettledSinceMs = 0L
        } else if (pullRefreshSettledSinceMs == 0L) {
            pullRefreshSettledSinceMs = now
        }
        val settled = pullRefreshSettledSinceMs != 0L &&
            now - pullRefreshSettledSinceMs > 600 &&
            now - pullRefreshStartMs > 600
        if (settled || now - pullRefreshStartMs > 3000) {
            pullRefreshing = false
            hidePullPill()
            Toast.makeText(this, R.string.list_updated_toast, Toast.LENGTH_SHORT).show()
        }
    }

    private fun hidePullPill() {
        tvPullRefresh.visibility = View.GONE
        tvPullRefresh.translationY = 0f
    }

    // ---------------- online update (v2) ----------------

    private fun updatePrefs() = getSharedPreferences("airbridge_settings", Context.MODE_PRIVATE)

    // FB-10/FB-7: the update address is no longer user-editable — checks
    // always use UpdateChecker.DEFAULT_UPDATE_MANIFEST_URL. The old
    // persisted "update_url" value (an old version pre-filled the field
    // and saved it back on every check, self-perpetuating the stale
    // Muse-hosted default) is wiped once here and never read again.
    private fun wipeStaleUpdateUrl() {
        if (updatePrefs().contains("update_url")) {
            updatePrefs().edit().remove("update_url").apply()
        }
    }

    private fun wireUpdateUi() {
        // Inject localized templates into the (resource-free) update
        // checker: it must also run on the JVM harness, so it cannot read
        // R.string itself. Locale comes from values / values-en.
        UpdateChecker.strings = UpdateChecker.Strings(
            summaryFailed = { getString(R.string.update_summary_failed, it) },
            summaryNone = getString(R.string.update_summary_none),
            errorHttp = { getString(R.string.update_error_http, it) },
            errorInvalidManifest = getString(R.string.update_manifest_invalid),
            errorNetwork = { getString(R.string.update_error_network, it) }
        )
        wipeStaleUpdateUrl()
        findViewById<TextView>(R.id.tvUpdateInfo).text = getString(R.string.update_current_version, UpdateChecker.APP_VERSION)
        findViewById<Button>(R.id.btnCheckUpdate).setOnClickListener {
            checkUpdate(manual = true)
        }
    }

    private fun checkUpdate(manual: Boolean) {
        val url = UpdateChecker.DEFAULT_UPDATE_MANIFEST_URL
        val tvInfo = findViewById<TextView>(R.id.tvUpdateInfo)
        // v2.2 defect B: a click must be VISIBLE immediately — never a
        // silent no-op. The card then lands on exactly one outcome:
        // 已是最新版本 / 发现新版本 / 检查失败：<plain reason>, with the
        // fetch diagnostics (URL + HTTP status + manifest versions)
        // kept as the card's second line.
        if (manual) tvInfo.text = getString(R.string.update_checking, UpdateChecker.APP_VERSION)
        Thread {
            val report = UpdateChecker.fetchManifestReport(url)
            if (report.partnerVersion != null) {
                updatePrefs().edit().putString("partner_latest_version", report.partnerVersion).apply()
            }
            runOnUiThread {
                val info = report.info
                when {
                    report.error != null || info == null -> {
                        val reason = report.error ?: getString(R.string.update_manifest_invalid)
                        tvInfo.text = getString(R.string.update_check_failed_card, reason) + "\n" + report.summary
                        if (manual) Toast.makeText(this, getString(R.string.update_check_failed_toast, reason), Toast.LENGTH_LONG).show()
                    }
                    UpdateChecker.isNewer(info.version) -> {
                        tvInfo.text = getString(R.string.update_found_card, info.version, UpdateChecker.APP_VERSION) + "\n" + report.summary
                        AlertDialog.Builder(this)
                            .setTitle(getString(R.string.update_found_title, info.version))
                            .setMessage(info.notes.ifEmpty { getString(R.string.update_found_default_notes) })
                            .setPositiveButton(getString(R.string.update_download_install)) { _, _ -> downloadUpdate(info) }
                            .setNegativeButton(getString(R.string.action_later), null)
                            .show()
                    }
                    else -> {
                        tvInfo.text = getString(R.string.update_uptodate_card, UpdateChecker.APP_VERSION, info.version) +
                            (report.partnerVersion?.let { getString(R.string.update_uptodate_partner_suffix, it) } ?: "") +
                            "\n" + report.summary
                        if (manual) Toast.makeText(this, getString(R.string.update_uptodate_toast, UpdateChecker.APP_VERSION), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.start()
    }

    private fun downloadUpdate(info: UpdateChecker.UpdateInfo) {
        val tvInfo = findViewById<TextView>(R.id.tvUpdateInfo)
        tvInfo.text = getString(R.string.update_downloading_card, info.version)
        Toast.makeText(this, getString(R.string.update_downloading_toast), Toast.LENGTH_SHORT).show()
        Thread {
            val dest = File(cacheDir, "airbridge-update.apk")
            val okDownload = UpdateChecker.downloadTo(info.url, dest)
            // Tamper gate: SHA-256 must match the manifest or we abort.
            val okVerify = okDownload && UpdateChecker.verify(dest, info.sha256)
            runOnUiThread {
                when {
                    !okDownload -> {
                        tvInfo.text = getString(R.string.update_download_failed_card, info.version)
                        Toast.makeText(this, getString(R.string.update_download_failed_toast), Toast.LENGTH_LONG).show()
                    }
                    !okVerify -> {
                        dest.delete()
                        tvInfo.text = getString(R.string.update_verify_failed)
                        Toast.makeText(this, getString(R.string.update_verify_failed), Toast.LENGTH_LONG).show()
                    }
                    else -> {
                        tvInfo.text = getString(R.string.update_downloaded_card, info.version)
                        installDownloaded(dest)
                    }
                }
            }
        }.start()
    }

    private fun installDownloaded(apk: File) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.install_perm_title))
                .setMessage(getString(R.string.install_perm_msg))
                .setPositiveButton(getString(R.string.action_go_settings)) { _, _ ->
                    try {
                        startActivity(
                            Intent(
                                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                android.net.Uri.parse("package:$packageName")
                            )
                        )
                    } catch (_: Exception) {}
                }
                .setNegativeButton(getString(R.string.action_cancel), null)
                .show()
            return
        }
        try {
            startActivity(UpdateInstaller.installIntent(this, apk))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.installer_failed_toast, e.message.toString()), Toast.LENGTH_LONG).show()
        }
    }

    private fun bindViews() {
        homeScroll = findViewById(R.id.homeScroll)
        tvPullRefresh = findViewById(R.id.tvPullRefresh)
        tvStatus = findViewById(R.id.tvStatus)
        viewStatusDot = findViewById(R.id.viewStatusDot)
        bannerReconnect = findViewById(R.id.bannerReconnect)
        bannerAuto = findViewById(R.id.bannerAuto)
        tvBannerAutoText = findViewById(R.id.tvBannerAutoText)
        tvHeroEyebrow = findViewById(R.id.tvHeroEyebrow)
        tvHeroTitle = findViewById(R.id.tvHeroTitle)
        tvHeroSub = findViewById(R.id.tvHeroSub)
        tvLatencyBadge = findViewById(R.id.tvLatencyBadge)
        meterBlock = findViewById(R.id.meterBlock)
        meterMic = findViewById(R.id.meterMic)
        meterSpk = findViewById(R.id.meterSpk)
        tvMicDb = findViewById(R.id.tvMicDb)
        tvSpkDb = findViewById(R.id.tvSpkDb)
        chipRow = findViewById(R.id.chipRow)
        chipRate = findViewById(R.id.chipRate)
        tvStats = findViewById(R.id.tvStats)
        channelSection = findViewById(R.id.channelSection)
        deviceContainer = findViewById(R.id.deviceContainer)
        tvMicState = findViewById(R.id.tvMicState)
        tvSpkState = findViewById(R.id.tvSpkState)
        tvLoopGuardNote = findViewById(R.id.tvLoopGuardNote)
        bannerHeadset = findViewById(R.id.bannerHeadset)
        tvHeadsetText = findViewById(R.id.tvHeadsetText)
        tvNoDevice = findViewById(R.id.tvNoDevice)
        tvElapsed = findViewById(R.id.tvElapsed)
        btnStart = findViewById(R.id.btnStart)
        btnCameraRelay = findViewById(R.id.btnCameraRelay)
        overlaySettings = findViewById(R.id.overlaySettings)
        overlayDiscover = findViewById(R.id.overlayDiscover)
        discoverContainer = findViewById(R.id.discoverContainer)
        tvDiscoverStatus = findViewById(R.id.tvDiscoverStatus)
        tvDiscoverEmpty = findViewById(R.id.tvDiscoverEmpty)
        bannerReturnAsk = findViewById(R.id.bannerReturnAsk)
        tvReturnAskText = findViewById(R.id.tvReturnAskText)
        overlayScan = findViewById(R.id.overlayScan)
        overlayCode = findViewById(R.id.overlayCode)
        overlayConfirm = findViewById(R.id.overlayConfirm)
        tvScanHint = findViewById(R.id.tvScanHint)
        etPairCode = findViewById(R.id.etPairCode)
        tvCodeError = findViewById(R.id.tvCodeError)
        btnCodeSubmit = findViewById(R.id.btnCodeSubmit)
    }

    private fun wireMainButtons() {
        btnStart.setOnClickListener {
            if (!AudioEngine.running) {
                if (!ensureAudioPermissions()) return@setOnClickListener
                AudioEngine.userPaused = false
                startBridge()
            } else if (AudioEngine.userPaused) {
                AudioEngine.resumeAll()
                // B-04: the foreground-service notification captured its
                // "paused" state when it was built and never re-read it,
                // so after a restore it kept offering 恢复 while already
                // running. Rebuild it now (ACTION_REFRESH_FG re-posts
                // without restarting the engine).
                if (BridgeService.isRunning) {
                    startService(Intent(this, BridgeService::class.java)
                        .setAction(BridgeService.ACTION_REFRESH_FG))
                }
            } else if (AudioEngine.connectedCount >= 2) {
                stopService(Intent(this, BridgeService::class.java))
                AudioEngine.stop()
            }
        }
        // Camera relay (wave 4 stage 1): a single toggle hook — the
        // full camera UI is wave 3. Needs the CAMERA permission first
        // (same permission the QR scanner uses, its own request code so
        // the grant lands here, not in the scanner).
        btnCameraRelay.setOnClickListener {
            if (!AudioEngine.cameraRelayActive &&
                checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA_RELAY)
            } else {
                AudioEngine.toggleCameraRelay()
            }
            refreshUi()
        }
        // v2.1 pairing hierarchy: nearby discovery is THE entry; scan/code/IP are 其他方式
        findViewById<Button>(R.id.btnAddNearby).setOnClickListener { openDiscover() }
        findViewById<TextView>(R.id.btnAddScan).setOnClickListener { openScan(null) }
        findViewById<TextView>(R.id.btnAddCode).setOnClickListener { openCode(null) }
        findViewById<TextView>(R.id.btnAddManual).setOnClickListener { showManualIpDialog() }
        findViewById<TextView>(R.id.btnOpenSettings).setOnClickListener { openSettings() }
        findViewById<TextView>(R.id.btnBannerUndo).setOnClickListener {
            for (ip in bannerIps) AudioEngine.disconnectDevice(ip)
            bannerAuto.visibility = View.GONE
        }
        findViewById<TextView>(R.id.btnReturnAskConnect).setOnClickListener {
            AudioEngine.acceptReturnAsk()
            AudioEngine.clearReturnAskNotification()
            bannerReturnAsk.visibility = View.GONE
        }
        findViewById<TextView>(R.id.btnReturnAskIgnore).setOnClickListener {
            AudioEngine.dismissReturnAsk()
            AudioEngine.clearReturnAskNotification()
            bannerReturnAsk.visibility = View.GONE
        }

        val swMic = findViewById<Switch>(R.id.cbMic)
        val swSpk = findViewById<Switch>(R.id.cbSpeaker)
        tintSwitch(swMic, accent, accentDim)
        tintSwitch(swSpk, sky, skyDim)
        swMic.setOnCheckedChangeListener { _, checked -> AudioEngine.micEnabled = checked }
        swSpk.setOnCheckedChangeListener { _, checked -> AudioEngine.speakerEnabled = checked }

        // Playback rides the MEDIA stream (USAGE_MEDIA): this slider is
        // the same STREAM_MUSIC index the hardware keys and the system
        // panel move — one master volume, several handles (§3.4). The
        // observer + onResume refresh keep it following key changes.
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val seek = findViewById<SeekBar>(R.id.seekVolume)
        seekPlaybackVolume = seek
        seek.max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        seek.progress = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) am.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)

        val swEcho = findViewById<Switch>(R.id.swEcho)
        tintSwitch(swEcho, accent, accentDim)
        swEcho.isChecked = AppSettings.prefs(this).getBoolean(AppSettings.KEY_ECHO, true)
        swEcho.setOnCheckedChangeListener { _, checked -> setEchoEnabled(checked) }
    }

    /** Echo toggle lives in two places (channel card + Settings): one writer. */
    private fun setEchoEnabled(checked: Boolean) {
        AppSettings.prefs(this).edit().putBoolean(AppSettings.KEY_ECHO, checked).apply()
        AudioEngine.echoSuppressionEnabled = checked
        if (syncingEchoSwitches) return
        syncingEchoSwitches = true
        try {
            findViewById<Switch>(R.id.swEcho).isChecked = checked
            findViewById<Switch>(R.id.swEchoSettings).isChecked = checked
        } finally { syncingEchoSwitches = false }
    }

    /** Start honoring the 后台运行 setting: service mode (default) keeps the
     *  bridge alive in background; off = engine bound to this Activity. */
    private fun startBridge() {
        if (AppSettings.bgServiceEnabled(this)) {
            usingService = true
            startBridgeService()
        } else {
            usingService = false
            AudioEngine.start(applicationContext)
        }
    }

    private fun startBridgeService() {
        val intent = Intent(this, BridgeService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    /** Manual IP = last-resort fallback under 「其他方式」 (v2.1 hierarchy). */
    private fun showManualIpDialog() {
        val et = EditText(this)
        et.hint = getString(R.string.manual_ip_hint)
        et.inputType = android.text.InputType.TYPE_CLASS_TEXT
        et.setTextColor(text1)
        et.setHintTextColor(text3)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.manual_ip_title))
            .setMessage(getString(R.string.manual_ip_msg))
            .setView(et)
            .setPositiveButton(getString(R.string.action_connect)) { _, _ ->
                val ip = et.text.toString().trim()
                if (ip.isEmpty()) {
                    Toast.makeText(this, getString(R.string.manual_ip_empty_toast), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (!AudioEngine.running) {
                    Toast.makeText(this, getString(R.string.manual_ip_need_search_toast), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                AudioEngine.connectManual(ip)
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    private fun tintSwitch(sw: Switch, thumb: Int, track: Int) {
        sw.thumbTintList = ColorStateList.valueOf(thumb)
        sw.trackTintList = ColorStateList.valueOf(track)
    }

    // ---------------- meters ----------------

    private fun buildMeter(container: LinearLayout, out: ArrayList<View>) {
        val d = resources.displayMetrics.density
        val w = (4 * d).toInt(); val h = (22 * d).toInt(); val gap = (3 * d).toInt()
        for (i in 0 until 24) {
            val v = View(this)
            val lp = LinearLayout.LayoutParams(w, h)
            if (i > 0) lp.marginStart = gap
            v.layoutParams = lp
            v.setBackgroundResource(R.drawable.bg_meter_seg)
            v.backgroundTintList = ColorStateList.valueOf(surfaceIn)
            container.addView(v)
            out.add(v)
        }
    }

    private fun paintMeter(segs: List<View>, level: Float, channelColor: Int, enabled: Boolean) {
        val lit = if (enabled) (level * segs.size).roundToInt().coerceIn(0, segs.size) else 0
        for (i in segs.indices) {
            val color = when {
                i >= lit || !enabled -> surfaceIn
                i >= 22 -> error
                i >= 17 -> warn
                else -> channelColor
            }
            segs[i].backgroundTintList = ColorStateList.valueOf(color)
        }
    }

    private fun levelDb(level: Float): String {
        val rms = level / 3.0
        if (rms < 0.0005) return "-∞ dB"
        return "${(20 * log10(rms)).roundToInt()} dB"
    }

    // ---------------- permissions ----------------

    private fun ensureAudioPermissions(): Boolean {
        val needed = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), REQ_AUDIO)
            Toast.makeText(this, getString(R.string.mic_perm_toast), Toast.LENGTH_LONG).show()
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (overlayScan.visibility == View.VISIBLE) startScanner()
        } else if (requestCode == REQ_CAMERA) {
            tvScanHint.text = getString(R.string.scan_need_camera_perm_short)
            tvScanHint.setTextColor(error)
        } else if (requestCode == REQ_CAMERA_RELAY) {
            // camera-relay toggle was waiting on this permission
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                AudioEngine.toggleCameraRelay()
            }
            refreshUi()
        } else if (requestCode == REQ_BLE) {
            // nearby-devices permission decided: (re)start BLE scan either way
            AudioEngine.restartDiscovery()
        }
    }

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(volumeObserver)
        stopScanner()
        // Wave 3 (plan A.1.3 consequence, audit B-02): destroying the
        // Activity no longer stops the engine — Back at home backgrounds
        // the task instead of finishing, and a system-driven destroy
        // must not silently kill the bridge either. The engine stops
        // only on an explicit stop: 全部停止 (btnStart), the
        // notification's disconnect action, or Settings' stop. In
        // Activity-bound mode the engine object simply outlives the UI
        // in the same process and the next launch reattaches to it.
        super.onDestroy()
    }

    // ---------------- lifecycle / polling ----------------

    override fun onResume() {
        super.onResume()
        AudioEngine.uiInForeground = true
        refreshPlaybackVolumeSeek() // keys may have moved it while we were away
        polling = true
        handler.post(pollRunnable)
    }

    override fun onPause() {
        polling = false
        handler.removeCallbacks(pollRunnable)
        AudioEngine.uiInForeground = false
        super.onPause()
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!polling) return
            refreshUi()
            handler.postDelayed(this, 100)
        }
    }

    // ---------------- main refresh ----------------

    private fun refreshUi() {
        val e = AudioEngine
        btnCameraRelay.text = getString(
            if (e.cameraRelayActive) R.string.action_camera_relay_stop else R.string.action_camera_relay_start)
        val now = System.currentTimeMillis()
        if (e.running && !wasRunning) searchStartMs = now
        wasRunning = e.running
        val views = e.deviceViews()
        val conn = views.filter { it.connected }
        if (conn.isNotEmpty() && connectedSinceMs == 0L) connectedSinceMs = now
        if (conn.isEmpty()) connectedSinceMs = 0L

        // header overview (§1: ● N 台推流中 / ● N 台在线 / ○ 暂无在线)
        val onlineCount = views.count { it.online }
        when {
            conn.isNotEmpty() -> { tvStatus.text = getString(R.string.header_streaming, conn.size); tvStatus.setTextColor(accent); viewStatusDot.backgroundTintList = ColorStateList.valueOf(accent) }
            onlineCount > 0 -> { tvStatus.text = getString(R.string.header_online, onlineCount); tvStatus.setTextColor(text2); viewStatusDot.backgroundTintList = ColorStateList.valueOf(accent) }
            else -> { tvStatus.text = getString(R.string.header_none_online); tvStatus.setTextColor(text3); viewStatusDot.backgroundTintList = ColorStateList.valueOf(text3) }
        }

        // unified versioning: NEVER fail silently on a version mismatch
        for (v in views) {
            if (v.versionMismatch && v.pcAppVersion.isNotEmpty()) {
                val key = "${v.deviceId ?: v.ip}@${v.pcAppVersion}"
                if (shownVersionWarnings.add(key)) {
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.version_mismatch_title))
                        .setMessage(getString(R.string.version_mismatch_msg, v.pcAppVersion, UpdateChecker.APP_VERSION))
                        .setPositiveButton(getString(R.string.action_check_update_now)) { _, _ -> checkUpdate(manual = true) }
                        .setNegativeButton(getString(R.string.action_got_it), null)
                        .show()
                }
            }
        }

        // reconnect banner (legacy single-purpose) — kept for drop events
        val reconnecting = e.running && conn.isEmpty() && e.reconnectCount > 0 && views.any { it.online }
        bannerReconnect.visibility = if (reconnecting) View.VISIBLE else View.GONE
        if (reconnecting) bannerReconnect.text = getString(R.string.reconnect_banner, e.reconnectCount)

        // auto-connect / pairing banner (§5): show each engine event once, 3 s
        if (e.lastAutoEventAtMs > lastShownAutoEventAt && e.lastAutoEventAtMs > 0) {
            lastShownAutoEventAt = e.lastAutoEventAtMs
            bannerIps = e.lastAutoEventIps
            tvBannerAutoText.text = "✓ ${e.lastAutoEventText}"
            bannerAuto.visibility = View.VISIBLE
            bannerShownAtMs = now
        }
        if (bannerAuto.visibility == View.VISIBLE && now - bannerShownAtMs > 3000) {
            bannerAuto.visibility = View.GONE
        }

        // hero / overview
        when {
            conn.isNotEmpty() -> {
                tvHeroEyebrow.text = getString(R.string.hero_live_eyebrow)
                tvHeroEyebrow.setTextColor(accent)
                tvHeroTitle.text = getString(R.string.hero_connected_title, conn.size)
                tvHeroSub.text = conn.joinToString(getString(R.string.list_separator)) { it.name }
            }
            e.userPaused -> {
                tvHeroEyebrow.text = getString(R.string.state_paused)
                tvHeroEyebrow.setTextColor(warn)
                tvHeroTitle.text = getString(R.string.hero_paused_title)
                tvHeroSub.text = getString(R.string.hero_paused_sub)
            }
            e.running -> {
                tvHeroEyebrow.text = "SEARCHING"
                tvHeroEyebrow.setTextColor(warn)
                tvHeroTitle.text = if (onlineCount > 0) getString(R.string.hero_online_not_streaming, onlineCount) else getString(R.string.status_searching_pc)
                tvHeroSub.text = getString(R.string.hero_auto_hint)
            }
            else -> {
                tvHeroEyebrow.text = "LISTALK"
                tvHeroEyebrow.setTextColor(text3)
                tvHeroTitle.text = getString(R.string.hero_not_started)
                tvHeroSub.text = getString(R.string.hero_not_started_sub)
            }
        }

        // latency badge = worst connected RTT (§1)
        val rtt = e.worstRttMs
        when {
            conn.isEmpty() -> { tvLatencyBadge.text = "-- ms"; tvLatencyBadge.setBackgroundResource(R.drawable.bg_badge_idle); tvLatencyBadge.setTextColor(text2) }
            rtt < 0 -> { tvLatencyBadge.text = getString(R.string.latency_measuring); tvLatencyBadge.setBackgroundResource(R.drawable.bg_badge_idle); tvLatencyBadge.setTextColor(text2) }
            rtt < 30 -> { tvLatencyBadge.text = getString(R.string.latency_good, rtt); tvLatencyBadge.setBackgroundResource(R.drawable.bg_badge_good); tvLatencyBadge.setTextColor(accent) }
            rtt <= 80 -> { tvLatencyBadge.text = getString(R.string.latency_fair, rtt); tvLatencyBadge.setBackgroundResource(R.drawable.bg_badge_warn); tvLatencyBadge.setTextColor(warn) }
            else -> { tvLatencyBadge.text = getString(R.string.latency_poor, rtt); tvLatencyBadge.setBackgroundResource(R.drawable.bg_badge_bad); tvLatencyBadge.setTextColor(error) }
        }

        val connVis = if (conn.isNotEmpty()) View.VISIBLE else View.GONE
        meterBlock.visibility = connVis
        chipRow.visibility = connVis
        tvStats.visibility = connVis
        channelSection.visibility = connVis
        if (conn.isNotEmpty()) {
            paintMeter(micSegs, e.micLevel, accent, e.micEnabled)
            paintMeter(spkSegs, e.speakerLevel, sky, e.speakerEnabled)
            tvMicDb.text = levelDb(e.micLevel)
            tvSpkDb.text = levelDb(e.speakerLevel)
            if (e.currentRateCode == Protocol.RATE_48K) {
                chipRate.text = "48 kHz"; chipRate.setTextColor(text1)
            } else {
                chipRate.text = getString(R.string.chip_rate_optimized, e.sampleRateHz / 1000); chipRate.setTextColor(warn)
            }
            tvStats.text = getString(
                R.string.stats_format,
                conn.count { it.micSend }, conn.count { it.speakerRecv },
                e.worstLossPct, e.worstJitterMs, e.displayBufferMs, e.rxPackets
            )
            // mic state (board item #3): a call-paused uplink and a
            // watchdog-rebuilt capture are named, not silent.
            tvMicState.text = when {
                !e.micEnabled -> getString(R.string.state_paused)
                e.uplinkCallPaused -> getString(R.string.mic_state_call_paused)
                e.captureFaultActive -> "⚠ ${e.captureFaultText ?: getString(R.string.capture_fault_rebuilt)}"
                else -> getString(R.string.mic_state_sending_rate, e.sampleRateHz / 1000)
            }
            tvMicState.setTextColor(when {
                !e.micEnabled -> text3
                e.uplinkCallPaused -> warn
                e.captureFaultActive -> error
                else -> accent
            })
            // echo suppression status: REAL active layers (v2.1 — NLMS wired live)
            val tvEcho = findViewById<TextView>(R.id.tvEchoStatus)
            val echoText = echoStatusText()
            tvEcho.text = echoText
            tvEcho.setTextColor(when {
                !e.echoSuppressionEnabled -> text3
                e.nlmsActive || e.fxAecActive -> accent
                else -> text2
            })
            if (overlaySettings.visibility == View.VISIBLE) {
                findViewById<TextView>(R.id.tvEchoLayers).text = echoText
                refreshSettingsStates() // keep the playout diagnostics line live
            }
            tvSpkState.text = when {
                !e.speakerEnabled -> getString(R.string.state_paused)
                e.downlinkFocusPaused -> getString(R.string.speaker_state_focus_paused)
                e.playoutFaultActive -> "⚠ ${e.playoutFaultText ?: getString(R.string.playout_fault_default)}"
                e.downlinkDucked -> getString(R.string.speaker_state_ducked)
                else -> getString(R.string.speaker_state_playing_mix)
            }
            tvSpkState.setTextColor(when {
                !e.speakerEnabled -> text3
                e.downlinkFocusPaused -> warn
                e.playoutFaultActive -> error
                e.downlinkDucked -> text2
                else -> sky
            })
            // loop-guard note (defect 5c): visible for a few seconds after a block
            tvLoopGuardNote.visibility = if (e.loopGuardNoteActive) View.VISIBLE else View.GONE
            val elapsed = (now - connectedSinceMs) / 1000
            tvElapsed.text = getString(R.string.elapsed_streaming, elapsed / 60, elapsed % 60)
        } else {
            tvElapsed.text = getString(R.string.elapsed_auto_hint)
        }

        // re-entry prompt banner (v2.1, mode=每次询问): engine holds the ask
        val ask = e.pendingReturnAsk
        if (ask != null) {
            tvReturnAskText.text = getString(R.string.return_ask_text, ask.name)
            bannerReturnAsk.visibility = View.VISIBLE
        } else if (bannerReturnAsk.visibility == View.VISIBLE) {
            bannerReturnAsk.visibility = View.GONE
        }

        // headset banner (headset inheritance): a headset appeared mid-session
        val hsName = e.pendingHeadsetName
        if (hsName != null) {
            tvHeadsetText.text = if (e.outputDeviceSel == "auto") getString(R.string.headset_detected_auto, hsName)
                else getString(R.string.headset_detected, hsName)
            bannerHeadset.visibility = View.VISIBLE
        } else if (bannerHeadset.visibility == View.VISIBLE) {
            bannerHeadset.visibility = View.GONE
        }

        // settings open: keep the device pickers + actual-route lines live
        if (overlaySettings.visibility == View.VISIBLE) {
            val sig = (e.commDevices().joinToString("|") { "${it.type}:${it.name}" }) +
                "#" + e.outputRouteText + "#" + e.actualInputText()
            if (sig != lastDeviceSignature) {
                lastDeviceSignature = sig
                buildDevicePickers()
                refreshSettingsStates()
            }
        }

        // primary button: start-service entry, or 全部停止 when >=2 streaming (§1)
        when {
            !e.running -> {
                btnStart.visibility = View.VISIBLE
                btnStart.text = getString(R.string.action_start_search)
                btnStart.setBackgroundResource(R.drawable.btn_primary)
                btnStart.setTextColor(onAccent)
            }
            e.userPaused -> {
                btnStart.visibility = View.VISIBLE
                btnStart.text = getString(R.string.btn_continue_connect)
                btnStart.setBackgroundResource(R.drawable.btn_primary)
                btnStart.setTextColor(onAccent)
            }
            conn.size >= 2 -> {
                btnStart.visibility = View.VISIBLE
                btnStart.text = getString(R.string.btn_stop_all)
                btnStart.setBackgroundResource(R.drawable.btn_stop)
                btnStart.setTextColor(error)
            }
            else -> btnStart.visibility = View.GONE
        }

        // FB-12: a device that just disconnected loses any manual
        // expansion, so it collapses back to a chip on its own (A.2.3).
        val nowConnectedIds = views.filter { it.connected }.mapNotNull { it.deviceId }.toSet()
        val pruned = InteractionPolicy.pruneManualExpanded(manualExpandedIds, prevConnectedIds, nowConnectedIds)
        if (pruned != manualExpandedIds) manualExpandedIds = pruned.toMutableSet()
        prevConnectedIds = nowConnectedIds
        anyConnectedNow = nowConnectedIds.isNotEmpty()

        // device cards (rebuild only on signature change; the FB-12
        // form, pin and connecting state are part of the signature so
        // density changes re-render in the same poll that causes them)
        val sig = views.joinToString("|") { v ->
            "${v.deviceId}/${v.ip}/${v.paired}/${v.online}/${v.connected}/${(v.rttMs / 5).toInt()}/${v.autoConnect}/${v.micSend}/${v.speakerRecv}/${(v.speakerVolume * 20).toInt()}/${v.muted}/${v.autoPaused}/${v.justAutoConnected}/${v.name}/${formOf(v)}/${v.pinned}/${v.connecting}"
        } + "#flash$flashDeviceId"
        val cardsChanged = sig != lastCardSignature
        if (cardsChanged && now >= listAnimUntilMs) {
            lastCardSignature = sig
            rebuildDeviceCards(views, now)
        }
        pollPullRefresh(now, cardsChanged)
        if (e.running && views.isEmpty() && now - searchStartMs > 5000) {
            tvNoDevice.visibility = View.VISIBLE
            tvNoDevice.text = getString(R.string.no_device_text)
        } else {
            tvNoDevice.visibility = View.GONE
        }

        if (overlayDiscover.visibility == View.VISIBLE) refreshDiscoverSheet(views)
        pollPairingProgress(now)
    }

    /** Honest echo-layer status, shared by the channel card and Settings. */
    private fun echoStatusText(): String {
        val e = AudioEngine
        if (!e.echoSuppressionEnabled) return getString(R.string.echo_off)
        val l1 = if (e.fxAecActive) getString(R.string.echo_aec_on) else getString(R.string.echo_aec_off)
        val l2 = when {
            e.nlmsActive -> getString(R.string.echo_nlms_locked_erle, e.nlmsErleDb)
            e.nlmsBulkDelay >= 0 -> getString(R.string.echo_nlms_measuring)
            else -> getString(R.string.echo_nlms_locking)
        }
        var s = "$l1 · $l2"
        if (e.headsetOutputActive) s += getString(R.string.echo_headset_suffix)
        if (e.doubleTalkActive) s += getString(R.string.echo_doubletalk_suffix)
        if (e.echoDuckingActive) s += getString(R.string.echo_ducking_suffix)
        if (e.howlGainDb < 0f) s += getString(R.string.echo_howl_suffix, e.howlGainDb.toInt())
        return s
    }

    // ---------------- device cards ----------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun sectionTitle(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(text1)
        tv.textSize = 16f
        tv.typeface = android.graphics.Typeface.DEFAULT_BOLD
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(16)
        tv.layoutParams = lp
        return tv
    }

    /** FB-12 density form for one paired device (plan A.2.2): the pure
     *  rule lives in InteractionPolicy; this only gathers the inputs. */
    private fun formOf(v: AudioEngine.DeviceView): InteractionPolicy.DeviceForm =
        InteractionPolicy.formFor(InteractionPolicy.FormInput(
            connected = v.connected,
            connecting = v.connecting,
            pairingTarget = isPairingTarget(v),
            manualExpanded = v.deviceId != null && manualExpandedIds.contains(v.deviceId)))

    private fun isPairingTarget(v: AudioEngine.DeviceView): Boolean =
        (awaitingIp != null && awaitingIp == v.ip) ||
            (confirmShown && confirmIp != null && confirmIp == v.ip)

    private fun rebuildDeviceCards(views: List<AudioEngine.DeviceView>, now: Long) {
        deviceContainer.removeAllViews()
        val paired = views.filter { it.paired }
        val fresh = views.filter { !it.paired }
        val expanded = paired.filter { formOf(it) == InteractionPolicy.DeviceForm.CARD }
        val compact = paired.filter { formOf(it) == InteractionPolicy.DeviceForm.CHIP }
            .sortedWith { a, b ->
                InteractionPolicy.COMPACT_ORDER.compare(
                    InteractionPolicy.CompactKey(a.pinned, a.online, a.lastSeenMs),
                    InteractionPolicy.CompactKey(b.pinned, b.online, b.lastSeenMs))
            }
        if (expanded.isNotEmpty()) {
            deviceContainer.addView(sectionTitle(getString(R.string.section_my_pcs)))
            for (v in expanded) deviceContainer.addView(buildPairedCard(v, now))
        }
        if (compact.isNotEmpty()) {
            deviceContainer.addView(sectionTitle(getString(R.string.section_my_pcs_disconnected, compact.size)))
            for (v in compact) deviceContainer.addView(buildPairedChip(v, now))
        }
        if (fresh.isNotEmpty()) {
            deviceContainer.addView(sectionTitle(getString(R.string.section_new_devices)))
            for (v in fresh) deviceContainer.addView(buildUnpairedRow(v))
        }
        // Form transitions (plan A.2.3): a device whose form changed
        // since the last rebuild fades into its new shape over 220 ms;
        // a manual tap additionally animates the height (below).
        val currentIds = paired.mapNotNull { it.deviceId }.toSet()
        lastForms.keys.retainAll(currentIds)
        var anyFormChanged = false
        for (v in paired) {
            val id = v.deviceId ?: continue
            val form = formOf(v)
            val prev = lastForms.put(id, form)
            if (prev != null && prev != form && pendingFormAnim?.first != id) {
                findTaggedDeviceView(id)?.let { view ->
                    view.alpha = 0f
                    view.animate().alpha(1f).setDuration(220)
                        .setInterpolator(overlayInterpolator).start()
                }
                anyFormChanged = true
            }
        }
        if (anyFormChanged) listAnimUntilMs = maxOf(listAnimUntilMs, now + 220)
        pendingFormAnim?.let { (id, fromHeight) ->
            pendingFormAnim = null
            animateFormChange(id, fromHeight, now)
        }
    }

    private fun findTaggedDeviceView(deviceId: String): View? {
        for (i in 0 until deviceContainer.childCount) {
            val child = deviceContainer.getChildAt(i)
            if (child.tag == deviceId) return child
        }
        return null
    }

    /** Height animation for a manual expand/collapse (220 ms, plan
     *  A.2.3): the list rebuild is signature-frozen while it runs so
     *  the animating view is not swapped out mid-flight. */
    private fun animateFormChange(deviceId: String, fromHeight: Int, now: Long) {
        val target = findTaggedDeviceView(deviceId) ?: return
        listAnimUntilMs = maxOf(listAnimUntilMs, now + 220)
        target.post {
            val targetHeight = target.height
            if (targetHeight <= 0 || fromHeight <= 0) return@post
            val lp = target.layoutParams
            lp.height = fromHeight
            target.layoutParams = lp
            val anim = ValueAnimator.ofInt(fromHeight, targetHeight)
            anim.duration = 220
            anim.interpolator = overlayInterpolator
            anim.addUpdateListener { a ->
                lp.height = a.animatedValue as Int
                target.layoutParams = lp
            }
            anim.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    target.layoutParams = lp
                }
            })
            anim.start()
        }
    }

    /** FB-12 compact row (plan A.2.2, prototype v3): one 54dp line —
     *  icon tile, name, status dot + text, expand chevron. The whole
     *  row is the tap target: tap expands (or retries a guard-paused
     *  auto-connect), long-press opens the same device menu as cards. */
    private fun buildPairedChip(v: AudioEngine.DeviceView, now: Long): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.tag = v.deviceId
        val flash = flashDeviceId != null && v.deviceId == flashDeviceId && now < flashUntilMs
        row.setBackgroundResource(if (flash) R.drawable.bg_card_highlight else R.drawable.bg_card)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54))
        lp.topMargin = dp(8)
        row.layoutParams = lp
        row.setPadding(dp(10), 0, dp(10), 0)
        if (!v.online) row.alpha = 0.75f

        val icon = TextView(this)
        icon.text = "🖥"
        icon.setBackgroundResource(R.drawable.bg_chip)
        icon.setPadding(dp(7), dp(5), dp(7), dp(5))
        row.addView(icon)

        val nameTv = TextView(this)
        nameTv.text = v.name
        nameTv.setTextColor(text1); nameTv.textSize = 14f
        nameTv.typeface = android.graphics.Typeface.DEFAULT_BOLD
        nameTv.maxLines = 1
        nameTv.ellipsize = android.text.TextUtils.TruncateAt.END
        val nlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT)
        nlp.weight = 1f; nlp.marginStart = dp(10)
        nameTv.layoutParams = nlp
        row.addView(nameTv)

        val dot = View(this)
        dot.layoutParams = LinearLayout.LayoutParams(dp(7), dp(7))
        dot.setBackgroundResource(R.drawable.bg_dot)
        dot.backgroundTintList = ColorStateList.valueOf(when {
            v.autoPaused -> error
            v.online -> accent
            else -> text3
        })
        row.addView(dot)

        val status = TextView(this)
        status.text = when {
            v.autoPaused -> getString(R.string.badge_auto_failed)
            v.online -> getString(R.string.chip_online)
            else -> getString(R.string.chip_offline) + " · " +
                getString(R.string.last_connected_format, formatLastSeen(v.lastSeenMs))
        }
        status.setTextColor(if (v.autoPaused) error else text2)
        status.textSize = 12f
        status.maxLines = 1
        val slp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        slp.marginStart = dp(6)
        status.layoutParams = slp
        row.addView(status)

        val chevron = TextView(this)
        chevron.text = "▾"
        chevron.setTextColor(text3); chevron.textSize = 14f
        val chlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        chlp.marginStart = dp(6)
        chevron.layoutParams = chlp
        row.addView(chevron)

        row.setOnClickListener {
            if (v.autoPaused) {
                // Guard-paused: a tap retries directly, never expands.
                AudioEngine.retryAutoConnect(v.ip)
                lastCardSignature = ""
                return@setOnClickListener
            }
            val id = v.deviceId ?: return@setOnClickListener
            pendingFormAnim = id to row.height
            manualExpandedIds = mutableSetOf(id) // exclusive (A.2.3)
            lastCardSignature = ""
        }
        wireDeviceLongPress(row, v)
        return row
    }

    /** Long-press on a card or a chip: LONG_PRESS haptic + a brief
     *  press-scale, then the shared device menu (plan A.1.6). Uses the
     *  system long-press duration — no custom timer (A.4). */
    private fun wireDeviceLongPress(view: View, v: AudioEngine.DeviceView) {
        view.setOnLongClickListener { anchor ->
            anchor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            anchor.animate().scaleX(0.98f).scaleY(0.98f).setDuration(120)
                .withEndAction { anchor.animate().scaleX(1f).scaleY(1f).setDuration(120).start() }
                .start()
            showDeviceMenu(anchor, v)
            true
        }
    }

    private fun badgeView(text: String, bg: Int, color: Int): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setBackgroundResource(bg)
        tv.setTextColor(color)
        tv.textSize = 12f
        tv.typeface = android.graphics.Typeface.DEFAULT_BOLD
        return tv
    }

    private fun buildPairedCard(v: AudioEngine.DeviceView, now: Long): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.tag = v.deviceId
        val flash = flashDeviceId != null && v.deviceId == flashDeviceId && now < flashUntilMs
        card.setBackgroundResource(when {
            flash -> R.drawable.bg_card_highlight
            v.connected -> R.drawable.bg_card_live
            v.online -> R.drawable.bg_card_online
            else -> R.drawable.bg_card
        })
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(10)
        card.layoutParams = lp

        val dimAlpha = if (v.online) 1.0f else 0.55f

        // header block (dims when offline)
        val header = LinearLayout(this); header.orientation = LinearLayout.VERTICAL; header.alpha = dimAlpha
        val nameRow = LinearLayout(this); nameRow.orientation = LinearLayout.HORIZONTAL; nameRow.gravity = Gravity.CENTER_VERTICAL
        val dot = View(this)
        dot.layoutParams = LinearLayout.LayoutParams(dp(8), dp(8))
        dot.setBackgroundResource(R.drawable.bg_dot)
        dot.backgroundTintList = ColorStateList.valueOf(if (v.online) accent else text3)
        nameRow.addView(dot)
        val nameTv = TextView(this)
        nameTv.text = "🖥  ${v.name}"
        nameTv.setTextColor(text1); nameTv.textSize = 16f; nameTv.typeface = android.graphics.Typeface.DEFAULT_BOLD
        val nlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT); nlp.weight = 1f; nlp.marginStart = dp(8)
        nameTv.layoutParams = nlp
        nameRow.addView(nameTv)
        when {
            v.versionMismatch || partnerVersionDiffers(v) -> nameRow.addView(badgeView(getString(R.string.badge_pc_version_mismatch), R.drawable.bg_badge_bad, error))
            v.connected && v.rttMs >= 0 -> {
                val (bg, col, word) = when {
                    v.rttMs < 30 -> Triple(R.drawable.bg_badge_good, accent, getString(R.string.quality_good))
                    v.rttMs <= 80 -> Triple(R.drawable.bg_badge_warn, warn, getString(R.string.quality_fair))
                    else -> Triple(R.drawable.bg_badge_bad, error, getString(R.string.quality_poor))
                }
                nameRow.addView(badgeView(String.format("%.0f ms · %s", v.rttMs, word), bg, col))
            }
            v.connected -> nameRow.addView(badgeView(getString(R.string.badge_connected), R.drawable.bg_badge_idle, text2))
            v.autoPaused -> nameRow.addView(badgeView(getString(R.string.badge_auto_failed), R.drawable.bg_badge_bad, error))
            v.online -> nameRow.addView(badgeView(getString(R.string.badge_online_not_connected), R.drawable.bg_badge_idle, text2))
            else -> nameRow.addView(badgeView(getString(R.string.badge_offline), R.drawable.bg_chip, text2))
        }
        header.addView(nameRow)
        val sub = TextView(this)
        sub.text = "${v.os.ifEmpty { getString(R.string.pc_default_name) }} · ${v.ip}" + if (v.pcAppVersion.isNotEmpty()) getString(R.string.card_sub_pc_version_suffix, v.pcAppVersion) else ""
        sub.setTextColor(text2); sub.textSize = 12f; sub.typeface = android.graphics.Typeface.MONOSPACE
        header.addView(sub)
        card.addView(header)
        // FB-12: tapping the header of a manually expanded card folds
        // it back to a chip. A connected card never collapses by hand
        // (plan A.2.2) — the tap explains instead; state-driven cards
        // (connecting / pairing) ignore the tap entirely.
        header.setOnClickListener {
            val id = v.deviceId ?: return@setOnClickListener
            when {
                v.connected -> Toast.makeText(this,
                    R.string.connected_cannot_collapse_toast, Toast.LENGTH_SHORT).show()
                manualExpandedIds.contains(id) -> {
                    pendingFormAnim = id to card.height
                    manualExpandedIds.remove(id)
                    lastCardSignature = ""
                }
            }
        }

        // auto-connect row — NEVER dimmed/disabled, even offline (§3.3 hard rule)
        val autoRow = LinearLayout(this); autoRow.orientation = LinearLayout.HORIZONTAL; autoRow.gravity = Gravity.CENTER_VERTICAL
        val alp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); alp.topMargin = dp(8)
        autoRow.layoutParams = alp
        val autoLabel = TextView(this)
        if (v.justAutoConnected && v.connected) {
            autoLabel.text = getString(R.string.card_auto_connected_just_now)
            autoLabel.setTextColor(accent)
        } else {
            autoLabel.text = getString(R.string.label_auto_connect)
            autoLabel.setTextColor(text1)
        }
        autoLabel.textSize = 13f
        val allp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT); allp.weight = 1f
        autoLabel.layoutParams = allp
        autoRow.addView(autoLabel)
        val swAuto = Switch(this)
        tintSwitch(swAuto, accent, accentDim)
        swAuto.isChecked = v.autoConnect
        swAuto.isEnabled = true
        swAuto.setOnCheckedChangeListener { _, checked ->
            v.deviceId?.let { AudioEngine.setAutoConnect(it, checked) }
            lastCardSignature = ""
        }
        autoRow.addView(swAuto)
        card.addView(autoRow)

        // channels block (dims + disables offline, values preserved)
        val channels = LinearLayout(this); channels.orientation = LinearLayout.VERTICAL; channels.alpha = dimAlpha
        val divider = View(this)
        divider.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { topMargin = dp(10); bottomMargin = dp(4) }
        divider.setBackgroundColor(stroke)
        channels.addView(divider)

        fun switchRow(label: String, checked: Boolean, thumb: Int, track: Int, onChange: (Boolean) -> Unit): LinearLayout {
            val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL; row.gravity = Gravity.CENTER_VERTICAL
            val tv = TextView(this); tv.text = label; tv.setTextColor(text1); tv.textSize = 14f
            val tlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT); tlp.weight = 1f
            tv.layoutParams = tlp
            row.addView(tv)
            val sw = Switch(this)
            tintSwitch(sw, thumb, track)
            sw.isChecked = checked
            sw.isEnabled = v.online
            sw.setOnCheckedChangeListener { _, c -> onChange(c); lastCardSignature = "" }
            row.addView(sw)
            return row
        }
        channels.addView(switchRow(getString(R.string.switch_mic_to_this_pc), v.micSend, accent, accentDim) { c ->
            v.deviceId?.let { AudioEngine.setMicSend(it, c) }
        })
        channels.addView(switchRow(getString(R.string.switch_speaker_from_this_pc), v.speakerRecv, sky, skyDim) { c ->
            v.deviceId?.let { AudioEngine.setSpeakerRecv(it, c) }
        })

        // per-device volume + mute
        val volRow = LinearLayout(this); volRow.orientation = LinearLayout.HORIZONTAL; volRow.gravity = Gravity.CENTER_VERTICAL
        val vlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); vlp.topMargin = dp(4)
        volRow.layoutParams = vlp
        val volLabel = TextView(this); volLabel.text = getString(R.string.card_volume_label); volLabel.setTextColor(text2); volLabel.textSize = 13f
        volRow.addView(volLabel)
        val seek = SeekBar(this)
        seek.max = 100
        seek.progress = Protocol.percentForGain(v.speakerVolume)
        seek.isEnabled = v.online && v.speakerRecv
        seek.progressTintList = ColorStateList.valueOf(if (v.muted) text3 else sky)
        seek.thumbTintList = ColorStateList.valueOf(if (v.muted) text3 else sky)
        val slp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT); slp.weight = 1f; slp.marginStart = dp(8)
        seek.layoutParams = slp
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) v.deviceId?.let { AudioEngine.setSpeakerVolume(it, Protocol.gainForPercent(progress)) }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { lastCardSignature = "" }
        })
        volRow.addView(seek)
        val volText = TextView(this)
        volText.text = if (v.muted) getString(R.string.muted_label) else "${Protocol.percentForGain(v.speakerVolume)}%"
        volText.setTextColor(if (v.muted) error else text2); volText.textSize = 12f; volText.typeface = android.graphics.Typeface.MONOSPACE
        volRow.addView(volText)
        val muteBtn = TextView(this)
        muteBtn.text = if (v.muted) "🔇" else "🔊"
        muteBtn.setPadding(dp(10), dp(6), dp(2), dp(6))
        muteBtn.isEnabled = v.online
        muteBtn.setOnClickListener {
            v.deviceId?.let { AudioEngine.setMuted(it, !v.muted) }
            lastCardSignature = ""
        }
        volRow.addView(muteBtn)
        channels.addView(volRow)
        // Master vs trim (§3.4): this slider is a per-PC relative trim;
        // overall loudness is the system media volume (hardware keys).
        val volNote = TextView(this); volNote.text = getString(R.string.card_volume_note); volNote.setTextColor(text3); volNote.textSize = 11f
        channels.addView(volNote)
        card.addView(channels)

        // footer action (§2.3)
        val footer = LinearLayout(this); footer.orientation = LinearLayout.HORIZONTAL; footer.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        val flp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); flp.topMargin = dp(8)
        footer.layoutParams = flp
        footer.alpha = dimAlpha
        when {
            v.connected -> {
                val b = Button(this)
                b.text = getString(R.string.action_stop); b.setTextColor(error); b.setBackgroundResource(R.drawable.btn_secondary)
                b.setOnClickListener { AudioEngine.disconnectDevice(v.ip); lastCardSignature = "" }
                footer.addView(b)
            }
            v.online -> {
                val b = Button(this)
                b.text = getString(R.string.action_connect); b.setTextColor(accent); b.setBackgroundResource(R.drawable.btn_secondary)
                b.setOnClickListener {
                    if (v.autoPaused) AudioEngine.retryAutoConnect(v.ip) else AudioEngine.connectDevice(v.ip)
                    lastCardSignature = ""
                }
                footer.addView(b)
            }
            else -> {
                val t = TextView(this)
                t.text = getString(R.string.last_connected_format, formatLastSeen(v.lastSeenMs))
                t.setTextColor(text3); t.textSize = 12f; t.typeface = android.graphics.Typeface.MONOSPACE
                footer.addView(t)
            }
        }
        card.addView(footer)

        if (v.autoPaused) {
            card.setOnClickListener { AudioEngine.retryAutoConnect(v.ip); lastCardSignature = "" }
        }
        wireDeviceLongPress(card, v)
        return card
    }

    private fun buildUnpairedRow(v: AudioEngine.DeviceView): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setBackgroundResource(R.drawable.bg_card_dashed)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64))
        lp.topMargin = dp(10)
        row.layoutParams = lp
        val icon = TextView(this)
        icon.text = "🖥"
        icon.setBackgroundResource(R.drawable.bg_chip)
        icon.setPadding(dp(8), dp(6), dp(8), dp(6))
        row.addView(icon)
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        val clp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT); clp.weight = 1f; clp.marginStart = dp(10)
        col.layoutParams = clp
        val nameTv = TextView(this); nameTv.text = v.name; nameTv.setTextColor(text1); nameTv.textSize = 14f; nameTv.typeface = android.graphics.Typeface.DEFAULT_BOLD
        col.addView(nameTv)
        val sub = TextView(this); sub.text = getString(R.string.device_unpaired_sub, v.os.ifEmpty { getString(R.string.pc_default_name) }, v.ip); sub.setTextColor(text2); sub.textSize = 12f
        col.addView(sub)
        row.addView(col)
        val pairBtn = Button(this)
        pairBtn.text = getString(R.string.action_pair)
        pairBtn.setTextColor(sky)
        pairBtn.setBackgroundResource(R.drawable.bg_btn_outline_sky)
        pairBtn.setOnClickListener { openScan(v) }
        row.addView(pairBtn)
        row.setOnClickListener { openScan(v) }
        return row
    }

    private fun showDeviceMenu(anchor: View, v: AudioEngine.DeviceView) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, getString(R.string.menu_rename))
        menu.menu.add(0, 2, 0, getString(R.string.menu_pairing_info))
        menu.menu.add(0, 3, 0, getString(R.string.menu_forget))
        menu.menu.add(0, 4, 0, getString(if (v.pinned) R.string.menu_unpin else R.string.menu_pin))
        menu.setOnMenuItemClickListener { item ->
            val id = v.deviceId ?: return@setOnMenuItemClickListener false
            when (item.itemId) {
                1 -> {
                    val et = EditText(this)
                    et.setText(v.name)
                    et.hint = getString(R.string.rename_hint)
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.rename_title))
                        .setView(et)
                        .setPositiveButton(getString(R.string.action_save)) { _, _ -> AudioEngine.renameDevice(id, et.text.toString()); lastCardSignature = "" }
                        .setNegativeButton(getString(R.string.action_cancel), null)
                        .show()
                }
                2 -> {
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.pairing_info_title))
                        .setMessage(AudioEngine.pairingInfo(id) ?: getString(R.string.pairing_info_none))
                        .setPositiveButton(getString(R.string.action_ok), null)
                        .show()
                }
                3 -> {
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.forget_title))
                        .setMessage(getString(R.string.forget_msg))
                        .setPositiveButton(getString(R.string.action_forget)) { _, _ ->
                            AudioEngine.disconnectDevice(v.ip)
                            AudioEngine.forgetDevice(id)
                            manualExpandedIds.remove(id)
                            lastCardSignature = ""
                        }
                        .setNegativeButton(getString(R.string.action_cancel), null)
                        .show()
                }
                4 -> {
                    // Pin/unpin only reorders the compact group (A.2.5).
                    AudioEngine.setPinned(id, !v.pinned)
                    lastCardSignature = ""
                }
            }
            true
        }
        menu.show()
    }

    /** True when the card PC's version differs (major.minor) from the newest
     *  windows version recorded by the last update-manifest fetch. */
    private fun partnerVersionDiffers(v: AudioEngine.DeviceView): Boolean {
        if (v.pcAppVersion.isEmpty()) return false
        val latest = updatePrefs().getString("partner_latest_version", "") ?: ""
        if (latest.isEmpty()) return false
        fun mm(x: String): Pair<Int, Int> {
            val parts = x.split(".")
            return (parts.getOrNull(0)?.toIntOrNull() ?: 0) to (parts.getOrNull(1)?.toIntOrNull() ?: 0)
        }
        return mm(v.pcAppVersion) != mm(latest)
    }

    private fun formatLastSeen(ms: Long): String {
        if (ms <= 0) return getString(R.string.last_seen_never)
        val then = Calendar.getInstance().apply { timeInMillis = ms }
        val nowCal = Calendar.getInstance()
        val hm = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
        val sameDay = then.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR) && then.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)
        val yest = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val isYest = then.get(Calendar.YEAR) == yest.get(Calendar.YEAR) && then.get(Calendar.DAY_OF_YEAR) == yest.get(Calendar.DAY_OF_YEAR)
        return when {
            sameDay -> getString(R.string.last_seen_today, hm)
            isYest -> getString(R.string.last_seen_yesterday, hm)
            else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
        }
    }

    // ---------------- pairing overlays ----------------

    private fun ensureServiceRunning(): Boolean {
        if (!AudioEngine.running) {
            if (!ensureAudioPermissions()) return false
            startBridge()
            Toast.makeText(this, getString(R.string.engine_started_pairing_toast), Toast.LENGTH_SHORT).show()
        }
        return true
    }

    private fun openScan(target: AudioEngine.DeviceView?) {
        pairTarget = target
        showOverlay(InteractionPolicy.Overlay.SCAN)
        tvScanHint.text = getString(R.string.scan_hint_aim)
        tvScanHint.setTextColor(text1)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startScanner()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            tvScanHint.text = getString(R.string.scan_need_camera_perm_long)
        }
    }

    private fun startScanner() {
        qrScanner?.stop()
        val texture = findViewById<android.view.TextureView>(R.id.textureScan)
        qrScanner = QrScanner(
            this, texture,
            onResult = { text -> onQrResult(text) },
            onError = { msg ->
                runOnUiThread {
                    tvScanHint.text = msg
                    tvScanHint.setTextColor(error)
                }
            }
        )
        qrScanner?.start()
    }

    private fun closeScan() {
        stopScanner()
        if (overlayStack.current == InteractionPolicy.Overlay.SCAN) overlayStack.clear()
        overlayScan.animate().cancel()
        overlayScan.visibility = View.GONE
        overlayScan.alpha = 1f
        overlayScan.translationX = 0f
        syncBackCallbackRegistration()
    }

    private fun onQrResult(text: String) {
        val o = try { JSONObject(text) } catch (_: Exception) { null }
        val deviceId = o?.optString("deviceId") ?: ""
        val token = o?.optString("token") ?: ""
        val ip = o?.optString("ip") ?: ""
        if (o == null || o.optString("app") != "AirBridge" || deviceId.isEmpty() || token.isEmpty() || ip.isEmpty()) {
            tvScanHint.text = getString(R.string.scan_not_soundrelay_qr)
            tvScanHint.setTextColor(error)
            qrScanner?.resume()
            return
        }
        closeScan()
        if (!ensureServiceRunning()) return
        val name = o.optString("name")
        val os = o.optString("os")
        AudioEngine.pairWithToken(ip, deviceId, name, os, token)
        awaitingIp = ip
        pairSubmittedAtMs = System.currentTimeMillis()
        // mismatch note (§4.1): scanned a different PC than the row tapped
        val target = pairTarget
        if (target != null && target.deviceId != null && target.deviceId != deviceId) {
            Toast.makeText(this, getString(R.string.scan_different_pc_toast), Toast.LENGTH_LONG).show()
        }
    }

    private fun openCode(target: AudioEngine.DeviceView?) {
        pairTarget = target
        closeScan()
        showOverlay(InteractionPolicy.Overlay.CODE)
        tvCodeError.visibility = View.GONE
        etPairCode.text = null
        btnCodeSubmit.isEnabled = false
        btnCodeSubmit.alpha = 0.5f
    }

    private fun submitCode() {
        val code = etPairCode.text.toString().trim()
        if (code.length != 6) return
        if (!ensureServiceRunning()) return
        tvCodeError.visibility = View.GONE
        pairSubmittedAtMs = System.currentTimeMillis()
        val target = pairTarget
        if (target != null) {
            AudioEngine.pairAndConnect(target.ip, code)
            awaitingIp = target.ip
        } else {
            AudioEngine.startCodePairing(code)
            awaitingIp = null
        }
        tvCodeError.visibility = View.VISIBLE
        tvCodeError.setTextColor(text2)
        tvCodeError.text = getString(R.string.code_searching)
    }

    private fun pollPairingProgress(now: Long) {
        // B-05 verification watch: after 确认配对 the overlay stays up
        // in a verifying state until the session proves itself for
        // VERIFY_OK_MS; a drop inside the window surfaces the error bar
        // (it used to stay GONE forever — audit R-A6) and offers a retry
        // instead of the old silent optimistic close.
        val verifyIp = confirmVerifyIp
        if (verifyIp != null && !confirmVerifyFailed) {
            val sess = AudioEngine.sessions[verifyIp]
            when (InteractionPolicy.verifyOutcome(
                sessionExists = sess != null,
                connected = sess?.connected == true,
                reResolving = sess?.reResolving == true,
                elapsedMs = now - confirmVerifyStartMs)) {
                InteractionPolicy.VerifyOutcome.OK -> {
                    confirmVerifyIp = null
                    finishConfirmSuccess()
                }
                InteractionPolicy.VerifyOutcome.FAILED -> {
                    confirmVerifyFailed = true
                    findViewById<TextView>(R.id.tvConfirmError).visibility = View.VISIBLE
                    val btnPair = findViewById<Button>(R.id.btnConfirmPair)
                    btnPair.isEnabled = true
                    btnPair.text = getString(R.string.action_retry)
                    if (Build.VERSION.SDK_INT >= 30) {
                        overlayConfirm.performHapticFeedback(HapticFeedbackConstants.REJECT)
                    }
                }
                InteractionPolicy.VerifyOutcome.PENDING -> Unit
            }
            return
        }
        // a pending pair appeared -> verification confirm (§4.3)
        val pending = AudioEngine.pendingPairSession()
        if (pending != null && !confirmShown) {
            showConfirm(pending)
            return
        }
        // already-paired device answered a code/scan attempt -> no confirm needed
        val aIp = awaitingIp
        if (aIp != null && !confirmShown) {
            val sess = AudioEngine.sessions[aIp]
            val did = sess?.deviceId
            if (sess != null && sess.connected && did != null && AudioEngine.registry.containsKey(did)) {
                awaitingIp = null
                hideAllOverlays()
                tvBannerAutoText.text = getString(R.string.already_paired_banner)
                bannerIps = emptyList()
                bannerAuto.visibility = View.VISIBLE
                bannerShownAtMs = now
            }
        }
        // timeout for code/scan attempts
        if ((awaitingIp != null || AudioEngine.codeSearch != null) && pairSubmittedAtMs > 0 && now - pairSubmittedAtMs > 18000 && pending == null && !confirmShown) {
            awaitingIp = null
            AudioEngine.cancelCodePairing()
            pairSubmittedAtMs = 0L
            if (overlayStack.current == InteractionPolicy.Overlay.CODE) {
                tvCodeError.visibility = View.VISIBLE
                tvCodeError.setTextColor(error)
                tvCodeError.text = getString(R.string.code_invalid_error)
            } else {
                Toast.makeText(this, getString(R.string.pair_timeout_toast), Toast.LENGTH_LONG).show()
            }
        }
        if (AudioEngine.codeSearch == null && pairSubmittedAtMs > 0 && awaitingIp == null && pending == null && !confirmShown && overlayStack.current == InteractionPolicy.Overlay.CODE && now - pairSubmittedAtMs > 18000) {
            pairSubmittedAtMs = 0L
        }
    }

    /** The pairing counts as done (verify watch OK): close the overlay
     *  and run the success banner + card flash, with the name/auto text
     *  captured at tap time. */
    private fun finishConfirmSuccess() {
        val did = confirmIp?.let { AudioEngine.sessions[it]?.deviceId }
        confirmShown = false
        hideAllOverlays()
        if (did != null) { flashDeviceId = did; flashUntilMs = System.currentTimeMillis() + 1000 }
        tvBannerAutoText.text = getString(R.string.paired_banner, confirmVerifyName,
            getString(if (confirmVerifyAuto) R.string.auto_on_word else R.string.auto_off_word))
        bannerIps = emptyList()
        bannerAuto.visibility = View.VISIBLE
        bannerShownAtMs = System.currentTimeMillis()
        lastCardSignature = ""
        if (Build.VERSION.SDK_INT >= 30) {
            findViewById<View>(android.R.id.content)
                .performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        }
    }

    private fun showConfirm(s: AudioEngine.Session) {
        confirmShown = true
        confirmIp = s.ip
        // Fresh confirm: reset the B-05 verify state and the button, and
        // clear any error a previous failed attempt left behind.
        confirmVerifyIp = null
        confirmVerifyFailed = false
        val btnPair = findViewById<Button>(R.id.btnConfirmPair)
        btnPair.isEnabled = true
        btnPair.text = getString(R.string.action_confirm_pair)
        showOverlay(InteractionPolicy.Overlay.CONFIRM)
        findViewById<TextView>(R.id.tvConfirmName).text = "🖥  ${s.name}"
        findViewById<TextView>(R.id.tvConfirmSub).text = "${s.os.ifEmpty { getString(R.string.pc_default_name) }} · ${s.ip}"
        val code = s.pendingVerifyCode ?: "------"
        findViewById<TextView>(R.id.tvConfirmVerify).text = code.toCharArray().joinToString(" ")
        findViewById<TextView>(R.id.tvConfirmError).visibility = View.GONE
        findViewById<Switch>(R.id.swAutoConfirm).isChecked = true
    }

    private fun wirePairingOverlays() {
        findViewById<TextView>(R.id.btnScanBack).setOnClickListener { closeTopOverlay() }
        findViewById<TextView>(R.id.btnScanToCode).setOnClickListener { openCode(pairTarget) }
        findViewById<TextView>(R.id.btnTorch).setOnClickListener { v ->
            val on = !(qrScanner?.torchOn ?: false)
            qrScanner?.setTorch(on)
            (v as TextView).setTextColor(if (on) accent else text2)
        }
        findViewById<TextView>(R.id.btnCodeBack).setOnClickListener { closeTopOverlay() }
        findViewById<TextView>(R.id.btnCodeToScan).setOnClickListener { openScan(pairTarget) }
        etPairCode.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val full = (s?.length ?: 0) == 6
                btnCodeSubmit.isEnabled = full
                btnCodeSubmit.alpha = if (full) 1.0f else 0.5f
                if (full) submitCode()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        btnCodeSubmit.setOnClickListener { submitCode() }
        findViewById<Button>(R.id.btnConfirmPair).setOnClickListener { clicked ->
            val btn = clicked as Button
            val ip = confirmIp ?: return@setOnClickListener
            val errorBar = findViewById<TextView>(R.id.tvConfirmError)
            if (confirmVerifyIp == ip && confirmVerifyFailed) {
                // Retry after a failed verification: the registration
                // already landed — re-drive the connection and watch
                // the session prove itself again.
                AudioEngine.connectDevice(ip)
                confirmVerifyFailed = false
                confirmVerifyStartMs = System.currentTimeMillis()
                errorBar.visibility = View.GONE
                btn.isEnabled = false
                btn.text = getString(R.string.confirm_verifying)
                return@setOnClickListener
            }
            if (confirmVerifyIp != null) return@setOnClickListener // already verifying
            val auto = findViewById<Switch>(R.id.swAutoConfirm).isChecked
            val sess = AudioEngine.sessions[ip]
            confirmVerifyName = sess?.name ?: getString(R.string.pc_default_name)
            confirmVerifyAuto = auto
            if (!AudioEngine.confirmPairing(ip, auto)) {
                // B-05: nothing was registered (session/token gone) —
                // surface it on the error bar instead of the old fake
                // success close.
                errorBar.visibility = View.VISIBLE
                if (Build.VERSION.SDK_INT >= 30) {
                    overlayConfirm.performHapticFeedback(HapticFeedbackConstants.REJECT)
                }
                return@setOnClickListener
            }
            awaitingIp = null
            pairSubmittedAtMs = 0L
            // Enter the verifying state — pollPairingProgress's watch
            // closes via finishConfirmSuccess once the session has
            // stayed up for VERIFY_OK_MS, or flips to the failed state.
            confirmVerifyIp = ip
            confirmVerifyStartMs = System.currentTimeMillis()
            confirmVerifyFailed = false
            errorBar.visibility = View.GONE
            btn.isEnabled = false
            btn.text = getString(R.string.confirm_verifying)
        }
        findViewById<TextView>(R.id.btnConfirmCancel).setOnClickListener {
            // Same path as Back on this overlay (plan A.1.3): cancels
            // the pairing unless registration already landed.
            closeTopOverlay()
        }
    }

    // ---------------- settings (v2.1) ----------------

    private fun openSettings() {
        showOverlay(InteractionPolicy.Overlay.SETTINGS)
        refreshSettingsStates()
    }

    private fun refreshSettingsStates() {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val whitelisted = pm.isIgnoringBatteryOptimizations(packageName)
        findViewById<TextView>(R.id.tvBatteryState).text =
            if (whitelisted) getString(R.string.battery_ok)
            else getString(R.string.battery_warn)
        findViewById<TextView>(R.id.tvEchoLayers).text = echoStatusText()
        // actual (confirmed) audio devices — not just the selection
        val e = AudioEngine
        val fb = e.routeFallbackNote
        findViewById<TextView>(R.id.tvOutputRouteState).text =
            getString(R.string.output_state_format, e.outputRouteText) +
                (if (e.headsetOutputActive) getString(R.string.output_headset_suffix) else "") +
                (if (fb != null) " · $fb" else "") +
                getString(R.string.playout_diag_prefix, e.playoutDiagText) +
                (if (e.routePinFailed) getString(R.string.output_route_pin_failed_suffix) else "") +
                getString(R.string.loopguard_count_suffix, e.loopGuard.detectionCount) +
                (if (e.loopGuard.cappedReleaseCount > 0) getString(R.string.loopguard_capped_suffix, e.loopGuard.cappedReleaseCount) else "")
        findViewById<TextView>(R.id.tvInputDeviceState).text = getString(R.string.input_state_format, e.actualInputText())
    }

    /** Build the output/input device radio lists from the LIVE device set. */
    private fun buildDevicePickers() {
        val p = AppSettings.prefs(this)
        val outSel = p.getString(AppSettings.KEY_OUTPUT_DEVICE, "auto") ?: "auto"
        val rgOut = findViewById<android.widget.RadioGroup>(R.id.rgOutputRoute)
        rgOut.setOnCheckedChangeListener(null)
        rgOut.removeAllViews()
        addDeviceRadio(rgOut, "auto", getString(R.string.output_auto_label), outSel == "auto")
        for (d in AudioEngine.commDevices()) {
            val key = "type:${d.type}|${d.name}"
            addDeviceRadio(rgOut, key, d.name, outSel == key)
        }
        rgOut.setOnCheckedChangeListener { group, checkedId ->
            val key = group.findViewById<android.widget.RadioButton>(checkedId)?.tag as? String ?: return@setOnCheckedChangeListener
            p.edit().putString(AppSettings.KEY_OUTPUT_DEVICE, key).apply()
            AudioEngine.reloadSettings()
            lastDeviceSignature = "" // force a state refresh next tick
        }
        val inSel = p.getString(AppSettings.KEY_INPUT_DEVICE, "auto") ?: "auto"
        val rgIn = findViewById<android.widget.RadioGroup>(R.id.rgInputDevices)
        rgIn.setOnCheckedChangeListener(null)
        rgIn.removeAllViews()
        addDeviceRadio(rgIn, "auto", getString(R.string.input_auto_label), inSel == "auto")
        addDeviceRadio(rgIn, "builtin", getString(R.string.device_phone_mic), inSel == "builtin")
        for (d in AudioEngine.inputDevices()) {
            val key = "type:${d.type}|${d.name}"
            addDeviceRadio(rgIn, key, getString(R.string.input_device_mic_suffix, d.name), inSel == key)
        }
        rgIn.setOnCheckedChangeListener { group, checkedId ->
            val key = group.findViewById<android.widget.RadioButton>(checkedId)?.tag as? String ?: return@setOnCheckedChangeListener
            p.edit().putString(AppSettings.KEY_INPUT_DEVICE, key).apply()
            AudioEngine.reloadSettings()
            lastDeviceSignature = ""
        }
    }

    private fun addDeviceRadio(group: android.widget.RadioGroup, key: String, label: String, checked: Boolean) {
        val rb = android.widget.RadioButton(this)
        rb.text = label
        rb.tag = key
        rb.setTextColor(text1)
        rb.textSize = 13f
        rb.isChecked = checked
        group.addView(rb)
    }

    private fun wireSettingsUi() {
        findViewById<TextView>(R.id.btnSettingsBack).setOnClickListener { closeTopOverlay() }
        val p = AppSettings.prefs(this)
        // language radios (board item #2): "" = follow system / zh / en
        val rgLang = findViewById<android.widget.RadioGroup>(R.id.rgLanguage)
        when (AppSettings.language(this)) {
            "zh" -> findViewById<android.widget.RadioButton>(R.id.rbLanguageZh).isChecked = true
            "en" -> findViewById<android.widget.RadioButton>(R.id.rbLanguageEn).isChecked = true
            else -> findViewById<android.widget.RadioButton>(R.id.rbLanguageSystem).isChecked = true
        }
        rgLang.setOnCheckedChangeListener { _, checkedId ->
            val lang = when (checkedId) {
                R.id.rbLanguageZh -> "zh"
                R.id.rbLanguageEn -> "en"
                else -> ""
            }
            if (lang != AppSettings.language(this)) {
                AppLocale.apply(this, lang)
                // 33+: the framework recreates on the locale change.
                // Below 33 the attachBaseContext wrap needs a recreate.
                if (android.os.Build.VERSION.SDK_INT < 33) recreate()
            }
        }
        // reconnect mode radios
        val rg = findViewById<android.widget.RadioGroup>(R.id.rgReconnect)
        val ask = findViewById<android.widget.RadioButton>(R.id.rbReconnectAsk)
        val auto = findViewById<android.widget.RadioButton>(R.id.rbReconnectAuto)
        val never = findViewById<android.widget.RadioButton>(R.id.rbReconnectNever)
        when (p.getInt(AppSettings.KEY_RECONNECT_MODE, AudioEngine.RECONNECT_ASK)) {
            AudioEngine.RECONNECT_AUTO -> auto.isChecked = true
            AudioEngine.RECONNECT_NEVER -> never.isChecked = true
            else -> ask.isChecked = true
        }
        rg.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rbReconnectAuto -> AudioEngine.RECONNECT_AUTO
                R.id.rbReconnectNever -> AudioEngine.RECONNECT_NEVER
                else -> AudioEngine.RECONNECT_ASK
            }
            p.edit().putInt(AppSettings.KEY_RECONNECT_MODE, mode).apply()
            AudioEngine.reloadSettings()
        }
        // background service toggle
        val swBg = findViewById<Switch>(R.id.swBgService)
        tintSwitch(swBg, accent, accentDim)
        swBg.isChecked = p.getBoolean(AppSettings.KEY_BG_SERVICE, true)
        swBg.setOnCheckedChangeListener { _, checked ->
            p.edit().putBoolean(AppSettings.KEY_BG_SERVICE, checked).apply()
            when {
                checked && !usingService && AudioEngine.running -> {
                    usingService = true
                    startBridgeService()
                    Toast.makeText(this, getString(R.string.bg_on_toast), Toast.LENGTH_SHORT).show()
                }
                !checked && usingService -> {
                    BridgeService.stopEngineOnDestroy = false
                    stopService(Intent(this, BridgeService::class.java))
                    usingService = false
                    Toast.makeText(this, getString(R.string.bg_off_toast), Toast.LENGTH_LONG).show()
                }
            }
        }
        // battery optimisation guide
        findViewById<Button>(R.id.btnBatteryGuide).setOnClickListener {
            try {
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
                try { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
            }
        }
        // echo toggle (settings copy; channel-card copy stays in sync via setEchoEnabled)
        val swEchoS = findViewById<Switch>(R.id.swEchoSettings)
        tintSwitch(swEchoS, accent, accentDim)
        swEchoS.isChecked = p.getBoolean(AppSettings.KEY_ECHO, true)
        swEchoS.setOnCheckedChangeListener { _, checked -> setEchoEnabled(checked) }
        // notifications toggle
        val swNotif = findViewById<Switch>(R.id.swNotifications)
        tintSwitch(swNotif, accent, accentDim)
        swNotif.isChecked = p.getBoolean(AppSettings.KEY_NOTIFICATIONS, true)
        swNotif.setOnCheckedChangeListener { _, checked ->
            p.edit().putBoolean(AppSettings.KEY_NOTIFICATIONS, checked).apply()
            AudioEngine.reloadSettings()
        }
        // mic mode radios (语音通话/高清拾音/自动; session-boundary effect)
        val rgMode = findViewById<android.widget.RadioGroup>(R.id.rgMicMode)
        when (p.getInt(AppSettings.KEY_MIC_MODE, 0)) {
            1 -> findViewById<android.widget.RadioButton>(R.id.rbMicModeHd).isChecked = true
            2 -> findViewById<android.widget.RadioButton>(R.id.rbMicModeAuto).isChecked = true
            else -> findViewById<android.widget.RadioButton>(R.id.rbMicModeComm).isChecked = true
        }
        rgMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rbMicModeHd -> 1
                R.id.rbMicModeAuto -> 2
                else -> 0
            }
            p.edit().putInt(AppSettings.KEY_MIC_MODE, mode).apply()
            AudioEngine.reloadSettings()
            Toast.makeText(this, getString(R.string.mic_mode_effective_toast), Toast.LENGTH_SHORT).show()
        }
        // mic sensitivity radios (defect 3)
        val rgSens = findViewById<android.widget.RadioGroup>(R.id.rgMicSens)
        if (p.getInt(AppSettings.KEY_MIC_SENSITIVITY, 0) == 1) {
            findViewById<android.widget.RadioButton>(R.id.rbMicHigh).isChecked = true
        } else {
            findViewById<android.widget.RadioButton>(R.id.rbMicStd).isChecked = true
        }
        rgSens.setOnCheckedChangeListener { _, checkedId ->
            p.edit().putInt(AppSettings.KEY_MIC_SENSITIVITY, if (checkedId == R.id.rbMicHigh) 1 else 0).apply()
            AudioEngine.reloadSettings()
        }
        // noise-suppression radios (defect 3): default 轻柔
        val rgNs = findViewById<android.widget.RadioGroup>(R.id.rgNsMode)
        if (p.getInt(AppSettings.KEY_NS_MODE, 1) == 0) {
            findViewById<android.widget.RadioButton>(R.id.rbNsStd).isChecked = true
        } else {
            findViewById<android.widget.RadioButton>(R.id.rbNsSoft).isChecked = true
        }
        rgNs.setOnCheckedChangeListener { _, checkedId ->
            p.edit().putInt(AppSettings.KEY_NS_MODE, if (checkedId == R.id.rbNsStd) 0 else 1).apply()
            AudioEngine.reloadSettings()
        }
        // cross-subnet discovery extras (v2.2.3 FB-6): free-form subnet
        // list, parsed by the engine's SubnetSweep on every reload.
        val etSubnets = findViewById<EditText>(R.id.etExtraSubnets)
        etSubnets.setText(p.getString(AppSettings.KEY_EXTRA_SUBNETS, "") ?: "")
        findViewById<Button>(R.id.btnSaveSubnets).setOnClickListener {
            p.edit().putString(AppSettings.KEY_EXTRA_SUBNETS, etSubnets.text.toString().trim()).apply()
            AudioEngine.reloadSettings()
            Toast.makeText(this, getString(R.string.extra_subnets_saved_toast), Toast.LENGTH_SHORT).show()
        }
        // device pickers (headset inheritance)
        buildDevicePickers()
        // headset banner actions
        findViewById<TextView>(R.id.btnHeadsetSwitch).setOnClickListener { AudioEngine.acceptHeadsetOffer() }
        findViewById<TextView>(R.id.btnHeadsetDismiss).setOnClickListener { AudioEngine.dismissHeadsetOffer() }
    }

    // ---------------- nearby discovery sheet (v2.1, PRIMARY pairing entry) ----------------

    private fun openDiscover() {
        closeScan()
        showOverlay(InteractionPolicy.Overlay.DISCOVER)
        lastDiscoverSignature = ""
        if (!ensureServiceRunning()) { /* sheet still lists what the engine hears once it starts */ }
        ensureBlePermission()
        AudioEngine.ensureBleScan()
    }

    private fun ensureBlePermission() {
        val needed = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_SCAN)
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), REQ_BLE)
    }

    private fun wireDiscoverUi() {
        findViewById<TextView>(R.id.btnDiscoverBack).setOnClickListener { closeTopOverlay() }
        findViewById<TextView>(R.id.btnDiscoverScan).setOnClickListener { openScan(null) }
        findViewById<TextView>(R.id.btnDiscoverCode).setOnClickListener { openCode(null) }
        findViewById<TextView>(R.id.btnDiscoverManual).setOnClickListener { closeTopOverlay(); showManualIpDialog() }
    }

    private fun refreshDiscoverSheet(views: List<AudioEngine.DeviceView>) {
        val e = AudioEngine
        val nearby = e.nearbyDevices()
        tvDiscoverStatus.text = getString(R.string.discover_status_format, e.mdnsStatusText, e.bleStatusText) +
            "\n" + e.discoveryDiagText()
        tvDiscoverEmpty.visibility = if (nearby.isEmpty()) View.VISIBLE else View.GONE
        val sig = nearby.joinToString("|") { n ->
            "${n.key}/${n.deviceId}/${n.ip}/${n.rssi}/${n.sources}/${views.firstOrNull { v -> v.deviceId == n.deviceId || v.ip == n.ip }?.connected}"
        }
        if (sig == lastDiscoverSignature) return
        lastDiscoverSignature = sig
        discoverContainer.removeAllViews()
        for (n in nearby) {
            discoverContainer.addView(buildNearbyRow(n, views))
        }
    }

    private fun buildNearbyRow(n: AudioEngine.NearbyDevice, views: List<AudioEngine.DeviceView>): View {
        val view = views.firstOrNull { v -> (n.deviceId != null && v.deviceId == n.deviceId) || (n.ip != null && v.ip == n.ip) }
        val paired = view?.paired == true || (n.deviceId != null && AudioEngine.registry.containsKey(n.deviceId))
        val connected = view?.connected == true
        val name = view?.name ?: n.name.ifEmpty { n.deviceId ?: n.bleAddress ?: getString(R.string.unknown_pc) }
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setBackgroundResource(if (paired) R.drawable.bg_card_online else R.drawable.bg_card_dashed)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(10)
        row.layoutParams = lp
        row.setPadding(dp(12), dp(10), dp(12), dp(10))
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        val clp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT); clp.weight = 1f
        col.layoutParams = clp
        val nameTv = TextView(this); nameTv.text = "🖥  $name"; nameTv.setTextColor(text1); nameTv.textSize = 15f; nameTv.typeface = android.graphics.Typeface.DEFAULT_BOLD
        col.addView(nameTv)
        val srcText = n.sources.joinToString(" · ") { s ->
            when (s) { "LAN" -> getString(R.string.source_lan); "MDNS" -> "mDNS"; "BLE" -> getString(R.string.source_ble); else -> s }
        }
        val prox = n.rssi?.let { r -> when { r >= -60 -> getString(R.string.proximity_close); r >= -75 -> getString(R.string.proximity_near); else -> getString(R.string.proximity_far) } }
        val sub = TextView(this)
        sub.text = buildString {
            append(view?.os ?: n.os.ifEmpty { getString(R.string.pc_default_name) })
            append(" · ")
            append(n.ip ?: getString(R.string.nearby_ble_waiting_ip))
            append(" · ").append(srcText)
            if (prox != null) append(" · ").append(prox)
            if (paired) append(getString(R.string.nearby_paired_suffix))
        }
        sub.setTextColor(text2); sub.textSize = 12f
        col.addView(sub)
        row.addView(col)
        val btn = Button(this)
        when {
            connected -> { btn.text = getString(R.string.badge_connected); btn.isEnabled = false }
            n.ip == null -> { btn.text = getString(R.string.btn_waiting_address); btn.isEnabled = false }
            paired -> {
                btn.text = getString(R.string.action_connect)
                btn.setTextColor(accent); btn.setBackgroundResource(R.drawable.btn_secondary)
                btn.setOnClickListener { AudioEngine.connectDevice(n.ip!!); closeTopOverlay() }
            }
            else -> {
                btn.text = getString(R.string.action_pair)
                btn.setTextColor(sky); btn.setBackgroundResource(R.drawable.bg_btn_outline_sky)
                btn.setOnClickListener {
                    pairTarget = null
                    AudioEngine.pairAndConnect(n.ip!!, null)
                    awaitingIp = n.ip
                    pairSubmittedAtMs = System.currentTimeMillis()
                    closeTopOverlay()
                }
            }
        }
        if (!connected && n.ip != null) { /* styling set above */ }
        row.addView(btn)
        return row
    }

    companion object {
        private const val REQ_AUDIO = 42
        private const val REQ_CAMERA = 43
        private const val REQ_BLE = 44
        private const val REQ_CAMERA_RELAY = 45
    }
}
