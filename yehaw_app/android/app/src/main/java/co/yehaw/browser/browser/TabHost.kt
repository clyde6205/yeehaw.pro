package co.yehaw.browser.browser

import android.content.Intent
import android.net.Uri
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient

/** UI callbacks implemented by MainActivity; TabManager keeps working (headless) when none is attached. */
interface TabHost {
    fun onTabsChanged()
    fun onActiveTabChanged(tab: Tab?)
    fun onTabUpdated(tab: Tab)
    fun onProgress(tab: Tab, progress: Int)
    fun onDownloadRequested(tab: Tab, url: String, userAgent: String?, contentDisposition: String?, mimeType: String?)
    fun onFileChooser(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean
    fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback)
    fun onHideCustomView()
    fun onStartPageAction(tab: Tab, uri: Uri)
    fun startPageJson(): String
    fun onExternalIntent(intent: Intent, fallbackUrl: String?)
    fun onMessage(resId: Int)
}
