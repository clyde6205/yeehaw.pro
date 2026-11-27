package app.yehaw.browser.console

import android.content.Context
import org.json.JSONObject

/**
 * Pro is granted only by a SIGNED server answer that says Google Play confirmed the subscription.
 * Offline grace: a verified entitlement keeps working for up to 7 days without re-checking.
 * No local flag can unlock Pro.
 */
object ProEntitlement {
    private const val PREFS = "pro_entitlement"
    private const val GRACE_MS = 7L * 24 * 60 * 60 * 1000
    private const val RECHECK_MS = 24L * 60 * 60 * 1000

    fun isPro(context: Context): Boolean =
        System.currentTimeMillis() < context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("valid_until", 0L)

    /** Pure rule, unit-testable: how long a positive answer is honoured. */
    fun validUntil(nowMs: Long, expiresAtMs: Long?): Long {
        val cap = nowMs + GRACE_MS
        return if (expiresAtMs != null && expiresAtMs > nowMs) minOf(expiresAtMs, cap) else cap
    }

    fun needsRecheck(context: Context): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getString("token", null) != null && System.currentTimeMillis() - p.getLong("checked_ms", 0L) > RECHECK_MS
    }

    fun storedToken(context: Context): Pair<String, String>? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val t = p.getString("token", null) ?: return null
        return (p.getString("product", "") ?: "") to t
    }

    /** result: true = verified Pro, false = server says not Pro, null = could not be verified now (try again later). */
    fun verify(context: Context, productId: String, purchaseToken: String, onResult: (Boolean?) -> Unit) {
        val c = context.applicationContext
        val key = RemoteConfigManager.publicKey(c)
        val id = TelemetryManager.entitlementId(c)
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        BackendHttp.async {
            val body = JSONObject().put("device_id", id).put("product_id", productId).put("purchase_token", purchaseToken).toString()
            val r = BackendHttp.postJsonForResponse("/v1/pro/verify", body)
            val answer: Boolean? = try {
                if (r == null || r.code != 200 || key == null) null
                else {
                    val sig = r.header("x-config-signature")
                    if (sig == null || !ConfigRules.verifySignature(key, r.body, sig)) null
                    else {
                        val o = JSONObject(String(r.body, Charsets.UTF_8))
                        if (o.optString("device_id") != id) null
                        else if (o.optBoolean("pro")) true else false
                    }
                }
            } catch (_: Throwable) {
                null
            }
            val now = System.currentTimeMillis()
            if (answer == true) {
                val exp = try { parseIsoUtc(JSONObject(String(r!!.body, Charsets.UTF_8)).optString("expires_at")) } catch (_: Throwable) { null }
                p.edit().putLong("valid_until", validUntil(now, exp)).putString("token", purchaseToken).putString("product", productId).putLong("checked_ms", now).apply()
            } else if (answer == false) {
                p.edit().putLong("valid_until", 0L).remove("token").putLong("checked_ms", now).apply()
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(answer) }
        }
    }

    /** "2030-01-01T00:00:00Z" / with millis -> epoch ms (API 23 safe). Null when it cannot be read. */
    fun parseIsoUtc(text: String): Long? = try {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        f.parse(text.take(19))?.time
    } catch (_: Throwable) {
        null
    }
}
