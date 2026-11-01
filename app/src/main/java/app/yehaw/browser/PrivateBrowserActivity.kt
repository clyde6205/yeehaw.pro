package app.yehaw.browser

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.URLUtil
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast

/**
 * Ephemeral private browsing process. Android 9+ gives it an independent WebView data directory.
 * No history or bookmarks are written by this activity.
 */
class PrivateBrowserActivity : Activity() {
    private var webView: WebView? = null
    private lateinit var address: EditText
    private var uploadCallback: android.webkit.ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Toast.makeText(this, "Private profile isolation requires Android 9 or later.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        // Must be called before this process creates its first WebView.
        WebView.setDataDirectorySuffix("yehaw_private")
        CookieManager.getInstance().setAcceptCookie(true)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        address = EditText(this).apply {
            hint = "Private search or website"
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_GO || action == EditorInfo.IME_ACTION_DONE) { navigate(); true } else false
            }
        }
        root.addView(address, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(button("Go") { navigate() })
        controls.addView(button("Back") { webView?.let { if (it.canGoBack()) it.goBack() } })
        controls.addView(button("Reload") { webView?.reload() })
        controls.addView(button("Close private") { finish() })
        root.addView(controls)
        val view = WebView(this)
        webView = view
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            allowFileAccess = false
            allowContentAccess = true // User-selected content:// URIs are required for file uploads.
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        view.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(v: WebView?, callback: android.webkit.ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
                uploadCallback?.onReceiveValue(null)
                uploadCallback = callback
                return try {
                    startActivityForResult(Intent.createChooser(params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
                    }, "Choose file"), FILE_REQUEST)
                    true
                } catch (_: Exception) { uploadCallback = null; false }
            }
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, request: android.webkit.WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme == "http" || uri.scheme == "https") return false
                try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) { }
                return true
            }
            override fun onPageStarted(v: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                if (!url.isNullOrBlank()) address.setText(url)
            }
        }
        view.setDownloadListener(DownloadListener { url, agent, disposition, mime, _ ->
            try {
                val name = URLUtil.guessFileName(url, disposition, mime)
                val request = DownloadManager.Request(Uri.parse(url)).setTitle(name)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                if (!agent.isNullOrBlank()) request.addRequestHeader("User-Agent", agent)
                CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            } catch (_: Exception) { Toast.makeText(this, "Download failed.", Toast.LENGTH_SHORT).show() }
        })
        root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        view.loadUrl("https://duckduckgo.com/")
    }

    private fun navigate() {
        val raw = address.text.toString().trim()
        if (raw.isBlank()) return
        val url = if (raw.contains(" ") || (!raw.contains(".") && !raw.startsWith("http", true)))
            "https://duckduckgo.com/?q=" + Uri.encode(raw)
        else if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw else "https://$raw"
        val parsed = Uri.parse(url)
        if (parsed.scheme !in listOf("http", "https") || parsed.host.isNullOrBlank()) return
        webView?.loadUrl(url)
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 11f; isAllCaps = false; setOnClickListener { action() }
    }
    @Deprecated("Legacy chooser API supports minSdk 23.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_REQUEST) {
            val result = if (resultCode == RESULT_OK && data != null) WebChromeClient.FileChooserParams.parseResult(resultCode, data) else null
            uploadCallback?.onReceiveValue(result); uploadCallback = null
        }
    }
    override fun onDestroy() {
        uploadCallback?.onReceiveValue(null)
        webView?.apply { stopLoading(); loadUrl("about:blank"); clearCache(true); clearHistory(); destroy() }
        webView = null
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        android.webkit.WebStorage.getInstance().deleteAllData()
        super.onDestroy()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object { private const val FILE_REQUEST = 7201 }
}
