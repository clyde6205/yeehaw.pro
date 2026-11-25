package app.yehaw.browser.core

import android.os.Handler
import android.os.Looper

/**
 * Keeps retrying an interrupted operation for as long as it stays interrupted, with capped
 * exponential backoff (3s, 6s, 12s ... 60s). Retries are cheap single reloads, so a user on a
 * flaky 2G link never has to press Retry. [reset] stops a key once it succeeds.
 */
class RetryScheduler {
    private val main = Handler(Looper.getMainLooper())
    private val attempts = HashMap<Any, Int>()
    private val pending = HashMap<Any, Runnable>()

    fun schedule(key: Any, action: () -> Unit) {
        pending.remove(key)?.let { main.removeCallbacks(it) }
        val n = attempts[key] ?: 0
        attempts[key] = n + 1
        val delay = minOf(MAX_DELAY_MS, BASE_DELAY_MS shl minOf(n, 5))
        val task = Runnable { pending.remove(key); action() }
        pending[key] = task
        main.postDelayed(task, delay)
    }

    /** Retry right now (for example when the OS reports the network is back). */
    fun runNow(key: Any, action: () -> Unit) {
        pending.remove(key)?.let { main.removeCallbacks(it) }
        action()
    }

    fun reset(key: Any) {
        attempts.remove(key)
        pending.remove(key)?.let { main.removeCallbacks(it) }
    }

    fun cancelAll() {
        pending.values.forEach { main.removeCallbacks(it) }
        pending.clear(); attempts.clear()
    }

    companion object {
        private const val BASE_DELAY_MS = 3_000L
        private const val MAX_DELAY_MS = 60_000L
    }
}
