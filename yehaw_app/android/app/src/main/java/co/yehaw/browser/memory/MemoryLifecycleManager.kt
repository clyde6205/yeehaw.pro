package co.yehaw.browser.memory

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.Lifecycle
import co.yehaw.browser.browser.Tab
import co.yehaw.browser.browser.TabManager
import co.yehaw.browser.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Reacts to Android memory-pressure signals and runs the idle-tab sweeper.
 *
 * Escalation (all work happens on the main thread, where onTrimMemory is delivered):
 *  - RUNNING_LOW       : drop WebView caches/decoded images, sleep tabs idle for >60 s.
 *  - RUNNING_CRITICAL  : keep only the visible tab and the most recent other tab awake.
 *  - UI_HIDDEN         : checkpoint the whole session to Room (cheap insurance against lmkd).
 *  - MODERATE          : clearCache(false) and sleep every background tab.
 *  - COMPLETE          : sleep everything, including the visible tab if the app is in the background.
 * Independently, a sweeper puts tabs to sleep after [SettingsStore.suspendAfterMs] (default 3 min) idle.
 */
class MemoryLifecycleManager(
    private val app: Application,
    private val tabs: TabManager,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) : ComponentCallbacks2 {

    private var sweeper: Job? = null

    fun start() {
        app.registerComponentCallbacks(this)
        sweeper?.cancel()
        sweeper = scope.launch {
            while (isActive) {
                delay(SWEEP_INTERVAL_MS)
                sweepIdleTabs()
            }
        }
    }

    fun stop() {
        app.unregisterComponentCallbacks(this)
        sweeper?.cancel()
    }

    override fun onTrimMemory(level: Int) {
        when {
            level >= LEVEL_COMPLETE -> {
                clearWebCaches()
                suspendWhere { tab -> tab !== tabs.activeTab || !isAppVisible() }
            }
            level >= LEVEL_MODERATE -> {
                clearWebCaches()
                suspendWhere { it !== tabs.activeTab }
            }
            level >= LEVEL_BACKGROUND -> suspendWhere { it !== tabs.activeTab && idleMs(it) > 30_000 }
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> tabs.persistSession()
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                val keep = tabs.liveTabs().filter { it !== tabs.activeTab }.maxByOrNull { it.lastActiveAt }
                suspendWhere { it !== tabs.activeTab && it !== keep }
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                clearWebCaches()
                suspendWhere { it !== tabs.activeTab && idleMs(it) > 60_000 }
            }
        }
    }

    @Deprecated("Superseded by onTrimMemory but still delivered on some builds")
    override fun onLowMemory() {
        clearWebCaches()
        suspendWhere { it !== tabs.activeTab || !isAppVisible() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    /** Sleep every tab (not the visible one) idle longer than the configured threshold. */
    fun sweepIdleTabs() {
        if (!settings.autoSleepTabs) return
        val threshold = settings.suspendAfterMs
        val visible = isAppVisible()
        tabs.liveTabs().forEach { tab ->
            val isCurrent = visible && tab === tabs.activeTab
            if (!isCurrent && idleMs(tab) >= threshold) tabs.suspendTab(tab)
        }
    }

    private fun suspendWhere(predicate: (Tab) -> Boolean) {
        tabs.liveTabs().filter(predicate).forEach { tabs.suspendTab(it) }
    }

    /** WebView's HTTP cache and decoded-image caches are process-wide; one call frees them for every tab. */
    private fun clearWebCaches() {
        tabs.liveTabs().firstOrNull()?.webView?.clearCache(false)
    }

    private fun idleMs(tab: Tab) = System.currentTimeMillis() - tab.lastActiveAt

    private fun isAppVisible(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    companion object {
        private const val SWEEP_INTERVAL_MS = 20_000L
        // Numeric values of the ComponentCallbacks2 levels deprecated in API 34; the OS still sends them.
        private const val LEVEL_BACKGROUND = 40
        private const val LEVEL_MODERATE = 60
        private const val LEVEL_COMPLETE = 80
    }
}
