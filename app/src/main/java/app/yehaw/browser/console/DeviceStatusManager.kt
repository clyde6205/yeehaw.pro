package app.yehaw.browser.console

import android.content.Context
import org.json.JSONObject

/**
 * Founder controls per device: blocked flag and update notices. The answer is signed with the same key as the
 * remote config; an unsigned or tampered answer is ignored. Never blocks on a failed check (fail open).
 */
object DeviceStatusManager {
    class Status(
        val blocked: Boolean, val blockedReason: String,
        val updateRequired: Boolean, val updateAvailable: Boolean,
        val latestVersionName: String, val updateUrl: String, val message: String,
    )

    private const val PREFS = "device_status"
    private const val MIN_INTERVAL_MS = 6L * 60 * 60 * 1000
    private val ALLOWED_UPDATE_HOSTS = setOf("play.google.com", "yeehaw.pro", "www.yeehaw.pro")

    /** Update links must be https on a host we control; anything else from the network is dropped. */
    fun safeUpdateUrl(url: String): String? = try {
        val u = java.net.URI(url)
        if (u.scheme == "https" && u.host in ALLOWED_UPDATE_HOSTS) url else null
    } catch (_: Throwable) {
        null
    }

    fun check(context: Context, versionCode: Int, onStatus: (Status) -> Unit) {
        val c = context.applicationContext
        val key = RemoteConfigManager.publicKey(c) ?: return
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - p.getLong("last_ms", 0L) < MIN_INTERVAL_MS) return
        val id = TelemetryManager.deviceId(c) ?: return
        BackendHttp.async {
            val r = BackendHttp.get("/v1/device/status?d=$id&vc=$versionCode") ?: return@async
            if (r.code != 200) return@async
            val sig = r.header("x-config-signature") ?: return@async
            if (!ConfigRules.verifySignature(key, r.body, sig)) return@async
            val o = JSONObject(String(r.body, Charsets.UTF_8))
            p.edit().putLong("last_ms", System.currentTimeMillis()).apply()
            val s = Status(
                blocked = o.optBoolean("blocked"), blockedReason = o.optString("blocked_reason").take(200),
                updateRequired = o.optBoolean("update_required"), updateAvailable = o.optBoolean("update_available"),
                latestVersionName = o.optString("latest_version_name").take(32),
                updateUrl = safeUpdateUrl(o.optString("update_url")) ?: "", message = o.optString("update_message").take(300),
            )
            android.os.Handler(android.os.Looper.getMainLooper()).post { onStatus(s) }
        }
    }
}
