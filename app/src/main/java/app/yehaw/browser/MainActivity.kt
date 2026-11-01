package app.yehaw.browser

import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.app.AlertDialog
import android.app.ActivityManager
import android.app.DownloadManager
import android.app.role.RoleManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.os.Message
import android.provider.Settings
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Minimal-dependency native browser foundation.
 *
 * WebView uses the Android System WebView engine; the engine is not bundled into this APK.
 * JavaScript is enabled for modern-site compatibility. No JavaScript interfaces are exposed.
 */
class MainActivity : Activity() {
    private data class BrowserTab(
        val webView: WebView,
        var title: String = "New tab",
        var currentUrl: String = HOME_URL,
        var failed: Boolean = false
    )

    private lateinit var addressBar: EditText
    private lateinit var pageContainer: LinearLayout
    private lateinit var statusText: TextView
    private val tabs = mutableListOf<BrowserTab>()
    private lateinit var browserStore: BrowserStore
    private val browserPreferences by lazy { getSharedPreferences("browser_settings", Context.MODE_PRIVATE) }
    private var uploadCallback: ValueCallback<Array<Uri>>? = null
    private var desktopMode = false
    private var thirdPartyCookiesEnabled = false
    private var searchEngine = "Google"
    private var memorySaver = false
    private var pendingWebPermission: PermissionRequest? = null
    private var pendingWebResources: Array<String> = emptyArray()
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private var pendingGeoOrigin: String? = null
    private var fullScreenView: View? = null
    private var fullScreenCallback: WebChromeClient.CustomViewCallback? = null
    private var previousSystemUiVisibility = 0
    private var activeTabIndex = -1
    private val maxTabs: Int by lazy {
        val memoryClass = (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).memoryClass
        when {
            memoryClass <= 128 -> 2
            memoryClass <= 192 -> 3
            else -> 4
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        browserStore = BrowserStore(this)
        desktopMode = browserPreferences.getBoolean("desktop_mode", false)
        memorySaver = browserPreferences.getBoolean("memory_saver", false)
        thirdPartyCookiesEnabled = browserPreferences.getBoolean("third_party_cookies", false)
        searchEngine = browserPreferences.getString("search_engine", "Google") ?: "Google"
        if (searchEngine !in SEARCH_ENGINES.keys) searchEngine = "Google"
        buildInterface()
        CookieManager.getInstance().setAcceptCookie(true)
        handleIncomingIntent(intent)
        if (tabs.isEmpty()) createTab(HOME_URL)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF7F8FA.toInt())
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
        }

        val brand = TextView(this).apply {
            text = "YEHAW  ·  LIGHTWEIGHT BROWSER"
            textSize = 12f
            setTextColor(0xFF475569.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(4))
        }
        root.addView(brand)

        val addressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(6))
        }
        addressBar = EditText(this).apply {
            hint = "Search or enter website"
            isSingleLine = true
            textSize = 15f
            imeOptions = EditorInfo.IME_ACTION_GO
            setSelectAllOnFocus(false)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                    navigateFromAddressBar()
                    true
                } else false
            }
        }
        addressRow.addView(addressBar, LinearLayout.LayoutParams(0, dp(46), 1f))
        addressRow.addView(actionButton("GO") { navigateFromAddressBar() })
        root.addView(addressRow)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(2), dp(4), dp(6))
        }
        controls.addView(actionButton("‹") { currentWebView()?.let { if (it.canGoBack()) it.goBack() } })
        controls.addView(actionButton("›") { currentWebView()?.let { if (it.canGoForward()) it.goForward() } })
        controls.addView(actionButton("↻") { currentWebView()?.reload() })
        controls.addView(actionButton("Home") { loadHome() })
        controls.addView(actionButton("+") { createTab(HOME_URL) })
        controls.addView(actionButton("Tabs") { showTabPicker() })
        controls.addView(actionButton("Default") { requestDefaultBrowserRole() })
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(controls)
        })

        val libraryControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(2), 0, dp(2), dp(4))
        }
        libraryControls.addView(actionButton("Save") { saveBookmark() })
        libraryControls.addView(actionButton("Bookmarks") { showSavedItems(true) })
        libraryControls.addView(actionButton("History") { showSavedItems(false) })
        libraryControls.addView(actionButton("Downloads") { openDownloads() })
        libraryControls.addView(actionButton("Find") { findOnPage() })
        libraryControls.addView(actionButton("Share") { shareCurrentPage() })
        libraryControls.addView(actionButton("More") { showBrowserMenu() })
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(libraryControls)
        })

        statusText = TextView(this).apply {
            text = "Ready"
            textSize = 11f
            setTextColor(0xFF64748B.toInt())
            setPadding(dp(12), dp(2), dp(12), dp(4))
            maxLines = 1
        }
        root.addView(statusText)

        pageContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        root.addView(
            pageContainer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        setContentView(root)
    }

    private fun actionButton(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 11f
            isAllCaps = false
            minWidth = dp(38)
            setPadding(dp(4), 0, dp(4), 0)
            setOnClickListener { action() }
        }
    }

    private fun createTab(url: String) {
        if (tabs.size >= maxTabs) {
            Toast.makeText(this, "Close a tab before opening another (limit: $maxTabs).", Toast.LENGTH_SHORT).show()
            return
        }
        val webView = WebView(this)
        configureWebView(webView)
        tabs.add(BrowserTab(webView = webView, currentUrl = url))
        showTab(tabs.lastIndex)
        webView.loadUrl(url)
    }

    private fun configureWebView(webView: WebView) {
        webView.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = false
            loadsImagesAutomatically = !memorySaver
            blockNetworkImage = false
            cacheMode = WebSettings.LOAD_DEFAULT
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)
            allowFileAccess = false
            allowContentAccess = true // User-selected content:// URIs are required for file uploads.
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, thirdPartyCookiesEnabled)
        if (desktopMode) webView.settings.userAgentString = DESKTOP_USER_AGENT

        webView.webChromeClient = object : WebChromeClient() {
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
                    val names = allowed.joinToString(" and ") {
                        if (it == PermissionRequest.RESOURCE_VIDEO_CAPTURE) "camera" else "microphone"
                    }
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Site permission request")
                        .setMessage(request.origin.host.toString() + " wants to use your " + names + ". Continue only if you trust this website.")
                        .setPositiveButton("Continue") { _, _ ->
                            pendingWebPermission = request
                            pendingWebResources = allowed
                            val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                            if (missing.isEmpty()) grantPendingWebPermission()
                            else requestPermissions(missing.toTypedArray(), REQUEST_SITE_PERMISSION)
                        }
                        .setNegativeButton("Block") { _, _ -> request.deny() }
                        .show()
                }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                if (pendingWebPermission === request) {
                    pendingWebPermission = null
                    pendingWebResources = emptyArray()
                }
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                if (origin.isNullOrBlank() || !origin.startsWith("https://") || callback == null) {
                    callback?.invoke(origin, false, false)
                    return
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Location permission request")
                    .setMessage(Uri.parse(origin).host.toString() + " wants access to your device location.")
                    .setPositiveButton("Continue") { _, _ ->
                        pendingGeoCallback = callback
                        pendingGeoOrigin = origin
                        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                            grantPendingGeoPermission(true)
                        } else requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_GEO_PERMISSION)
                    }
                    .setNegativeButton("Block") { _, _ -> callback.invoke(origin, false, false) }
                    .show()
            }

            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                uploadCallback?.onReceiveValue(null)
                uploadCallback = filePathCallback
                return try {
                    val chooser = fileChooserParams?.createIntent()
                        ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "*/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                        }
                    startActivityForResult(Intent.createChooser(chooser, "Choose file"), REQUEST_FILE_CHOOSER)
                    true
                } catch (_: Exception) {
                    uploadCallback = null
                    false
                }
            }

            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                if (!isUserGesture || tabs.size >= maxTabs) return false
                val message = resultMsg ?: return false
                val transport = message.obj as? WebView.WebViewTransport ?: return false
                val popup = WebView(this@MainActivity)
                configureWebView(popup)
                tabs.add(BrowserTab(webView = popup))
                showTab(tabs.lastIndex)
                transport.webView = popup
                message.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView?) {
                val index = tabs.indexOfFirst { it.webView === window }
                if (index >= 0) {
                    if (index == activeTabIndex) closeCurrentTab()
                    else {
                        val removed = tabs.removeAt(index)
                        removed.webView.stopLoading()
                        removed.webView.destroy()
                        if (activeTabIndex > index) activeTabIndex--
                    }
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                val tab = tabs.firstOrNull { it.webView === view } ?: return
                tab.title = title?.take(80)?.ifBlank { "Untitled page" } ?: "Untitled page"
                if (view === currentWebView()) statusText.text = tab.title
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (view === currentWebView()) {
                    statusText.text = if (newProgress >= 100) "Page loaded" else "Loading $newProgress%"
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                return when (uri.scheme?.lowercase()) {
                    "http", "https" -> false
                    else -> {
                        openExternalScheme(uri)
                        true
                    }
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                val tab = tabs.firstOrNull { it.webView === view } ?: return
                tab.failed = false
                if (view === currentWebView() && pageContainer.indexOfChild(view) < 0) showTab(activeTabIndex)
                if (!url.isNullOrBlank()) {
                    tab.currentUrl = url
                    if (view === currentWebView()) {
                        addressBar.setText(url)
                        addressBar.setSelection(addressBar.text.length)
                    }
                }
                if (view === currentWebView()) statusText.text = "Loading…"
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val tab = tabs.firstOrNull { it.webView === view }
                if (tab != null && !url.isNullOrBlank() &&
                    (url.startsWith("https://") || url.startsWith("http://"))) {
                    tab.currentUrl = url
                    browserStore.addHistory(url, tab.title)
                }
                if (view === currentWebView()) statusText.text = "Ready"
            }

            override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || view == null) return true
                val index = tabs.indexOfFirst { it.webView === view }
                if (index < 0) return true
                pageContainer.removeView(view)
                tabs.removeAt(index)
                view.destroy()
                if (tabs.isEmpty()) {
                    activeTabIndex = -1
                    createTab(HOME_URL)
                } else {
                    if (activeTabIndex > index) activeTabIndex--
                    else if (activeTabIndex == index) activeTabIndex = -1
                    showTab(if (activeTabIndex < 0) index.coerceAtMost(tabs.lastIndex) else activeTabIndex)
                }
                Toast.makeText(this@MainActivity, "A page used too much memory and was restarted.", Toast.LENGTH_LONG).show()
                return true
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true && view != null) {
                    val tab = tabs.firstOrNull { it.webView === view } ?: return
                    tab.failed = true
                    if (view === currentWebView()) showTab(activeTabIndex)
                    statusText.text = "Page could not be loaded."
                }
            }
        }

        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            downloadFile(url, userAgent, contentDisposition, mimeType)
        })
    }

    private fun navigateFromAddressBar() {
        val raw = addressBar.text?.toString()?.trim().orEmpty()
        if (raw.isBlank()) return
        val destination = if (raw.contains(" ") || (!raw.contains(".") && !raw.startsWith("http", true))) {
            SEARCH_ENGINES.getValue(searchEngine) + Uri.encode(raw)
        } else {
            val candidate = if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw else "https://$raw"
            val parsed = Uri.parse(candidate)
            if (parsed.scheme !in listOf("http", "https") || parsed.host.isNullOrBlank()) {
                Toast.makeText(this, "Enter a valid website address or search.", Toast.LENGTH_SHORT).show()
                return
            }
            candidate
        }
        currentWebView()?.loadUrl(destination) ?: createTab(destination)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        if (uri.scheme == "http" || uri.scheme == "https") {
            if (tabs.isEmpty()) createTab(uri.toString()) else currentWebView()?.loadUrl(uri.toString())
        }
    }

    private fun openExternalScheme(uri: Uri) {
        val scheme = uri.scheme?.lowercase()
        if (scheme !in setOf("tel", "mailto", "sms", "smsto", "geo", "market")) {
            Toast.makeText(this, "Blocked unsupported link type.", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Open another app?")
            .setMessage("This link wants to open an external application (" + scheme + "). Continue only if you trust the page.")
            .setPositiveButton("Open") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (_: Exception) {
                    Toast.makeText(this, "No compatible app is installed.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun downloadFile(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        val uri = Uri.parse(url)
        if (uri.scheme !in listOf("http", "https")) {
            Toast.makeText(this, "Blocked unsupported download URL.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val filename = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(uri)
                .setTitle(filename)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
            if (!userAgent.isNullOrBlank()) request.addRequestHeader("User-Agent", userAgent)
            CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            Toast.makeText(this, "Download started.", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Could not start this download.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showTabPicker() {
        if (tabs.isEmpty()) return
        val labels = tabs.mapIndexed { index, tab ->
            "${index + 1}. ${tab.title}  ·  ${Uri.parse(tab.currentUrl).host ?: "New tab"}"
        }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Open tabs")
            .setItems(labels) { _, which -> showTab(which) }
            .setNegativeButton("Close current") { _, _ -> closeCurrentTab() }
            .setNeutralButton("New tab") { _, _ -> createTab(HOME_URL) }
            .show()
    }

    private fun showTab(index: Int) {
        if (index !in tabs.indices) return
        val previous = currentWebView()
        if (previous != null && previous !== tabs[index].webView) previous.onPause()
        pageContainer.removeAllViews()
        activeTabIndex = index
        val tab = tabs[index]
        tab.webView.onResume()
        if (tab.failed) {
            val errorPanel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(24), dp(24), dp(24))
            }
            errorPanel.addView(TextView(this).apply {
                text = "This page could not be loaded. Check your connection and try again."
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(16))
            })
            errorPanel.addView(actionButton("Retry") {
                tab.failed = false
                pageContainer.removeAllViews()
                pageContainer.addView(tab.webView)
                tab.webView.reload()
            })
            pageContainer.addView(errorPanel, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        } else {
            pageContainer.addView(tab.webView)
        }
        addressBar.setText(tab.currentUrl)
        addressBar.setSelection(addressBar.text.length)
        statusText.text = tab.title
    }

    private fun closeCurrentTab() {
        if (tabs.isEmpty() || activeTabIndex !in tabs.indices) return
        val removed = tabs.removeAt(activeTabIndex)
        pageContainer.removeView(removed.webView)
        removed.webView.stopLoading()
        removed.webView.loadUrl("about:blank")
        removed.webView.destroy()
        if (tabs.isEmpty()) {
            activeTabIndex = -1
            createTab(HOME_URL)
        } else {
            showTab((activeTabIndex - 1).coerceAtLeast(0))
        }
    }

    private fun loadHome() {
        currentWebView()?.loadUrl(HOME_URL) ?: createTab(HOME_URL)
    }

    private fun currentWebView(): WebView? = tabs.getOrNull(activeTabIndex)?.webView

    private fun requestDefaultBrowserRole() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "Use Android Settings → Apps → Default apps → Browser.", Toast.LENGTH_LONG).show()
            return
        }
        val roleManager = getSystemService(RoleManager::class.java)
        if (!roleManager.isRoleAvailable(RoleManager.ROLE_BROWSER)) {
            Toast.makeText(this, "Browser role is unavailable on this Android version.", Toast.LENGTH_LONG).show()
            return
        }
        if (roleManager.isRoleHeld(RoleManager.ROLE_BROWSER)) {
            Toast.makeText(this, "Yehaw is already the default browser.", Toast.LENGTH_SHORT).show()
            return
        }
        startActivityForResult(roleManager.createRequestRoleIntent(RoleManager.ROLE_BROWSER), REQUEST_BROWSER_ROLE)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && tabs.size > 1) {
            val active = currentWebView()
            tabs.filter { it.webView !== active }.forEach {
                it.webView.stopLoading()
                it.webView.onPause()
                it.webView.clearCache(false)
            }
        }
    }

    private fun hideFullScreenView() {
        val view = fullScreenView ?: return
        val decor = window.decorView as? FrameLayout
        decor?.removeView(view)
        decor?.systemUiVisibility = previousSystemUiVisibility
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
            REQUEST_SITE_PERMISSION -> {
                if (granted) grantPendingWebPermission() else {
                    pendingWebPermission?.deny()
                    pendingWebPermission = null
                    pendingWebResources = emptyArray()
                }
            }
            REQUEST_GEO_PERMISSION -> grantPendingGeoPermission(granted)
        }
    }

    private fun openDownloads() {
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        } catch (_: Exception) {
            Toast.makeText(this, "Downloads app is unavailable on this device.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareCurrentPage() {
        val tab = tabs.getOrNull(activeTabIndex) ?: return
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, tab.currentUrl)
            putExtra(Intent.EXTRA_SUBJECT, tab.title)
        }
        startActivity(Intent.createChooser(share, "Share page"))
    }

    private fun saveBookmark() {
        val tab = tabs.getOrNull(activeTabIndex) ?: return
        if (!tab.currentUrl.startsWith("http")) return
        browserStore.addBookmark(tab.currentUrl, tab.title)
        Toast.makeText(this, "Bookmark saved.", Toast.LENGTH_SHORT).show()
    }

    private fun showSavedItems(bookmarks: Boolean) {
        val items = if (bookmarks) browserStore.bookmarks() else browserStore.history()
        if (items.isEmpty()) {
            Toast.makeText(this, if (bookmarks) "No bookmarks yet." else "No history yet.", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = items.map { "${it.title}\n${it.url}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(if (bookmarks) "Bookmarks" else "Recent history")
            .setItems(labels) { _, index ->
                val url = items[index].url
                currentWebView()?.loadUrl(url) ?: createTab(url)
            }
            .setNeutralButton(if (bookmarks) "Clear bookmarks" else "Clear history") { _, _ ->
                if (bookmarks) browserStore.clearBookmarks() else browserStore.clearHistory()
            }
            .setNegativeButton("Close", null).show()
    }

    private fun findOnPage() {
        val field = EditText(this).apply { hint = "Find text on this page"; isSingleLine = true }
        AlertDialog.Builder(this).setTitle("Find in page").setView(field)
            .setPositiveButton("Find") { _, _ -> currentWebView()?.findAllAsync(field.text.toString()) }
            .setNeutralButton("Next") { _, _ -> currentWebView()?.findNext(true) }
            .setNegativeButton("Close", null).show()
    }

    private fun showBrowserMenu() {
        val choices = arrayOf(
            if (desktopMode) "Use mobile website" else "Request desktop website",
            if (memorySaver) "Disable memory saver" else "Enable memory saver",
            if (thirdPartyCookiesEnabled) "Block third-party cookies" else "Allow third-party cookies",
            "Clear browsing data", "Browser engine information", "Make Yehaw default browser", "New private window", "Search engine", "App permissions"
        )
        AlertDialog.Builder(this).setTitle("Browser settings").setItems(choices) { _, which ->
            when (which) {
                0 -> {
                    desktopMode = !desktopMode
                    browserPreferences.edit().putBoolean("desktop_mode", desktopMode).apply()
                    tabs.forEach { tab ->
                        tab.webView.settings.userAgentString = if (desktopMode) DESKTOP_USER_AGENT else WebSettings.getDefaultUserAgent(this)
                        tab.webView.reload()
                    }
                }
                1 -> {
                    memorySaver = !memorySaver
                    browserPreferences.edit().putBoolean("memory_saver", memorySaver).apply()
                    tabs.forEachIndexed { index, tab ->
                        tab.webView.settings.loadsImagesAutomatically = !memorySaver
                        if (index != activeTabIndex) tab.webView.onPause()
                        else tab.webView.onResume()
                    }
                    Toast.makeText(this, if (memorySaver) "Memory saver enabled." else "Memory saver disabled.", Toast.LENGTH_SHORT).show()
                }
                2 -> {
                    thirdPartyCookiesEnabled = !thirdPartyCookiesEnabled
                    browserPreferences.edit().putBoolean("third_party_cookies", thirdPartyCookiesEnabled).apply()
                    tabs.forEach { CookieManager.getInstance().setAcceptThirdPartyCookies(it.webView, thirdPartyCookiesEnabled) }
                    Toast.makeText(this, if (thirdPartyCookiesEnabled) "Third-party cookies allowed." else "Third-party cookies blocked.", Toast.LENGTH_SHORT).show()
                }
                3 -> AlertDialog.Builder(this).setTitle("Clear browsing data?")
                    .setMessage("This clears history, bookmarks, cookies, cached files and website storage. You may be signed out.")
                    .setPositiveButton("Clear") { _, _ ->
                        browserStore.clearHistory()
                        browserStore.clearBookmarks()
                        CookieManager.getInstance().removeAllCookies(null)
                        CookieManager.getInstance().flush()
                        tabs.forEach {
                            it.webView.clearCache(true)
                            it.webView.clearHistory()
                            it.webView.clearFormData()
                            it.webView.clearSslPreferences()
                        }
                        android.webkit.WebStorage.getInstance().deleteAllData()
                        android.webkit.GeolocationPermissions.getInstance().clearAll()
                        Toast.makeText(this, "Browsing data cleared.", Toast.LENGTH_SHORT).show()
                    }.setNegativeButton("Cancel", null).show()
                4 -> {
                    val pkg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WebView.getCurrentWebViewPackage() else null
                    val message = if (pkg == null) "Android System WebView provider details unavailable."
                        else "Engine: Chromium-based Android System WebView\nProvider: ${pkg.packageName}\nVersion: ${pkg.versionName}\n\nKeep Android System WebView or Chrome updated."
                    AlertDialog.Builder(this).setTitle("Browser engine").setMessage(message).setPositiveButton("OK", null).show()
                }
                5 -> requestDefaultBrowserRole()
                6 -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        startActivity(Intent(this, PrivateBrowserActivity::class.java))
                    } else {
                        Toast.makeText(this, "Isolated private browsing requires Android 9 or later.", Toast.LENGTH_LONG).show()
                    }
                }
                7 -> {
                    val engines = SEARCH_ENGINES.keys.toList()
                    AlertDialog.Builder(this)
                        .setTitle("Choose search engine")
                        .setSingleChoiceItems(engines.toTypedArray(), engines.indexOf(searchEngine)) { dialog, which ->
                            searchEngine = engines[which]
                            browserPreferences.edit().putString("search_engine", searchEngine).apply()
                            Toast.makeText(this, "Search engine: " + searchEngine, Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                8 -> {
                    val settingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                    startActivity(settingsIntent)
                }
            }
        }.show()
    }

    @Deprecated("Platform activity result API retained for Android 6+ compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_FILE_CHOOSER) {
            val result = if (resultCode == RESULT_OK && data != null)
                WebChromeClient.FileChooserParams.parseResult(resultCode, data) else null
            uploadCallback?.onReceiveValue(result)
            uploadCallback = null
        }
        if (requestCode == REQUEST_BROWSER_ROLE) {
            val held = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_BROWSER)
            Toast.makeText(this, if (held) "Yehaw is now the default browser." else "Default browser selection was not changed.", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Uses WebView history before finishing.")
    override fun onBackPressed() {
        if (fullScreenView != null) {
            hideFullScreenView()
            return
        }
        val webView = currentWebView()
        if (webView?.canGoBack() == true) webView.goBack()
        else if (tabs.size > 1) closeCurrentTab()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        tabs.forEach {
            it.webView.stopLoading()
            it.webView.loadUrl("about:blank")
            it.webView.destroy()
        }
        tabs.clear()
        if (fullScreenView != null) hideFullScreenView()
        uploadCallback?.onReceiveValue(null)
        uploadCallback = null
        pendingWebPermission?.deny()
        pendingWebPermission = null
        pendingGeoCallback?.invoke(pendingGeoOrigin, false, false)
        pendingGeoCallback = null
        browserStore.close()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_BROWSER_ROLE = 4102
        private const val REQUEST_FILE_CHOOSER = 4103
        private const val REQUEST_SITE_PERMISSION = 4104
        private const val REQUEST_GEO_PERMISSION = 4105
        private const val DESKTOP_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val HOME_URL = "https://yehawapp.vercel.app/"
        private val SEARCH_ENGINES = linkedMapOf(
            "Google" to "https://www.google.com/search?q=",
            "DuckDuckGo" to "https://duckduckgo.com/?q=",
            "Bing" to "https://www.bing.com/search?q=",
            "Brave" to "https://search.brave.com/search?q="
        )
    }
}
