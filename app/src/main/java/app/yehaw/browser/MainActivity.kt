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
import android.os.Message
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
        var currentUrl: String = HOME_URL
    )

    private lateinit var addressBar: EditText
    private lateinit var pageContainer: LinearLayout
    private lateinit var statusText: TextView
    private val tabs = mutableListOf<BrowserTab>()
    private lateinit var browserStore: BrowserStore
    private var uploadCallback: ValueCallback<Array<Uri>>? = null
    private var desktopMode = false
    private var memorySaver = false
    private var pendingWebPermission: PermissionRequest? = null
    private var pendingWebResources: Array<String> = emptyArray()
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private var pendingGeoOrigin: String? = null
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
        root.addView(controls)

        val libraryControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(2), 0, dp(2), dp(4))
        }
        libraryControls.addView(actionButton("Save") { saveBookmark() })
        libraryControls.addView(actionButton("Bookmarks") { showSavedItems(true) })
        libraryControls.addView(actionButton("History") { showSavedItems(false) })
        libraryControls.addView(actionButton("Find") { findOnPage() })
        libraryControls.addView(actionButton("Share") { shareCurrentPage() })
        libraryControls.addView(actionButton("More") { showBrowserMenu() })
        root.addView(libraryControls)

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
            loadsImagesAutomatically = true
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
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)

        webView.webChromeClient = object : WebChromeClient() {
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

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true && view === currentWebView()) {
                    statusText.text = "Page could not be loaded. Check your connection."
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
        val destination = if (raw.contains(" ") || (!raw.contains(".") && !raw.startsWith("http"))) {
            "https://www.google.com/search?q=" + Uri.encode(raw)
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
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            Toast.makeText(this, "This link type is not supported on this device.", Toast.LENGTH_SHORT).show()
        }
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
        tabs[index].webView.onResume()
        pageContainer.addView(tabs[index].webView)
        addressBar.setText(tabs[index].currentUrl)
        addressBar.setSelection(addressBar.text.length)
        statusText.text = tabs[index].title
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
            "Clear browsing data", "Browser engine information", "Make Yehaw default browser", "New private window"
        )
        AlertDialog.Builder(this).setTitle("Browser settings").setItems(choices) { _, which ->
            when (which) {
                0 -> {
                    desktopMode = !desktopMode
                    currentWebView()?.settings?.userAgentString =
                        if (desktopMode) DESKTOP_USER_AGENT else WebSettings.getDefaultUserAgent(this)
                    currentWebView()?.reload()
                }
                1 -> {
                    memorySaver = !memorySaver
                    tabs.forEachIndexed { index, tab ->
                        tab.webView.settings.loadsImagesAutomatically = !memorySaver
                        if (index != activeTabIndex) tab.webView.onPause()
                        else tab.webView.onResume()
                    }
                    Toast.makeText(this, if (memorySaver) "Memory saver enabled." else "Memory saver disabled.", Toast.LENGTH_SHORT).show()
                }
                2 -> AlertDialog.Builder(this).setTitle("Clear browsing data?")
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
                        Toast.makeText(this, "Browsing data cleared.", Toast.LENGTH_SHORT).show()
                    }.setNegativeButton("Cancel", null).show()
                3 -> {
                    val pkg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WebView.getCurrentWebViewPackage() else null
                    val message = if (pkg == null) "Android System WebView provider details unavailable."
                        else "Engine: Chromium-based Android System WebView\nProvider: ${pkg.packageName}\nVersion: ${pkg.versionName}\n\nKeep Android System WebView or Chrome updated."
                    AlertDialog.Builder(this).setTitle("Browser engine").setMessage(message).setPositiveButton("OK", null).show()
                }
                4 -> requestDefaultBrowserRole()
                5 -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        startActivity(Intent(this, PrivateBrowserActivity::class.java))
                    } else {
                        Toast.makeText(this, "Isolated private browsing requires Android 9 or later.", Toast.LENGTH_LONG).show()
                    }
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
    }
}
