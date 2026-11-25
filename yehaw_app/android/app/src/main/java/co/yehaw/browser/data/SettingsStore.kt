package co.yehaw.browser.data

import android.content.Context
import co.yehaw.browser.util.UrlUtils

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("yeehaw_prefs", Context.MODE_PRIVATE)

    var adBlockEnabled: Boolean
        get() = prefs.getBoolean("adblock", true)
        set(v) = prefs.edit().putBoolean("adblock", v).apply()

    var dataSaverEnabled: Boolean
        get() = prefs.getBoolean("datasaver", false)
        set(v) = prefs.edit().putBoolean("datasaver", v).apply()

    var textOnly: Boolean
        get() = prefs.getBoolean("textonly", false)
        set(v) = prefs.edit().putBoolean("textonly", v).apply()

    var blockAutoplay: Boolean
        get() = prefs.getBoolean("autoplay_block", true)
        set(v) = prefs.edit().putBoolean("autoplay_block", v).apply()

    var autoSleepTabs: Boolean
        get() = prefs.getBoolean("autosleep", true)
        set(v) = prefs.edit().putBoolean("autosleep", v).apply()

    /** Idle time before a background tab is serialized and its WebView destroyed. Default 3 minutes. */
    var suspendAfterMs: Long
        get() = prefs.getLong("suspend_after_ms", 180_000L)
        set(v) = prefs.edit().putLong("suspend_after_ms", v.coerceIn(30_000L, 3_600_000L)).apply()

    /** Image-proxy URL template with {url} and {q} placeholders. Empty = data-saver proxy off. */
    var imageProxyTemplate: String
        get() = prefs.getString("image_proxy", "") ?: ""
        set(v) = prefs.edit().putString("image_proxy", v.trim()).apply()

    var searchTemplate: String
        get() = prefs.getString("search_template", UrlUtils.DEFAULT_SEARCH) ?: UrlUtils.DEFAULT_SEARCH
        set(v) = prefs.edit().putString("search_template", v).apply()

    var onboardingShown: Boolean
        get() = prefs.getBoolean("onboarding_shown", false)
        set(v) = prefs.edit().putBoolean("onboarding_shown", v).apply()

    var blockedTotal: Long
        get() = prefs.getLong("blocked_total", 0L)
        set(v) = prefs.edit().putLong("blocked_total", v).apply()

    fun lastContainerFor(serviceId: String): String? = prefs.getString("svc_container_$serviceId", null)
    fun setLastContainerFor(serviceId: String, containerId: String) =
        prefs.edit().putString("svc_container_$serviceId", containerId).apply()
}
