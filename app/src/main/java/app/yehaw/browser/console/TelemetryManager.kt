package app.yehaw.browser.console

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * Anonymous, minimal telemetry. Sends only: a random install UUID, app version, Android version,
 * total RAM and the device's region. No browsing data, no advertising id, no IP is stored server-side.
 *
 * In regions that require opt-in (EEA/UK/CH) nothing is sent until [setConsent] is called with true.
 * [forgetMe] erases the server-side record and the local id.
 */
object TelemetryManager {
    private const val PREFS = "yeehaw_console"
    private const val KEY_ID = "device_id"
    private const val KEY_CONSENT = "telemetry_consent" // 1 granted, -1 denied, absent = not asked
    private const val KEY_LAST_BOOT = "last_boot_ms"

    @Volatile private var appContext: Context? = null

    /** Call once from MainActivity.onCreate. Non-blocking: all network work happens on a background thread. */
    fun init(context: Context) {
        appContext = context.applicationContext
        sendBootIfAllowed(retryOnFailure = true)
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun country(): String = Locale.getDefault().country.uppercase(Locale.ROOT)

    fun isConsentRequired(): Boolean = country() in BackendConfig.CONSENT_REQUIRED_COUNTRIES

    /** True when the user said yes, or when no opt-in is required in their region and they did not opt out. */
    fun hasConsent(context: Context): Boolean = when (prefs(context).getInt(KEY_CONSENT, 0)) {
        1 -> true
        -1 -> false
        else -> !isConsentRequired()
    }

    fun setConsent(context: Context, granted: Boolean) {
        appContext = context.applicationContext
        prefs(context).edit().putInt(KEY_CONSENT, if (granted) 1 else -1).apply()
        if (granted) sendBootIfAllowed(retryOnFailure = true)
    }

    /** The anonymous id, or null when the user has not consented (the id is not even created then). */
    fun deviceId(context: Context): String? {
        if (!hasConsent(context)) return null
        val p = prefs(context)
        p.getString(KEY_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        p.edit().putString(KEY_ID, id).apply()
        return id
    }

    /**
     * Install id used only to verify a purchase with the server (contract necessity, not analytics).
     * Same id as telemetry; created on first use even without analytics consent, and sent nowhere else.
     */
    fun entitlementId(context: Context): String {
        val p = prefs(context)
        p.getString(KEY_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        p.edit().putString(KEY_ID, id).apply()
        return id
    }

    /** "Forget me": deletes server-side records for this install, removes the local id and opts out. */
    fun forgetMe(context: Context, onDone: ((Boolean) -> Unit)? = null) {
        val c = context.applicationContext
        val id = prefs(c).getString(KEY_ID, null)
        BackendHttp.async {
            val ok = id == null || BackendHttp.postJson(
                BackendConfig.PATH_FORGET, JSONObject().put("device_id", id).toString()
            ) in 200..299
            if (ok) prefs(c).edit().remove(KEY_ID).remove(KEY_LAST_BOOT).putInt(KEY_CONSENT, -1).apply()
            onDone?.invoke(ok)
        }
    }

    private fun sendBootIfAllowed(retryOnFailure: Boolean) {
        val c = appContext ?: return
        if (!hasConsent(c)) return
        val p = prefs(c)
        val now = System.currentTimeMillis()
        if (now - p.getLong(KEY_LAST_BOOT, 0L) < BackendConfig.BOOT_DEBOUNCE_MS) return
        val id = deviceId(c) ?: return
        val payload = try {
            JSONObject()
                .put("device_id", id)
                .put("app_version", appVersion(c))
                .put("android_os_ver", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                .put("ram_mb", totalRamMb(c))
                .put("country_locale", country())
                .toString()
        } catch (_: Throwable) {
            return
        }
        BackendHttp.async {
            val code = BackendHttp.postJson(BackendConfig.PATH_BOOT, payload)
            if (code in 200..299) {
                p.edit().putLong(KEY_LAST_BOOT, System.currentTimeMillis()).apply()
            } else if (retryOnFailure) {
                BackendHttp.schedule(30_000) { sendBootIfAllowed(retryOnFailure = false) }
            }
        }
    }

    private fun appVersion(c: Context): String = try {
        (c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "unknown").take(32)
    } catch (_: Throwable) {
        "unknown"
    }

    private fun totalRamMb(c: Context): Int {
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return (info.totalMem / (1024L * 1024L)).toInt().coerceIn(256, 131072)
    }

    /** Counts QR displays and shares per channel (no contacts, no message content). Dropped without consent. */
    internal fun sendShareEvent(action: String, channel: String, refCode: String?) {
        val c = appContext ?: return
        val id = deviceId(c) ?: return
        val json = try {
            JSONObject().put("device_id", id).put("action", action).put("channel", channel).apply {
                if (refCode != null) put("ref_code", refCode)
            }.toString()
        } catch (_: Throwable) {
            return
        }
        BackendHttp.async { BackendHttp.postJson(BackendConfig.PATH_SHARE_EVENT, json) }
    }

    /** Used by the ad manager (not bundled in this build). Fire-and-forget; silently dropped without consent. */
    internal fun sendAdEvent(
        event: String, adFormat: String, adUnit: String?, network: String? = null,
        revenueMicros: Long = 0L, currency: String? = null,
    ) {
        val c = appContext ?: return
        val id = deviceId(c) ?: return
        val json = try {
            JSONObject().put("device_id", id).put("event", event).put("ad_format", adFormat).apply {
                if (adUnit != null) put("ad_unit", adUnit)
                if (network != null) put("network", network)
                if (event == "paid") put("revenue_micros", revenueMicros).put("currency", currency ?: "USD")
            }.toString()
        } catch (_: Throwable) {
            return
        }
        BackendHttp.async { BackendHttp.postJson(BackendConfig.PATH_AD_EVENT, json) }
    }
}
