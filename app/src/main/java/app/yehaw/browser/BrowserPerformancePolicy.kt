package app.yehaw.browser

/**
 * Conservative defaults for entry-level Android devices.
 *
 * The policy only changes resource loading defaults and live-tab count. It never rewrites
 * page content, disables JavaScript, or changes a user's explicit saved preference.
 */
internal object BrowserPerformancePolicy {
    /** Upper bound on open tabs; only [maxLiveTabs] of them keep a live WebView. */
    const val MAX_TOTAL_TABS = 12

    fun defaultMemorySaver(isLowRamDevice: Boolean, memoryClassMb: Int): Boolean =
        isLowRamDevice || memoryClassMb <= 128

    fun maxLiveTabs(isLowRamDevice: Boolean, memoryClassMb: Int): Int = when {
        isLowRamDevice || memoryClassMb <= 128 -> 2
        memoryClassMb <= 192 -> 3
        else -> 4
    }
}
