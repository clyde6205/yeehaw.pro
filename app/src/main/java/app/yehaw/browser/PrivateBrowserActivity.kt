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

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (privateSessionActive) {
            Toast.makeText(this, getString(R.string.toast_a_private_window_is_already_open), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        privateSessionActive = true
        ownsPrivateSession = true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Toast.makeText(this, getString(R.string.toast_private_profile_isolation_requires_a), Toast.LENGTH_LONG).show()
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
            hint = getString(R.string.hint_private_search_or_website)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_GO || action == EditorInfo.IME_ACTION_DONE) { navigate(); true } else false
            }
        }
        root.addView(address, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(button(getString(R.string.label_go_2)) { navigate() })
        controls.addView(button(getString(R.string.cd_back)) { webView?.let { if (it.canGoBack()) it.goBack() } })
        controls.addView(button(getString(R.string.cd_reload)) { webView?.reload() })
        controls.addView(button(getString(R.string.label_close_private)) { finish() })
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
                        .setTitle(getString(R.string.title_private_site_permission))
                        .setMessage(getString(R.string.msg_private_requests_camera_mic, request.origin.host.toString()))
                        .setPositiveButton(getString(R.string.btn_continue)) { _, _ ->
                            pendingWebPermission = request
                            pendingWebResources = allowed
                            val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                            if (missing.isEmpty()) grantPendingWebPermission()
                            else requestPermissions(missing.toTypedArray(), PRIVATE_SITE_PERMISSION)
                        }
                        .setNegativeButton(getString(R.string.btn_block)) { _, _ -> request.deny() }
                        .show()
                }
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                if (origin.isNullOrBlank() || !origin.startsWith("https://") || callback == null) {
                    callback?.invoke(origin, false, false)
                    return
                }
                AlertDialog.Builder(this@PrivateBrowserActivity)
                    .setTitle(getString(R.string.title_private_location_permission))
                    .setMessage(getString(R.string.msg_private_requests_location, Uri.parse(origin).host.toString()))
                    .setPositiveButton(getString(R.string.btn_continue)) { _, _ ->
                        pendingGeoCallback = callback
                        pendingGeoOrigin = origin
                        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                            grantPendingGeoPermission(true)
                        } else requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), PRIVATE_GEO_PERMISSION)
                    }
                    .setNegativeButton(getString(R.string.btn_block)) { _, _ -> callback.invoke(origin, false, false) }
                    .show()
            }

            override fun onShowFileChooser(v: WebView?, callback: android.webkit.ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
                uploadCallback?.onReceiveValue(null)
                uploadCallback = callback
                return try {
                    startActivityForResult(Intent.createChooser(params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
                    }, getString(R.string.title_choose_file)), FILE_REQUEST)
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
                    .setTitle(getString(R.string.title_open_another_app))
                    .setMessage(getString(R.string.msg_private_wants_external_app, scheme))
                    .setPositiveButton(getString(R.string.btn_open)) { _, _ ->
                        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                        catch (_: Exception) { Toast.makeText(this@PrivateBrowserActivity, getString(R.string.toast_no_compatible_app_is_installed), Toast.LENGTH_SHORT).show() }
                    }
                    .setNegativeButton(getString(R.string.btn_cancel), null)
                    .show()
                return true
            }

            override fun onRenderProcessGone(v: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                v?.let {
                    it.stopLoading()
                    it.destroy()
                }
                webView = null
                Toast.makeText(this@PrivateBrowserActivity, getString(R.string.toast_private_page_ran_out_of_memory), Toast.LENGTH_LONG).show()
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
                    Toast.makeText(this@PrivateBrowserActivity, getString(R.string.status_secure_connection_blocked), Toast.LENGTH_LONG).show()
                }
            }

            override fun onPageStarted(v: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                if (!url.isNullOrBlank()) address.setText(url)
            }
        }
        view.setDownloadListener(DownloadListener { url, agent, disposition, mime, _ ->
            val uri = Uri.parse(url)
            if (uri.scheme !in listOf("http", "https")) {
                Toast.makeText(this, getString(R.string.toast_blocked_unsupported_download_url), Toast.LENGTH_SHORT).show()
            } else {
                val name = safeDownloadFilename(URLUtil.guessFileName(url, disposition, mime))
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.title_save_outside_private_browsing))
                    .setMessage(getString(R.string.msg_private_save_outside, name))
                    .setPositiveButton(getString(R.string.btn_download)) { _, _ ->
                        try {
                            // On Android 9 and below the shared Downloads folder needs a storage permission this window does not
                            // request; those downloads go to the app's own folder instead of failing.
                            val sharedOk = android.os.Build.VERSION.SDK_INT > 28 ||
                                checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            val request = DownloadManager.Request(uri).setTitle(name)
                                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            if (sharedOk) request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                            else request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name)
                            if (!agent.isNullOrBlank()) request.addRequestHeader("User-Agent", agent)
                            CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
                            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                        } catch (_: Exception) {
                            Toast.makeText(this, getString(R.string.toast_download_failed), Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton(getString(R.string.btn_cancel), null)
                    .show()
            }
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
                if (!isFinishing && !isDestroyed) view.loadUrl(startUrl())
            }
        }
    }

    private fun safeDownloadFilename(value: String): String {
        val cleaned = value.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_").trim().trim('.')
        return cleaned.take(180).ifBlank { "download" }
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
            PRIVATE_GEO_PERMISSION -> grantPendingGeoPermission(grantResults.any { it == PackageManager.PERMISSION_GRANTED })
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
    /** Internal start address (the activity is not exported); anything but http/https falls back to the default search page. */
    private fun startUrl(): String {
        val fallback = "https://duckduckgo.com/"
        val requested = intent?.getStringExtra(EXTRA_START_URL) ?: return fallback
        val uri = Uri.parse(requested)
        return if ((uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()) requested else fallback
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object {
        const val EXTRA_START_URL = "start_url"
        private var privateSessionActive = false
        private var privateDataDirectoryConfigured = false
        private const val FILE_REQUEST = 7201
        private const val PRIVATE_SITE_PERMISSION = 7202
        private const val PRIVATE_GEO_PERMISSION = 7203
    }
}
