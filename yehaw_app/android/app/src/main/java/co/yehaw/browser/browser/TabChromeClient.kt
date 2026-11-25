package co.yehaw.browser.browser

import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

class TabChromeClient(private val tab: Tab, private val mgr: TabManager) : WebChromeClient() {

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        tab.progress = newProgress
        mgr.host?.onProgress(tab, newProgress)
    }

    override fun onReceivedTitle(view: WebView, title: String?) {
        if (!title.isNullOrBlank()) {
            tab.title = title
            mgr.host?.onTabUpdated(tab)
        }
    }

    override fun onShowFileChooser(
        webView: WebView, filePathCallback: ValueCallback<Array<Uri>>, params: FileChooserParams
    ): Boolean {
        val host = mgr.host
        if (host == null) {
            filePathCallback.onReceiveValue(null)
            return false
        }
        return host.onFileChooser(filePathCallback, params)
    }

    /** window.open / target=_blank: only with a user gesture (blocks popup spam), opens as a real tab. */
    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
        if (!isUserGesture) return false
        val popup = mgr.openPopupTab(tab) ?: return false
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        transport.webView = popup.webView
        resultMsg.sendToTarget()
        return true
    }

    override fun onCloseWindow(window: WebView) {
        mgr.closeByWebView(window)
    }

    override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
        callback.invoke(origin, false, false)
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        request.deny()
    }

    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        val host = mgr.host
        if (host == null) callback.onCustomViewHidden() else host.onShowCustomView(view, callback)
    }

    override fun onHideCustomView() {
        mgr.host?.onHideCustomView()
    }

    override fun getDefaultVideoPoster(): Bitmap? =
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
}
