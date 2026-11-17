package com.qi.phoneaudiobridge

import android.content.Context

/**
 * v2.1 settings system: ONE SharedPreferences file ("airbridge_settings")
 * shared by the Settings screen (MainActivity), the engine (reconnect
 * behaviour / notifications / echo toggle) and the update checker
 * (update-address field). Every key here is wired to real behaviour —
 * no decorative toggles.
 */
object AppSettings {
    const val PREFS = "airbridge_settings"

    /** 重连模式: AudioEngine.RECONNECT_ASK(0, default) / _AUTO(1) / _NEVER(2). */
    const val KEY_RECONNECT_MODE = "reconnect_mode"
    /** 后台运行: foreground service keeps the bridge alive (default true).
     *  False = engine runs only while the Activity is alive. */
    const val KEY_BG_SERVICE = "bg_service_enabled"
    /** 回声抑制 master toggle (layers 2/2b; howl guard always runs). */
    const val KEY_ECHO = "echo_enabled"
    /** 事件通知 (auto-connect / re-entry prompts). The foreground-service
     *  notification itself is required by Android and cannot be hidden. */
    const val KEY_NOTIFICATIONS = "notifications_enabled"
    /** Online-update manifest URL (editable update address). */
    const val KEY_UPDATE_URL = "update_url"
    /** Newest windows version seen in the last manifest fetch (display). */
    const val KEY_PARTNER_LATEST = "partner_latest_version"
    /** 输出路由: 0 = 扬声器 (default), 1 = 听筒, 2 = 自动 (headset if present). */
    const val KEY_OUTPUT_ROUTE = "output_route"
    /** 麦克风灵敏度: 0 = 标准, 1 = 高灵敏 (post-chain digital gain; platform AGC off). */
    const val KEY_MIC_SENSITIVITY = "mic_sensitivity"
    /** 平台降噪: 0 = 标准 (NS on), 1 = 轻柔 (NS off — protects quiet speech, defect 3). */
    const val KEY_NS_MODE = "ns_mode"
    /** 输出设备选择 (headset inheritance): "auto" | "type:<type>|<productName>". */
    const val KEY_OUTPUT_DEVICE = "output_device"
    /** 输入设备选择: "auto" | "builtin" | "type:<type>|<productName>". */
    const val KEY_INPUT_DEVICE = "input_device"
    /** 麦克风模式: 0 = 语音通话 (default), 1 = 高清拾音, 2 = 自动. Session-boundary only. */
    const val KEY_MIC_MODE = "mic_mode"

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun reconnectMode(ctx: Context): Int =
        prefs(ctx).getInt(KEY_RECONNECT_MODE, AudioEngine.RECONNECT_ASK)

    fun bgServiceEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_BG_SERVICE, true)

    fun updateUrl(ctx: Context): String =
        prefs(ctx).getString(KEY_UPDATE_URL, UpdateChecker.DEFAULT_UPDATE_MANIFEST_URL)
            ?: UpdateChecker.DEFAULT_UPDATE_MANIFEST_URL
}
