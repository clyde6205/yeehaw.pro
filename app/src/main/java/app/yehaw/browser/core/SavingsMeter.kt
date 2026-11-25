package app.yehaw.browser.core

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock

/**
 * Honest data-savings ledger, persisted across restarts.
 *
 * EXACT counters: requests skipped, seconds spent in low-signal mode, bytes of saved text reused
 * offline (kept by the activity). ESTIMATED counter: bytes avoided by skipping an image, font or
 * video. The real size of a request we never send is unknowable, so the estimate uses deliberately
 * conservative per-type averages and the UI labels it "est.". Nothing here is a made-up number.
 */
class SavingsMeter(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("savings_meter", Context.MODE_PRIVATE)
    private var skipped = prefs.getLong(K_SKIPPED, 0L)
    private var estBytes = prefs.getLong(K_EST_BYTES, 0L)
    private var lowSignalSeconds = prefs.getLong(K_LOW_SECS, 0L)
    private var lowSince = 0L
    private var dirtyAt = 0L

    val skippedRequests: Long @Synchronized get() = skipped
    val estimatedBytesAvoided: Long @Synchronized get() = estBytes
    val lowSignalSecs: Long @Synchronized get() = lowSignalSeconds + liveLowSecs()

    /** Called for every request the browser chose not to send. [path] is lowercase URL path. */
    @Synchronized
    fun recordSkipped(path: String) {
        skipped++
        estBytes += estimate(path)
        flushSoon()
    }

    @Synchronized
    fun onSignal(level: SignalLevel) {
        val now = SystemClock.elapsedRealtime()
        if (level.textFirst) {
            if (lowSince == 0L) lowSince = now
        } else if (lowSince != 0L) {
            lowSignalSeconds += (now - lowSince) / 1000
            lowSince = 0L
            flush()
        }
    }

    @Synchronized
    fun flush() {
        lowSignalSeconds += liveLowSecs().also { if (lowSince != 0L) lowSince = SystemClock.elapsedRealtime() }
        prefs.edit().putLong(K_SKIPPED, skipped).putLong(K_EST_BYTES, estBytes).putLong(K_LOW_SECS, lowSignalSeconds).apply()
    }

    private fun liveLowSecs(): Long = if (lowSince == 0L) 0L else (SystemClock.elapsedRealtime() - lowSince) / 1000

    private fun flushSoon() {
        val now = SystemClock.elapsedRealtime()
        if (now - dirtyAt > 5_000) { dirtyAt = now; flush() }
    }

    private fun estimate(path: String): Long = when {
        path.endsWith(".mp4") || path.endsWith(".webm") -> 500_000L
        path.endsWith(".gif") -> 120_000L
        path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") || path.endsWith(".webp") -> 40_000L
        path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".ttf") || path.endsWith(".otf") -> 25_000L
        path.endsWith(".svg") -> 6_000L
        else -> 3_000L
    }

    companion object {
        private const val K_SKIPPED = "skipped_requests"
        private const val K_EST_BYTES = "est_bytes_avoided"
        private const val K_LOW_SECS = "low_signal_seconds"

        fun isHeavyAsset(path: String): Boolean =
            path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") || path.endsWith(".gif") ||
                path.endsWith(".webp") || path.endsWith(".svg") || path.endsWith(".ico") || path.endsWith(".mp4") ||
                path.endsWith(".webm") || path.endsWith(".woff") || path.endsWith(".woff2") ||
                path.endsWith(".ttf") || path.endsWith(".otf")
    }
}
