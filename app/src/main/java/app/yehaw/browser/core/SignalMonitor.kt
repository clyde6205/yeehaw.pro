package app.yehaw.browser.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

enum class SignalLevel(val label: String) {
    OFFLINE("Offline"), POOR("Weak"), FAIR("Fair"), GOOD("Good");

    /** True when the browser should avoid every non-essential byte. */
    val textFirst: Boolean get() = this == OFFLINE || this == POOR
}

/**
 * Measures the connection the user actually has. It never claims to improve signal.
 * Inputs: the OS network state and bandwidth estimate, plus real page-load timings and
 * failures observed by this browser. Downgrades are immediate; upgrades need a stable
 * period so the UI does not flap between modes on a wobbling 2G/3G link.
 */
class SignalMonitor(context: Context, private val onChange: (SignalLevel) -> Unit) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var manager: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Volatile private var hasNetwork = true
    @Volatile private var validated = true
    @Volatile private var downKbps = 0
    private var avgLoadMs = 0.0
    private val recentOk = ArrayDeque<Boolean>()

    @Volatile var level: SignalLevel = SignalLevel.GOOD
        private set
    private var betterSince = 0L

    private val reevaluate = Runnable { evaluate() }

    fun start() {
        if (callback != null) return
        try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            manager = cm
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { hasNetwork = true; main.post(reevaluate) }
                override fun onLost(network: Network) {
                    hasNetwork = cm.activeNetworkInfoCompat()
                    main.post(reevaluate)
                }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    hasNetwork = true
                    validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    downKbps = caps.linkDownstreamBandwidthKbps
                    main.post(reevaluate)
                }
            }
            callback = cb
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), cb
            )
            hasNetwork = cm.activeNetworkInfoCompat()
        } catch (_: Exception) {
            // Without network-state access the monitor stays at GOOD and the browser behaves normally.
            callback = null
        }
        evaluate()
    }

    fun stop() {
        main.removeCallbacks(reevaluate)
        try { callback?.let { manager?.unregisterNetworkCallback(it) } } catch (_: Exception) {}
        callback = null
    }

    /** Real observation from a finished (or failed) main-frame load. Call on the main thread. */
    fun recordLoad(durationMs: Long, ok: Boolean) {
        if (ok && durationMs in 1..120_000) {
            avgLoadMs = if (avgLoadMs == 0.0) durationMs.toDouble() else avgLoadMs * 0.6 + durationMs * 0.4
        }
        recentOk.addLast(ok)
        while (recentOk.size > 4) recentOk.removeFirst()
        evaluate()
    }

    private fun measured(): SignalLevel {
        if (!hasNetwork) return SignalLevel.OFFLINE
        val failures = recentOk.count { !it }
        if (!validated || failures >= 2 || avgLoadMs > 12_000 || (downKbps in 1..599)) return SignalLevel.POOR
        if (avgLoadMs > 5_000 || (downKbps in 1..2_499)) return SignalLevel.FAIR
        return SignalLevel.GOOD
    }

    private fun evaluate() {
        main.removeCallbacks(reevaluate)
        val target = measured()
        val now = SystemClock.uptimeMillis()
        when {
            target.ordinal <= level.ordinal -> { betterSince = 0; if (target != level) set(target) }
            betterSince == 0L -> { betterSince = now; main.postDelayed(reevaluate, STABLE_MS) }
            now - betterSince >= STABLE_MS -> { betterSince = 0; set(target) }
            else -> main.postDelayed(reevaluate, STABLE_MS - (now - betterSince))
        }
    }

    private fun set(next: SignalLevel) {
        level = next
        onChange(next)
    }

    @Suppress("DEPRECATION")
    private fun ConnectivityManager.activeNetworkInfoCompat(): Boolean =
        try { activeNetworkInfo?.isConnected == true } catch (_: Exception) { true }

    companion object { private const val STABLE_MS = 8_000L }
}
