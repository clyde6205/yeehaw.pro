package app.yehaw.browser.console

import android.content.Context
import org.json.JSONObject

/**
 * Sends counts only (crashes recovered, repairs, skipped requests, estimated bytes avoided, weak-signal seconds,
 * data received). No URLs, no page content. Needs analytics consent where required. Reports deltas at most every 6 h.
 */
object HealthReporter {
    private const val PREFS = "health_reporter"
    private const val MIN_INTERVAL_MS = 6L * 60 * 60 * 1000
    private val KINDS = listOf("crash", "render_rescue", "skipped_requests", "est_bytes_avoided", "low_signal_seconds", "data_received_bytes")

    fun maybeReport(context: Context, totals: Map<String, Long>) {
        val c = context.applicationContext
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - p.getLong("last_ms", 0L) < MIN_INTERVAL_MS) return
        val id = TelemetryManager.deviceId(c) ?: return
        val deltas = JSONObject()
        var any = false
        for (k in KINDS) {
            val cur = totals[k] ?: continue
            val prev = p.getLong("sent_$k", 0L)
            val d = if (cur >= prev) cur - prev else cur // counters reset (reinstall/clear data): count from zero
            if (d > 0) { deltas.put(k, d); any = true }
        }
        if (!any) { p.edit().putLong("last_ms", now).apply(); return }
        val json = JSONObject().put("device_id", id).put("deltas", deltas).toString()
        BackendHttp.async {
            val code = BackendHttp.postJson("/v1/telemetry/health", json)
            if (code in 200..299) {
                val e = p.edit().putLong("last_ms", System.currentTimeMillis())
                for (k in KINDS) totals[k]?.let { e.putLong("sent_$k", it) }
                e.apply()
            }
        }
    }
}
