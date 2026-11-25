package co.yehaw.browser.browser

import android.webkit.WebView
import co.yehaw.browser.data.TabStateEntity

enum class TabKind(val code: Int) {
    START(0), WEB(1), ARCHIVE(2);

    companion object {
        fun fromCode(code: Int): TabKind = entries.firstOrNull { it.code == code } ?: WEB
    }
}

/** One browser tab. [webView] is null while the tab is sleeping (state lives in [savedState] / Room). */
class Tab(val id: String, var containerId: String, @Volatile var kind: TabKind) {
    var webView: WebView? = null
    @Volatile var url: String = ""
    var title: String = ""
    var scrollY: Int = 0
    var progress: Int = 0
    var lastActiveAt: Long = System.currentTimeMillis()
    var openerId: String? = null
    var savedState: TabStateEntity? = null
    var pendingScrollY: Int = -1
    var offlineRestore: Boolean = false
    var crashes: Int = 0
    var lastCrashAt: Long = 0

    val isLive: Boolean get() = webView != null
    val isSleeping: Boolean get() = webView == null

    val canGoBack: Boolean get() = webView?.canGoBack() == true
    val canGoForward: Boolean get() = webView?.canGoForward() == true
}
