package app.yehaw.browser

import android.content.Context
import android.content.res.Configuration
import android.view.View
import java.util.Locale

/**
 * In-app language selection with persistence. Both activities wrap their base context with [wrap], so the choice applies
 * to the browser window and the isolated private window alike (they run in different processes and each reads the saved
 * choice when it is created). "system" (default) follows the phone's language list, which gives correct fallback:
 * an unsupported phone language resolves to English through Android's normal resource matching.
 */
object LocaleHelper {
    const val SYSTEM = "system"
    private const val PREFS = "yeehaw_language"
    private const val KEY = "tag"

    /** Language tag to native name. Names are written in their own language so people can find theirs. */
    val SUPPORTED: List<Pair<String, String>> = listOf(
        "en" to "English",
        "hi" to "हिन्दी",
        "zh-CN" to "简体中文",
        "es" to "Español",
        "ar" to "العربية",
        "bn" to "বাংলা",
        "pt" to "Português",
        "ru" to "Русский",
        "ur" to "اردو",
        "id" to "Bahasa Indonesia",
        "fil" to "Filipino",
    )

    fun saved(context: Context): String = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM) ?: SYSTEM
    } catch (_: Exception) {
        SYSTEM
    }

    fun save(context: Context, tag: String) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).commit()
        } catch (_: Exception) {
            // Language choice is a convenience; failing to persist must never crash the browser.
        }
    }

    /** Returns [base] configured for the saved language (or [base] unchanged when following the system language). */
    fun wrap(base: Context): Context {
        val tag = saved(base)
        if (tag == SYSTEM || tag.isBlank() || Locale.forLanguageTag(tag).language.isEmpty()) {
            restoreSystemDefault(base)
            return base
        }
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    /**
     * Choosing "system default" after another language must give the process the phone's language back. Without this,
     * Locale.getDefault() (used by WebView for error pages and Accept-Language, and by number/date formatting) keeps
     * the previously chosen language until the process dies.
     */
    private fun restoreSystemDefault(base: Context) {
        try {
            val cfg = base.resources.configuration
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                val list = cfg.locales
                if (!list.isEmpty) { android.os.LocaleList.setDefault(list); Locale.setDefault(list[0]) }
            } else {
                @Suppress("DEPRECATION")
                cfg.locale?.let { Locale.setDefault(it) }
            }
        } catch (_: Exception) {
            // Never let a locale housekeeping problem stop the browser from starting.
        }
    }

    fun isRtl(context: Context): Boolean =
        context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
}
