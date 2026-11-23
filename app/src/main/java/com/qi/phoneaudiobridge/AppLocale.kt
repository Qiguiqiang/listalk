package com.qi.phoneaudiobridge

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Per-app language (board item #2): the persisted AppSettings.KEY_LANGUAGE
 * ("" = follow system / "zh" / "en") applied through the platform's own
 * mechanisms — no AndroidX in this project:
 * - API 33+: the framework per-app locale service (LocaleManager).
 *   setApplicationLocales persists the choice system-side and recreates
 *   running activities; contexts pick it up automatically, so [wrap]
 *   is a pass-through.
 * - API 26–32: attachBaseContext wrap — MainActivity / BridgeService
 *   wrap their base context with a Configuration carrying the chosen
 *   locale; changing the setting persists it and the caller recreates
 *   the activity so the wrap takes effect.
 */
object AppLocale {

    /** Wrap a base context for the persisted language (pre-33 path;
     * no-op on 33+ and when following the system). Safe to call before
     * attachBaseContext completes: reads SharedPreferences off [base]. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val lang = AppSettings.language(base)
        if (lang.isEmpty()) return base
        val config = Configuration(base.resources.configuration)
        config.setLocale(Locale.forLanguageTag(lang))
        return base.createConfigurationContext(config)
    }

    /** Persist + apply a new language choice. On 33+ the framework
     * recreates activities itself; below 33 the caller must recreate
     * the activity (the wrap in attachBaseContext does the rest). */
    fun apply(context: Context, lang: String) {
        AppSettings.setLanguage(context, lang)
        if (Build.VERSION.SDK_INT >= 33) {
            val lm = context.getSystemService(LocaleManager::class.java)
            lm?.applicationLocales =
                if (lang.isEmpty()) LocaleList.getEmptyLocaleList()
                else LocaleList.forLanguageTags(lang)
        }
    }
}
