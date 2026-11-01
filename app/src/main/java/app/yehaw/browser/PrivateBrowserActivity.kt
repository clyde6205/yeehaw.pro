package app.yehaw.browser

import android.app.Activity
import android.app.AlertDialog
import android.Manifest
import android.content.pm.PackageManager
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.ViewGroup
import android.view.View
import android.widget.FrameLayout
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.PermissionRequest
import android.webkit.GeolocationPermissions
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
    private var pendingWebPermission: PermissionRequest? = null
    private var pendingWebResources: Array<String> = emptyArray()
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private var pendingGeoOrigin: String? = null
    private var ownsPrivateSession = false
    private var browserRoot: LinearLayout? = null
    private var fullScreenView: View? = null
    private var fullScreenCallback: WebChromeClient.CustomViewCallback? = null
    private var previousSystemUiVisibility = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (privateSessionActive) {
            Toast.makeText(this, "A private window is already open.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        privateSessionActive = true
        ownsPrivateSession = true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Toast.makeText(this, "Private profile isolation requires Android 9 or later.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        // The suffix is process-wide and must be configured only once before the first WebView.
        if (!privateDataDirectoryConfigured) {
            WebView.setDataDirectorySuffix("yehaw_private")
            privateDataDirectoryConfigured = true
        }
        CookieManager.getInstance().setAcceptCookie(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
        }
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
            setGeolocationEnabled(true)
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        view.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
                if (view == null || fullScreenView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                val decor = window.decorView as? FrameLayout ?: run {
                    callback?.onCustomViewHidden()
                    return
                }
                fullScreenView = view
                fullScreenCallback = callback
                previousSystemUiVisibility = decor.systemUiVisibility
                browserRoot?.visibility = View.GONE
                decor.addView(view, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ))
                decor.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
            }

            override fun onHideCustomView() {
                hideFullScreenView()
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val allowed = request.resources.filter {
                        it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
                    }.toTypedArray()
                    if (request.origin.scheme != "https" || allowed.isEmpty() || allowed.size != request.resources.size) {
                        request.deny()
                        return@runOnUiThread
                    }
                    val required = mutableListOf<String>()
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in allowed) required.add(Manifest.permission.CAMERA)
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in allowed) required.add(Manifest.permission.RECORD_AUDIO)
                    AlertDialog.Builder(this@PrivateBrowserActivity)
                        .setTitle("Private site permission")
                        .setMessage(request.origin.host.toString() + " requests camera or microphone access.")
                        .setPositiveButton("Continue") { _, _ ->
                            pendingWebPermission = request
                            pendingWebResources = allowed
                            val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                            if (missing.isEmpty()) grantPendingWebPermission()
                            else requestPermissions(missing.toTypedArray(), PRIVATE_SITE_PERMISSION)
                        }
                        .setNegativeButton("Block") { _, _ -> request.deny() }
                        .show()
                }
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                if (origin.isNullOrBlank() || !origin.startsWith("https://") || callback == null) {
                    callback?.invoke(origin, false, false)
                    return
                }
                AlertDialog.Builder(this@PrivateBrowserActivity)
                    .setTitle("Private location permission")
                    .setMessage(Uri.parse(origin).host.toString() + " requests device location.")
                    .setPositiveButton("Continue") { _, _ ->
                        pendingGeoCallback = callback
                        pendingGeoOrigin = origin
                        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                            grantPendingGeoPermission(true)
                        } else requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), PRIVATE_GEO_PERMISSION)
                    }
                    .setNegativeButton("Block") { _, _ -> callback.invoke(origin, false, false) }
                    .show()
            }

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
                val scheme = uri.scheme?.lowercase()
                if (scheme !in setOf("tel", "mailto", "sms", "smsto", "geo", "market")) return true
                AlertDialog.Builder(this@PrivateBrowserActivity)
                    .setTitle("Open another app?")
                    .setMessage("This private page wants to open an external application (" + scheme + ").")
                    .setPositiveButton("Open") { _, _ ->
                        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                        catch (_: Exception) { Toast.makeText(this@PrivateBrowserActivity, "No compatible app is installed.", Toast.LENGTH_SHORT).show() }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                return true
            }

            override fun onRenderProcessGone(v: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                v?.let {
                    it.stopLoading()
                    it.destroy()
                }
                webView = null
                Toast.makeText(this@PrivateBrowserActivity, "Private page ran out of memory. Close and reopen private browsing.", Toast.LENGTH_LONG).show()
                finish()
                return true
            }
            override fun onReceivedSslError(
                v: WebView?,
                handler: android.webkit.SslErrorHandler?,
                error: android.net.http.SslError?
            ) {
                handler?.cancel()
                if (v != null && error?.url == v.url) {
                    Toast.makeText(this@PrivateBrowserActivity, "Secure connection blocked.", Toast.LENGTH_LONG).show()
                }
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
        browserRoot = root
        setContentView(root)
        // Remove remnants left by an interrupted private session before navigation.
        view.clearCache(true)
        view.clearHistory()
        android.webkit.WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            runOnUiThread {
                if (!isFinishing && !isDestroyed) view.loadUrl("https://duckduckgo.com/")
            }
        }
    }

    private fun hideFullScreenView() {
        val view = fullScreenView ?: return
        val decor = window.decorView as? FrameLayout
        decor?.removeView(view)
        decor?.systemUiVisibility = previousSystemUiVisibility
        browserRoot?.visibility = View.VISIBLE
        fullScreenView = null
        fullScreenCallback?.onCustomViewHidden()
        fullScreenCallback = null
    }

    private fun grantPendingWebPermission() {
        val request = pendingWebPermission ?: return
        request.grant(pendingWebResources)
        pendingWebPermission = null
        pendingWebResources = emptyArray()
    }

    private fun grantPendingGeoPermission(granted: Boolean) {
        pendingGeoCallback?.invoke(pendingGeoOrigin, granted, false)
        pendingGeoCallback = null
        pendingGeoOrigin = null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        when (requestCode) {
            PRIVATE_SITE_PERMISSION -> if (granted) grantPendingWebPermission() else {
                pendingWebPermission?.deny()
                pendingWebPermission = null
                pendingWebResources = emptyArray()
            }
            PRIVATE_GEO_PERMISSION -> grantPendingGeoPermission(granted)
        }
    }

    @Deprecated("Uses the private WebView navigation stack.")
    override fun onBackPressed() {
        if (fullScreenView != null) {
            hideFullScreenView()
            return
        }
        val current = webView
        if (current?.canGoBack() == true) current.goBack() else finish()
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
        text = label; textSize = 11f; isAllCaps = false; minWidth = dp(48); minHeight = dp(48); setOnClickListener { action() }
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
        if (fullScreenView != null) hideFullScreenView()
        uploadCallback?.onReceiveValue(null)
        pendingWebPermission?.deny()
        pendingWebPermission = null
        pendingGeoCallback?.invoke(pendingGeoOrigin, false, false)
        pendingGeoCallback = null
        webView?.apply { stopLoading(); loadUrl("about:blank"); clearCache(true); clearHistory(); destroy() }
        webView = null
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        android.webkit.WebStorage.getInstance().deleteAllData()
        if (ownsPrivateSession) privateSessionActive = false
        super.onDestroy()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object {
        private var privateSessionActive = false
        private var privateDataDirectoryConfigured = false
        private const val FILE_REQUEST = 7201
        private const val PRIVATE_SITE_PERMISSION = 7202
        private const val PRIVATE_GEO_PERMISSION = 7203
    }
}
