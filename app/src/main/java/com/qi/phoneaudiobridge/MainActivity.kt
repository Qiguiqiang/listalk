package com.qi.phoneaudiobridge

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
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
 * 声桥 AirBridge — multi-PC home (UI_SPEC.md + UI_SPEC_MULTIDEVICE.md):
 * overview hero, one card per PC (three states, per-device toggles,
 * auto-connect switch that stays live on offline cards), unpaired rows,
 * add-a-PC card, and the pairing overlays (QR scan / 6-digit code /
 * verification confirm) — all in this single Activity.
 */
class MainActivity : Activity() {

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        buildMeter(meterMic, micSegs)
        buildMeter(meterSpk, spkSegs)
        wireMainButtons()
        wirePairingOverlays()
        wireSettingsUi()
        wireDiscoverUi()
        wireUpdateUi()
        checkUpdate(manual = false)
    }

    // ---------------- online update (v2) ----------------

    private fun updatePrefs() = getSharedPreferences("airbridge_settings", Context.MODE_PRIVATE)

    private fun updateManifestUrl(): String =
        updatePrefs().getString("update_url", UpdateChecker.DEFAULT_UPDATE_MANIFEST_URL)
            ?: UpdateChecker.DEFAULT_UPDATE_MANIFEST_URL

    private fun wireUpdateUi() {
        val et = findViewById<EditText>(R.id.etUpdateUrl)
        et.setText(updateManifestUrl())
        findViewById<TextView>(R.id.tvUpdateInfo).text = "当前版本 ${UpdateChecker.APP_VERSION}"
        findViewById<Button>(R.id.btnCheckUpdate).setOnClickListener {
            val url = et.text.toString().trim()
            if (url.isNotEmpty()) updatePrefs().edit().putString("update_url", url).apply()
            checkUpdate(manual = true)
        }
    }

    private fun checkUpdate(manual: Boolean) {
        val url = updateManifestUrl()
        val tvInfo = findViewById<TextView>(R.id.tvUpdateInfo)
        // v2.2 defect B: a click must be VISIBLE immediately — never a
        // silent no-op. The card then lands on exactly one outcome:
        // 已是最新版本 / 发现新版本 / 检查失败：<plain reason>, with the
        // fetch diagnostics (URL + HTTP status + manifest versions)
        // kept as the card's second line.
        if (manual) tvInfo.text = "正在检查更新…（当前版本 ${UpdateChecker.APP_VERSION}）"
        Thread {
            val report = UpdateChecker.fetchManifestReport(url)
            if (report.partnerVersion != null) {
                updatePrefs().edit().putString("partner_latest_version", report.partnerVersion).apply()
            }
            runOnUiThread {
                val info = report.info
                when {
                    report.error != null || info == null -> {
                        val reason = report.error ?: "更新清单无效（内容无法解析）"
                        tvInfo.text = "检查失败：$reason\n${report.summary}"
                        if (manual) Toast.makeText(this, "检查更新失败：$reason", Toast.LENGTH_LONG).show()
                    }
                    UpdateChecker.isNewer(info.version) -> {
                        tvInfo.text = "发现新版本 ${info.version}（当前 ${UpdateChecker.APP_VERSION}）\n${report.summary}"
                        AlertDialog.Builder(this)
                            .setTitle("发现新版本 ${info.version}")
                            .setMessage(info.notes.ifEmpty { "有新版本可用。" })
                            .setPositiveButton("下载并安装") { _, _ -> downloadUpdate(info) }
                            .setNegativeButton("以后再说", null)
                            .show()
                    }
                    else -> {
                        tvInfo.text = "已是最新版本 · 当前 ${UpdateChecker.APP_VERSION} · 服务器 ${info.version}" +
                            (report.partnerVersion?.let { " · 电脑端最新 v$it" } ?: "") +
                            "\n${report.summary}"
                        if (manual) Toast.makeText(this, "已是最新版（${UpdateChecker.APP_VERSION}）", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.start()
    }

    private fun downloadUpdate(info: UpdateChecker.UpdateInfo) {
        val tvInfo = findViewById<TextView>(R.id.tvUpdateInfo)
        tvInfo.text = "正在下载新版本 ${info.version}…"
        Toast.makeText(this, "正在下载更新…", Toast.LENGTH_SHORT).show()
        Thread {
            val dest = File(cacheDir, "airbridge-update.apk")
            val okDownload = UpdateChecker.downloadTo(info.url, dest)
            // Tamper gate: SHA-256 must match the manifest or we abort.
            val okVerify = okDownload && UpdateChecker.verify(dest, info.sha256)
            runOnUiThread {
                when {
                    !okDownload -> {
                        tvInfo.text = "下载新版本 ${info.version} 失败，请重试"
                        Toast.makeText(this, "下载失败，请重试", Toast.LENGTH_LONG).show()
                    }
                    !okVerify -> {
                        dest.delete()
                        tvInfo.text = "更新包校验失败（SHA-256 不一致），已中止安装"
                        Toast.makeText(this, "更新包校验失败（SHA-256 不一致），已中止安装", Toast.LENGTH_LONG).show()
                    }
                    else -> {
                        tvInfo.text = "新版本 ${info.version} 下载完成并校验通过，正在打开安装器…"
                        installDownloaded(dest)
                    }
                }
            }
        }.start()
    }

    private fun installDownloaded(apk: File) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle("需要安装权限")
                .setMessage("请在系统设置里允许声桥安装未知应用，然后回来重新检查更新。")
                .setPositiveButton("去设置") { _, _ ->
                    try {
                        startActivity(
                            Intent(
                                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                android.net.Uri.parse("package:$packageName")
                            )
                        )
                    } catch (_: Exception) {}
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        try {
            startActivity(UpdateInstaller.installIntent(this, apk))
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开系统安装器：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun bindViews() {
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
            } else if (AudioEngine.connectedCount >= 2) {
                stopService(Intent(this, BridgeService::class.java))
                AudioEngine.stop()
            }
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

        // playback runs on the voice-call stream (USAGE_VOICE_COMMUNICATION)
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val seek = findViewById<SeekBar>(R.id.seekVolume)
        seek.max = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        seek.progress = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, progress, 0)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

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
        et.hint = "电脑 IP，如 192.168.1.20"
        et.inputType = android.text.InputType.TYPE_CLASS_TEXT
        et.setTextColor(text1)
        et.setHintTextColor(text3)
        AlertDialog.Builder(this)
            .setTitle("手动输入电脑 IP")
            .setMessage("这是最保底的方式：标准配对请用「配对新电脑」的附近列表。")
            .setView(et)
            .setPositiveButton("连接") { _, _ ->
                val ip = et.text.toString().trim()
                if (ip.isEmpty()) {
                    Toast.makeText(this, "先输入电脑 IP", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (!AudioEngine.running) {
                    Toast.makeText(this, "先点「开始搜索」，再连接电脑", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                AudioEngine.connectManual(ip)
            }
            .setNegativeButton("取消", null)
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
            Toast.makeText(this, "需要麦克风权限才能把声音送到电脑，授权后再点一次", Toast.LENGTH_LONG).show()
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (overlayScan.visibility == View.VISIBLE) startScanner()
        } else if (requestCode == REQ_CAMERA) {
            tvScanHint.text = "需要相机权限才能扫码——可以用下方「输入配对码」"
            tvScanHint.setTextColor(error)
        } else if (requestCode == REQ_BLE) {
            // nearby-devices permission decided: (re)start BLE scan either way
            AudioEngine.restartDiscovery()
        }
    }

    override fun onDestroy() {
        // Activity-bound mode (后台运行 off): the bridge dies with the UI.
        if (isFinishing && !usingService && !AppSettings.bgServiceEnabled(this)) {
            AudioEngine.stop()
        }
        super.onDestroy()
    }

    // ---------------- lifecycle / polling ----------------

    override fun onResume() {
        super.onResume()
        AudioEngine.uiInForeground = true
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
            conn.isNotEmpty() -> { tvStatus.text = "${conn.size} 台推流中"; tvStatus.setTextColor(accent); viewStatusDot.backgroundTintList = ColorStateList.valueOf(accent) }
            onlineCount > 0 -> { tvStatus.text = "$onlineCount 台在线"; tvStatus.setTextColor(text2); viewStatusDot.backgroundTintList = ColorStateList.valueOf(accent) }
            else -> { tvStatus.text = "暂无在线"; tvStatus.setTextColor(text3); viewStatusDot.backgroundTintList = ColorStateList.valueOf(text3) }
        }

        // unified versioning: NEVER fail silently on a version mismatch
        for (v in views) {
            if (v.versionMismatch && v.pcAppVersion.isNotEmpty()) {
                val key = "${v.deviceId ?: v.ip}@${v.pcAppVersion}"
                if (shownVersionWarnings.add(key)) {
                    AlertDialog.Builder(this)
                        .setTitle("版本不一致")
                        .setMessage("版本不一致：电脑端为 v${v.pcAppVersion}，本机为 v${UpdateChecker.APP_VERSION}，请先更新电脑端")
                        .setPositiveButton("去检查更新") { _, _ -> checkUpdate(manual = true) }
                        .setNegativeButton("知道了", null)
                        .show()
                }
            }
        }

        // reconnect banner (legacy single-purpose) — kept for drop events
        val reconnecting = e.running && conn.isEmpty() && e.reconnectCount > 0 && views.any { it.online }
        bannerReconnect.visibility = if (reconnecting) View.VISIBLE else View.GONE
        if (reconnecting) bannerReconnect.text = "连接中断，正在重连…（第 ${e.reconnectCount} 次）· 网络恢复后会自动继续推流"

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
                tvHeroEyebrow.text = "● LIVE · 正在推流"
                tvHeroEyebrow.setTextColor(accent)
                tvHeroTitle.text = "已连接 ${conn.size} 台电脑"
                tvHeroSub.text = conn.joinToString("、") { it.name }
            }
            e.userPaused -> {
                tvHeroEyebrow.text = "已暂停"
                tvHeroEyebrow.setTextColor(warn)
                tvHeroTitle.text = "声桥已暂停"
                tvHeroSub.text = "点下方「继续连接」或通知里的「继续」恢复"
            }
            e.running -> {
                tvHeroEyebrow.text = "SEARCHING"
                tvHeroEyebrow.setTextColor(warn)
                tvHeroTitle.text = if (onlineCount > 0) "$onlineCount 台在线 · 未推流" else "正在寻找你的电脑…"
                tvHeroSub.text = "配对过的电脑一出现就会自动连接"
            }
            else -> {
                tvHeroEyebrow.text = "AIRBRIDGE"
                tvHeroEyebrow.setTextColor(text3)
                tvHeroTitle.text = "还没开始"
                tvHeroSub.text = "点下方「开始搜索」，让手机开始监听电脑的出现"
            }
        }

        // latency badge = worst connected RTT (§1)
        val rtt = e.worstRttMs
        when {
            conn.isEmpty() -> { tvLatencyBadge.text = "-- ms"; tvLatencyBadge.setBackgroundResource(R.drawable.bg_badge_idle); tvLatencyBadge.setTextColor(text2) }
            rtt < 0 -> { tvLatencyBadge.text = "测速中…"; tvLatencyBadge.setBackgroundResource(R.drawable.bg_badge_idle); tvLatencyBadge.setTextColor(text2) }
            rtt < 30 -> { tvLatencyBadge.text = String.format("%.0f ms · 流畅", rtt); tvLatencyBadge.setBackgroundResource(R.drawable.bg_badge_good); tvLatencyBadge.setTextColor(accent) }
            rtt <= 80 -> { tvLatencyBadge.text = String.format("%.0f ms · 一般", rtt); tvLatencyBadge.setBackgroundResource(R.drawable.bg_badge_warn); tvLatencyBadge.setTextColor(warn) }
            else -> { tvLatencyBadge.text = String.format("%.0f ms · 偏高", rtt); tvLatencyBadge.setBackgroundResource(R.drawable.bg_badge_bad); tvLatencyBadge.setTextColor(error) }
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
                chipRate.text = "${e.sampleRateHz / 1000} kHz · 已自动优化"; chipRate.setTextColor(warn)
            }
            tvStats.text = String.format(
                "麦克风 → %d 台 · 扬声器 ← %d 台混音 · 丢包(最差) %.1f%% · 抖动 %.0f ms · 缓冲 %d ms",
                conn.count { it.micSend }, conn.count { it.speakerRecv },
                e.worstLossPct, e.worstJitterMs, e.displayBufferMs
            )
            tvMicState.text = if (e.micEnabled) "● 正在发送 · ${e.sampleRateHz / 1000}kHz" else "已暂停"
            tvMicState.setTextColor(if (e.micEnabled) accent else text3)
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
            }
            tvSpkState.text = if (e.speakerEnabled) "● 正在播放（混音）" else "已暂停"
            tvSpkState.setTextColor(if (e.speakerEnabled) sky else text3)
            // loop-guard note (defect 5c): visible for a few seconds after a block
            tvLoopGuardNote.visibility = if (e.loopGuardNoteActive) View.VISIBLE else View.GONE
            val elapsed = (now - connectedSinceMs) / 1000
            tvElapsed.text = String.format("已推流 %02d:%02d · 断线会自动重连", elapsed / 60, elapsed % 60)
        } else {
            tvElapsed.text = "已配对的电脑出现在同一网络时，会自动连接，无需打开 App 点按"
        }

        // re-entry prompt banner (v2.1, mode=每次询问): engine holds the ask
        val ask = e.pendingReturnAsk
        if (ask != null) {
            tvReturnAskText.text = "${ask.name} 回来了，要连接吗？"
            bannerReturnAsk.visibility = View.VISIBLE
        } else if (bannerReturnAsk.visibility == View.VISIBLE) {
            bannerReturnAsk.visibility = View.GONE
        }

        // headset banner (headset inheritance): a headset appeared mid-session
        val hsName = e.pendingHeadsetName
        if (hsName != null) {
            tvHeadsetText.text = if (e.outputDeviceSel == "auto") "🎧 检测到耳机〈$hsName〉（自动模式已跟随系统，可固定切换）"
                else "🎧 检测到耳机〈$hsName〉"
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
                btnStart.text = "开始搜索"
                btnStart.setBackgroundResource(R.drawable.btn_primary)
                btnStart.setTextColor(onAccent)
            }
            e.userPaused -> {
                btnStart.visibility = View.VISIBLE
                btnStart.text = "继续连接"
                btnStart.setBackgroundResource(R.drawable.btn_primary)
                btnStart.setTextColor(onAccent)
            }
            conn.size >= 2 -> {
                btnStart.visibility = View.VISIBLE
                btnStart.text = "全部停止"
                btnStart.setBackgroundResource(R.drawable.btn_stop)
                btnStart.setTextColor(error)
            }
            else -> btnStart.visibility = View.GONE
        }

        // device cards (rebuild only on signature change)
        val sig = views.joinToString("|") { v ->
            "${v.deviceId}/${v.ip}/${v.paired}/${v.online}/${v.connected}/${(v.rttMs / 5).toInt()}/${v.autoConnect}/${v.micSend}/${v.speakerRecv}/${(v.speakerVolume * 20).toInt()}/${v.muted}/${v.autoPaused}/${v.justAutoConnected}/${v.name}"
        } + "#flash$flashDeviceId"
        if (sig != lastCardSignature) {
            lastCardSignature = sig
            rebuildDeviceCards(views, now)
        }
        if (e.running && views.isEmpty() && now - searchStartMs > 5000) {
            tvNoDevice.visibility = View.VISIBLE
            tvNoDevice.text = "还没找到电脑\n确保电脑端已打开，且与手机连着同一个 WiFi；或点「配对新电脑」看附近列表。"
        } else {
            tvNoDevice.visibility = View.GONE
        }

        if (overlayDiscover.visibility == View.VISIBLE) refreshDiscoverSheet(views)
        pollPairingProgress(now)
    }

    /** Honest echo-layer status, shared by the channel card and Settings. */
    private fun echoStatusText(): String {
        val e = AudioEngine
        if (!e.echoSuppressionEnabled) return "已关闭（啸叫保护仍在工作）"
        val l1 = if (e.fxAecActive) "系统AEC已启用" else "系统AEC不可用"
        val l2 = when {
            e.nlmsActive -> "NLMS 已锁定 · 回声抵消 ${String.format("%.0f", e.nlmsErleDb)} dB"
            e.nlmsBulkDelay >= 0 -> "NLMS 已锁定 · 测量中"
            else -> "NLMS 锁定中…"
        }
        var s = "$l1 · $l2"
        if (e.headsetOutputActive) s += " · 耳机模式 · 回声风险低（抑制已放松）"
        if (e.doubleTalkActive) s += " · 双讲保护中（人声优先）"
        if (e.echoDuckingActive) s += " · 残留回声压制中"
        if (e.howlGainDb < 0f) s += " · 啸叫保护 ${e.howlGainDb.toInt()} dB"
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

    private fun rebuildDeviceCards(views: List<AudioEngine.DeviceView>, now: Long) {
        deviceContainer.removeAllViews()
        val paired = views.filter { it.paired }
        val fresh = views.filter { !it.paired }
        if (paired.isNotEmpty()) {
            deviceContainer.addView(sectionTitle("我的电脑"))
            for (v in paired) deviceContainer.addView(buildPairedCard(v, now))
        }
        if (fresh.isNotEmpty()) {
            deviceContainer.addView(sectionTitle("新设备"))
            for (v in fresh) deviceContainer.addView(buildUnpairedRow(v))
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
            v.versionMismatch || partnerVersionDiffers(v) -> nameRow.addView(badgeView("电脑端版本不一致", R.drawable.bg_badge_bad, error))
            v.connected && v.rttMs >= 0 -> {
                val (bg, col, word) = when {
                    v.rttMs < 30 -> Triple(R.drawable.bg_badge_good, accent, "流畅")
                    v.rttMs <= 80 -> Triple(R.drawable.bg_badge_warn, warn, "一般")
                    else -> Triple(R.drawable.bg_badge_bad, error, "偏高")
                }
                nameRow.addView(badgeView(String.format("%.0f ms · %s", v.rttMs, word), bg, col))
            }
            v.connected -> nameRow.addView(badgeView("已连接", R.drawable.bg_badge_idle, text2))
            v.autoPaused -> nameRow.addView(badgeView("自动连接失败 · 点按重试", R.drawable.bg_badge_bad, error))
            v.online -> nameRow.addView(badgeView("在线 · 未连接", R.drawable.bg_badge_idle, text2))
            else -> nameRow.addView(badgeView("离线", R.drawable.bg_chip, text2))
        }
        header.addView(nameRow)
        val sub = TextView(this)
        sub.text = "${v.os.ifEmpty { "电脑" }} · ${v.ip}" + if (v.pcAppVersion.isNotEmpty()) " · 电脑端 v${v.pcAppVersion}" else ""
        sub.setTextColor(text2); sub.textSize = 12f; sub.typeface = android.graphics.Typeface.MONOSPACE
        header.addView(sub)
        card.addView(header)

        // auto-connect row — NEVER dimmed/disabled, even offline (§3.3 hard rule)
        val autoRow = LinearLayout(this); autoRow.orientation = LinearLayout.HORIZONTAL; autoRow.gravity = Gravity.CENTER_VERTICAL
        val alp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); alp.topMargin = dp(8)
        autoRow.layoutParams = alp
        val autoLabel = TextView(this)
        if (v.justAutoConnected && v.connected) {
            autoLabel.text = "● 已自动连接 · 刚刚"
            autoLabel.setTextColor(accent)
        } else {
            autoLabel.text = "自动连接"
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
        channels.addView(switchRow("麦克风 → 这台电脑", v.micSend, accent, accentDim) { c ->
            v.deviceId?.let { AudioEngine.setMicSend(it, c) }
        })
        channels.addView(switchRow("电脑声音 → 手机扬声器", v.speakerRecv, sky, skyDim) { c ->
            v.deviceId?.let { AudioEngine.setSpeakerRecv(it, c) }
        })

        // per-device volume + mute
        val volRow = LinearLayout(this); volRow.orientation = LinearLayout.HORIZONTAL; volRow.gravity = Gravity.CENTER_VERTICAL
        val vlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); vlp.topMargin = dp(4)
        volRow.layoutParams = vlp
        val volLabel = TextView(this); volLabel.text = "这台的音量"; volLabel.setTextColor(text2); volLabel.textSize = 13f
        volRow.addView(volLabel)
        val seek = SeekBar(this)
        seek.max = 100
        seek.progress = (v.speakerVolume * 100).toInt()
        seek.isEnabled = v.online && v.speakerRecv
        seek.progressTintList = ColorStateList.valueOf(if (v.muted) text3 else sky)
        seek.thumbTintList = ColorStateList.valueOf(if (v.muted) text3 else sky)
        val slp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT); slp.weight = 1f; slp.marginStart = dp(8)
        seek.layoutParams = slp
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) v.deviceId?.let { AudioEngine.setSpeakerVolume(it, progress / 100f) }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { lastCardSignature = "" }
        })
        volRow.addView(seek)
        val volText = TextView(this)
        volText.text = if (v.muted) "静音" else "${(v.speakerVolume * 100).toInt()}%"
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
        card.addView(channels)

        // footer action (§2.3)
        val footer = LinearLayout(this); footer.orientation = LinearLayout.HORIZONTAL; footer.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        val flp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); flp.topMargin = dp(8)
        footer.layoutParams = flp
        footer.alpha = dimAlpha
        when {
            v.connected -> {
                val b = Button(this)
                b.text = "停止"; b.setTextColor(error); b.setBackgroundResource(R.drawable.btn_secondary)
                b.setOnClickListener { AudioEngine.disconnectDevice(v.ip); lastCardSignature = "" }
                footer.addView(b)
            }
            v.online -> {
                val b = Button(this)
                b.text = "连接"; b.setTextColor(accent); b.setBackgroundResource(R.drawable.btn_secondary)
                b.setOnClickListener {
                    if (v.autoPaused) AudioEngine.retryAutoConnect(v.ip) else AudioEngine.connectDevice(v.ip)
                    lastCardSignature = ""
                }
                footer.addView(b)
            }
            else -> {
                val t = TextView(this)
                t.text = "上次连接 ${formatLastSeen(v.lastSeenMs)}"
                t.setTextColor(text3); t.textSize = 12f; t.typeface = android.graphics.Typeface.MONOSPACE
                footer.addView(t)
            }
        }
        card.addView(footer)

        if (v.autoPaused) {
            card.setOnClickListener { AudioEngine.retryAutoConnect(v.ip); lastCardSignature = "" }
        }
        card.setOnLongClickListener { anchor ->
            showDeviceMenu(anchor, v)
            true
        }
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
        val sub = TextView(this); sub.text = "${v.os.ifEmpty { "电脑" }} · 未配对 · ${v.ip}"; sub.setTextColor(text2); sub.textSize = 12f
        col.addView(sub)
        row.addView(col)
        val pairBtn = Button(this)
        pairBtn.text = "配对"
        pairBtn.setTextColor(sky)
        pairBtn.setBackgroundResource(R.drawable.bg_btn_outline_sky)
        pairBtn.setOnClickListener { openScan(v) }
        row.addView(pairBtn)
        row.setOnClickListener { openScan(v) }
        return row
    }

    private fun showDeviceMenu(anchor: View, v: AudioEngine.DeviceView) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "重命名")
        menu.menu.add(0, 2, 0, "查看配对信息")
        menu.menu.add(0, 3, 0, "忘记这台电脑")
        menu.setOnMenuItemClickListener { item ->
            val id = v.deviceId ?: return@setOnMenuItemClickListener false
            when (item.itemId) {
                1 -> {
                    val et = EditText(this)
                    et.setText(v.name)
                    et.hint = "给这台电脑起个名字"
                    AlertDialog.Builder(this)
                        .setTitle("重命名")
                        .setView(et)
                        .setPositiveButton("保存") { _, _ -> AudioEngine.renameDevice(id, et.text.toString()); lastCardSignature = "" }
                        .setNegativeButton("取消", null)
                        .show()
                }
                2 -> {
                    AlertDialog.Builder(this)
                        .setTitle("配对信息")
                        .setMessage(AudioEngine.pairingInfo(id) ?: "无")
                        .setPositiveButton("好", null)
                        .show()
                }
                3 -> {
                    AlertDialog.Builder(this)
                        .setTitle("忘记这台电脑？")
                        .setMessage("忘记后需要重新配对才能再自动连接。")
                        .setPositiveButton("忘记") { _, _ ->
                            AudioEngine.disconnectDevice(v.ip)
                            AudioEngine.forgetDevice(id)
                            lastCardSignature = ""
                        }
                        .setNegativeButton("取消", null)
                        .show()
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
        if (ms <= 0) return "从未连接"
        val then = Calendar.getInstance().apply { timeInMillis = ms }
        val nowCal = Calendar.getInstance()
        val hm = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
        val sameDay = then.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR) && then.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)
        val yest = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val isYest = then.get(Calendar.YEAR) == yest.get(Calendar.YEAR) && then.get(Calendar.DAY_OF_YEAR) == yest.get(Calendar.DAY_OF_YEAR)
        return when {
            sameDay -> "今天 $hm"
            isYest -> "昨天 $hm"
            else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
        }
    }

    // ---------------- pairing overlays ----------------

    private fun ensureServiceRunning(): Boolean {
        if (!AudioEngine.running) {
            if (!ensureAudioPermissions()) return false
            startBridge()
            Toast.makeText(this, "声桥已启动，正在配对…", Toast.LENGTH_SHORT).show()
        }
        return true
    }

    private fun openScan(target: AudioEngine.DeviceView?) {
        pairTarget = target
        overlayCode.visibility = View.GONE
        overlayConfirm.visibility = View.GONE
        overlayScan.visibility = View.VISIBLE
        tvScanHint.text = "对准电脑屏幕上的二维码"
        tvScanHint.setTextColor(text1)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startScanner()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            tvScanHint.text = "需要相机权限才能扫码——授权后重试，或用下方「输入配对码」"
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
        qrScanner?.stop()
        qrScanner = null
        overlayScan.visibility = View.GONE
    }

    private fun onQrResult(text: String) {
        val o = try { JSONObject(text) } catch (_: Exception) { null }
        val deviceId = o?.optString("deviceId") ?: ""
        val token = o?.optString("token") ?: ""
        val ip = o?.optString("ip") ?: ""
        if (o == null || o.optString("app") != "AirBridge" || deviceId.isEmpty() || token.isEmpty() || ip.isEmpty()) {
            tvScanHint.text = "这不是声桥的配对二维码"
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
            Toast.makeText(this, "扫到的不是这台电脑，已按扫到的设备配对", Toast.LENGTH_LONG).show()
        }
    }

    private fun openCode(target: AudioEngine.DeviceView?) {
        pairTarget = target
        closeScan()
        overlayConfirm.visibility = View.GONE
        overlayCode.visibility = View.VISIBLE
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
        tvCodeError.text = "正在查找这台电脑…"
    }

    private fun pollPairingProgress(now: Long) {
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
                overlayCode.visibility = View.GONE
                overlayScan.visibility = View.GONE
                overlayDiscover.visibility = View.GONE
                tvBannerAutoText.text = "这台电脑已经配对过了"
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
            if (overlayCode.visibility == View.VISIBLE) {
                tvCodeError.visibility = View.VISIBLE
                tvCodeError.setTextColor(error)
                tvCodeError.text = "配对码不存在或已过期，请核对后重试"
            } else {
                Toast.makeText(this, "配对超时，电脑那边没有确认，请重试", Toast.LENGTH_LONG).show()
            }
        }
        if (AudioEngine.codeSearch == null && pairSubmittedAtMs > 0 && awaitingIp == null && pending == null && !confirmShown && overlayCode.visibility == View.VISIBLE && now - pairSubmittedAtMs > 18000) {
            pairSubmittedAtMs = 0L
        }
    }

    private fun showConfirm(s: AudioEngine.Session) {
        confirmShown = true
        confirmIp = s.ip
        overlayScan.visibility = View.GONE
        overlayCode.visibility = View.GONE
        overlayDiscover.visibility = View.GONE
        overlayConfirm.visibility = View.VISIBLE
        findViewById<TextView>(R.id.tvConfirmName).text = "🖥  ${s.name}"
        findViewById<TextView>(R.id.tvConfirmSub).text = "${s.os.ifEmpty { "电脑" }} · ${s.ip}"
        val code = s.pendingVerifyCode ?: "------"
        findViewById<TextView>(R.id.tvConfirmVerify).text = code.toCharArray().joinToString(" ")
        findViewById<TextView>(R.id.tvConfirmError).visibility = View.GONE
        findViewById<Switch>(R.id.swAutoConfirm).isChecked = true
    }

    private fun wirePairingOverlays() {
        findViewById<TextView>(R.id.btnScanBack).setOnClickListener { closeScan() }
        findViewById<TextView>(R.id.btnScanToCode).setOnClickListener { openCode(pairTarget) }
        findViewById<TextView>(R.id.btnTorch).setOnClickListener { v ->
            val on = !(qrScanner?.torchOn ?: false)
            qrScanner?.setTorch(on)
            (v as TextView).setTextColor(if (on) accent else text2)
        }
        findViewById<TextView>(R.id.btnCodeBack).setOnClickListener { overlayCode.visibility = View.GONE }
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
        findViewById<Button>(R.id.btnConfirmPair).setOnClickListener {
            val ip = confirmIp ?: return@setOnClickListener
            val auto = findViewById<Switch>(R.id.swAutoConfirm).isChecked
            val sess = AudioEngine.sessions[ip]
            val name = sess?.name ?: "电脑"
            val did = sess?.deviceId
            AudioEngine.confirmPairing(ip, auto)
            overlayConfirm.visibility = View.GONE
            confirmShown = false
            awaitingIp = null
            pairSubmittedAtMs = 0L
            if (did != null) { flashDeviceId = did; flashUntilMs = System.currentTimeMillis() + 1000 }
            tvBannerAutoText.text = "✓ 已配对 $name，自动连接已${if (auto) "开启" else "关闭"}"
            bannerIps = emptyList()
            bannerAuto.visibility = View.VISIBLE
            bannerShownAtMs = System.currentTimeMillis()
            lastCardSignature = ""
        }
        findViewById<TextView>(R.id.btnConfirmCancel).setOnClickListener {
            confirmIp?.let { AudioEngine.cancelPairing(it) }
            overlayConfirm.visibility = View.GONE
            confirmShown = false
            awaitingIp = null
            pairSubmittedAtMs = 0L
        }
    }

    // ---------------- settings (v2.1) ----------------

    private fun openSettings() {
        overlaySettings.visibility = View.VISIBLE
        refreshSettingsStates()
    }

    private fun refreshSettingsStates() {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val whitelisted = pm.isIgnoringBatteryOptimizations(packageName)
        findViewById<TextView>(R.id.tvBatteryState).text =
            if (whitelisted) "电池优化：已关闭，声桥在后台不会被系统杀掉 ✓"
            else "电池优化：未关闭——后台运行可能被系统杀掉，建议关闭"
        findViewById<TextView>(R.id.tvEchoLayers).text = echoStatusText()
        // actual (confirmed) audio devices — not just the selection
        val e = AudioEngine
        val fb = e.routeFallbackNote
        findViewById<TextView>(R.id.tvOutputRouteState).text =
            "当前输出：${e.outputRouteText}" +
                (if (e.headsetOutputActive) " · 耳机模式 · 回声风险低" else "") +
                (if (fb != null) " · $fb" else "")
        findViewById<TextView>(R.id.tvInputDeviceState).text = "当前输入：${e.actualInputText()}"
    }

    /** Build the output/input device radio lists from the LIVE device set. */
    private fun buildDevicePickers() {
        val p = AppSettings.prefs(this)
        val outSel = p.getString(AppSettings.KEY_OUTPUT_DEVICE, "auto") ?: "auto"
        val rgOut = findViewById<android.widget.RadioGroup>(R.id.rgOutputRoute)
        rgOut.setOnCheckedChangeListener(null)
        rgOut.removeAllViews()
        addDeviceRadio(rgOut, "auto", "自动（跟随系统：有耳机用耳机，否则扬声器）", outSel == "auto")
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
        addDeviceRadio(rgIn, "auto", "自动（跟随系统）", inSel == "auto")
        addDeviceRadio(rgIn, "builtin", "手机麦克风", inSel == "builtin")
        for (d in AudioEngine.inputDevices()) {
            val key = "type:${d.type}|${d.name}"
            addDeviceRadio(rgIn, key, "${d.name} 麦克风", inSel == key)
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
        findViewById<TextView>(R.id.btnSettingsBack).setOnClickListener { overlaySettings.visibility = View.GONE }
        val p = AppSettings.prefs(this)
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
                    Toast.makeText(this, "已切换为后台运行：关掉 App 声桥也继续工作", Toast.LENGTH_SHORT).show()
                }
                !checked && usingService -> {
                    BridgeService.stopEngineOnDestroy = false
                    stopService(Intent(this, BridgeService::class.java))
                    usingService = false
                    Toast.makeText(this, "已切换为仅前台运行：关闭 App 后声桥会停止", Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, "麦克风模式将在下次连接时生效", Toast.LENGTH_SHORT).show()
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
        // device pickers (headset inheritance)
        buildDevicePickers()
        // headset banner actions
        findViewById<TextView>(R.id.btnHeadsetSwitch).setOnClickListener { AudioEngine.acceptHeadsetOffer() }
        findViewById<TextView>(R.id.btnHeadsetDismiss).setOnClickListener { AudioEngine.dismissHeadsetOffer() }
    }

    // ---------------- nearby discovery sheet (v2.1, PRIMARY pairing entry) ----------------

    private fun openDiscover() {
        overlaySettings.visibility = View.GONE
        overlayCode.visibility = View.GONE
        overlayConfirm.visibility = View.GONE
        closeScan()
        overlayDiscover.visibility = View.VISIBLE
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
        findViewById<TextView>(R.id.btnDiscoverBack).setOnClickListener { overlayDiscover.visibility = View.GONE }
        findViewById<TextView>(R.id.btnDiscoverScan).setOnClickListener { overlayDiscover.visibility = View.GONE; openScan(null) }
        findViewById<TextView>(R.id.btnDiscoverCode).setOnClickListener { overlayDiscover.visibility = View.GONE; openCode(null) }
        findViewById<TextView>(R.id.btnDiscoverManual).setOnClickListener { overlayDiscover.visibility = View.GONE; showManualIpDialog() }
    }

    private fun refreshDiscoverSheet(views: List<AudioEngine.DeviceView>) {
        val e = AudioEngine
        val nearby = e.nearbyDevices()
        tvDiscoverStatus.text = "探寻中：局域网广播 · ${e.mdnsStatusText} · ${e.bleStatusText}"
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
        val name = view?.name ?: n.name.ifEmpty { n.deviceId ?: n.bleAddress ?: "未知电脑" }
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
            when (s) { "LAN" -> "局域网"; "MDNS" -> "mDNS"; "BLE" -> "蓝牙"; else -> s }
        }
        val prox = n.rssi?.let { r -> when { r >= -60 -> "很近"; r >= -75 -> "附近"; else -> "较远" } }
        val sub = TextView(this)
        sub.text = buildString {
            append(view?.os ?: n.os.ifEmpty { "电脑" })
            append(" · ")
            append(n.ip ?: "蓝牙发现 · 等待网络地址")
            append(" · ").append(srcText)
            if (prox != null) append(" · ").append(prox)
            if (paired) append(" · 已配对")
        }
        sub.setTextColor(text2); sub.textSize = 12f
        col.addView(sub)
        row.addView(col)
        val btn = Button(this)
        when {
            connected -> { btn.text = "已连接"; btn.isEnabled = false }
            n.ip == null -> { btn.text = "等待地址"; btn.isEnabled = false }
            paired -> {
                btn.text = "连接"
                btn.setTextColor(accent); btn.setBackgroundResource(R.drawable.btn_secondary)
                btn.setOnClickListener { AudioEngine.connectDevice(n.ip!!); overlayDiscover.visibility = View.GONE }
            }
            else -> {
                btn.text = "配对"
                btn.setTextColor(sky); btn.setBackgroundResource(R.drawable.bg_btn_outline_sky)
                btn.setOnClickListener {
                    pairTarget = null
                    AudioEngine.pairAndConnect(n.ip!!, null)
                    awaitingIp = n.ip
                    pairSubmittedAtMs = System.currentTimeMillis()
                    overlayDiscover.visibility = View.GONE
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
    }
}
