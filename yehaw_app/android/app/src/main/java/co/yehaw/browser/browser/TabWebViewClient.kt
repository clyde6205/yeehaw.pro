package co.yehaw.browser.browser

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.http.SslError
import co.yehaw.browser.R
import java.io.ByteArrayInputStream

class TabWebViewClient(private val tab: Tab, private val mgr: TabManager) : WebViewClient() {

    // Called on a WebView worker thread: never touch WebView APIs here (tab.url is @Volatile).
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        if (tab.kind == TabKind.ARCHIVE) {
            val path = uri.path
            return if (uri.scheme == "file" && path != null && path.startsWith(mgr.archivesDirPath)) null else emptyResponse()
        }
        if (tab.kind == TabKind.START && uri.host == TabManager.ASSET_HOST) {
            mgr.assetLoader.shouldInterceptRequest(uri)?.let { return it }
        }
        return mgr.adBlock.intercept(request, tab.url) ?: mgr.dataSaver.tryServe(request, tab.url)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url
        return when (uri.scheme?.lowercase()) {
            "http", "https", "about", "javascript", "data", "blob" -> false
            "yeehaw" -> {
                if (tab.kind == TabKind.START && request.hasGesture() && mgr.isStartPageUrl(view.url)) {
                    view.post { mgr.host?.onStartPageAction(tab, uri) }
                }
                true
            }
            "file", "content" -> tab.kind != TabKind.ARCHIVE
            else -> {
                if (request.hasGesture()) mgr.handleExternalScheme(uri)
                true
            }
        }
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        tab.url = url
        tab.progress = 0
        if (tab.kind != TabKind.ARCHIVE) tab.kind = if (mgr.isStartPageUrl(url)) TabKind.START else TabKind.WEB
        mgr.host?.onTabUpdated(tab)
    }

    override fun onPageFinished(view: WebView, url: String) {
        if (!url.startsWith("data:")) tab.url = url
        tab.title = view.title?.takeIf { it.isNotBlank() } ?: tab.title
        if (tab.pendingScrollY > 0) {
            val y = tab.pendingScrollY
            view.postDelayed({ view.scrollTo(0, y) }, 150)
        }
        tab.pendingScrollY = -1
        if (tab.offlineRestore) {
            tab.offlineRestore = false
            view.settings.cacheMode = WebSettings.LOAD_DEFAULT
        }
        if (tab.kind == TabKind.START && mgr.isStartPageUrl(url)) {
            val json = mgr.host?.startPageJson() ?: "{}"
            view.evaluateJavascript("window.YH&&window.YH.init($json)", null)
        }
        mgr.host?.onTabUpdated(tab)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        if (!url.startsWith("data:") && url != "about:blank") tab.url = url
        mgr.host?.onTabUpdated(tab)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (!request.isForMainFrame || tab.kind == TabKind.ARCHIVE) return
        mgr.showError(tab, mgr.string(R.string.err_title_load), error.description?.toString().orEmpty(), request.url.toString())
    }

    /** Certificates are never bypassed: a broken certificate on a banking or shopping page must stop the load. */
    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()
        mgr.showError(tab, mgr.string(R.string.err_title_ssl), mgr.string(R.string.err_msg_ssl), error.url ?: tab.url)
    }

    /** The renderer was killed (typically by low memory). Recover the tab instead of crashing the app. */
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        mgr.recoverTab(tab)
        return true
    }

    private fun emptyResponse() = WebResourceResponse(
        "text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0))
    )
}
