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
import android.content.ClipData
import android.content.ClipboardManager
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
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONObject
import org.json.JSONTokener
import android.widget.HorizontalScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Minimal-dependency native browser foundation.
 *
 * WebView uses the Android System WebView engine; the engine is not bundled into this APK.
 * JavaScript is enabled for modern-site compatibility. No JavaScript interfaces are exposed.
 * The start page is a local in-APK asset served through request interception: it needs no network.
 */
class MainActivity : Activity() {
    private data class BrowserTab(
        var webView: WebView,
        var title: String = "New tab",
        var currentUrl: String = HOME_URL,
        var failed: Boolean = false,
        var evicted: Boolean = false,
        var savedScrollY: Int = 0,
        var lastHistoryUrl: String? = null
    )

    private lateinit var brandText: TextView
    private lateinit var addressRow: LinearLayout
    private lateinit var controlsScroll: HorizontalScrollView
    private lateinit var libraryControlsScroll: HorizontalScrollView
    private lateinit var addressBar: EditText
    private lateinit var pageContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var adBanner: com.google.android.gms.ads.AdView
    private val tabs = mutableListOf<BrowserTab>()
    private lateinit var browserStore: BrowserStore
    private val browserPreferences by lazy { getSharedPreferences("browser_settings", Context.MODE_PRIVATE) }
    private var uploadCallback: ValueCallback<Array<Uri>>? = null
    private var desktopMode = false
    private var thirdPartyCookiesEnabled = false
    private var searchEngine = "Google"
    private var memorySaver = false
    private var preferOfflineCache = false
    private var turboMode = true
    private var pendingWebPermission: PermissionRequest? = null
    private var pendingWebResources: Array<String> = emptyArray()
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private var pendingGeoOrigin: String? = null
    private var fullScreenView: View? = null
    private var fullScreenCallback: WebChromeClient.CustomViewCallback? = null
    private var previousSystemUiVisibility = 0
    private var activeTabIndex = -1
    private var lastRxSample = -1L
    private var sessionDataUsed = 0L
    private var sessionOfflineSaved = 0L
    private var billingClient: com.android.billingclient.api.BillingClient? = null
    private var proProductDetails: com.android.billingclient.api.ProductDetails? = null
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
        preferOfflineCache = browserPreferences.getBoolean("prefer_offline_cache", false)
        turboMode = browserPreferences.getBoolean("turbo_mode", true)
        thirdPartyCookiesEnabled = browserPreferences.getBoolean("third_party_cookies", false)
        searchEngine = browserPreferences.getString("search_engine", "Google") ?: "Google"
        if (searchEngine !in SEARCH_ENGINES.keys) searchEngine = "Google"
        buildInterface()
        CookieManager.getInstance().setAcceptCookie(true)
        val restoredTabs = restoreTabs(savedInstanceState)
        if (!restoredTabs) handleIncomingIntent(intent)
        if (tabs.isEmpty()) createTab(HOME_URL)
        try {
            com.google.android.gms.ads.MobileAds.initialize(this) { }
        } catch (_: Exception) {
        }
        try {
            setupBilling()
        } catch (_: Exception) {
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val savedUrls = ArrayList<String>()
        val savedTitles = ArrayList<String>()
        val savedScrolls = ArrayList<Int>()
        var savedActiveIndex = 0
        tabs.forEachIndexed { index, tab ->
            val url = if (tab.evicted) tab.currentUrl else tab.webView.url ?: tab.currentUrl
            if (isWebUrl(url)) {
                if (index == activeTabIndex) savedActiveIndex = savedUrls.size
                savedUrls.add(url.take(4096))
                savedTitles.add(tab.title.take(80))
                savedScrolls.add(if (tab.evicted) tab.savedScrollY else tab.webView.scrollY)
            }
        }
        outState.putStringArrayList(STATE_TAB_URLS, savedUrls)
        outState.putStringArrayList(STATE_TAB_TITLES, savedTitles)
        outState.putIntegerArrayList(STATE_TAB_SCROLLS, savedScrolls)
        outState.putInt(STATE_ACTIVE_TAB, savedActiveIndex)
        persistSession()
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        persistSession()
        tabs.filterNot { it.evicted }.forEach { it.webView.onPause() }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        currentWebView()?.onResume()
    }

    override fun onStop() {
        persistSession()
        super.onStop()
    }

    private fun restoreTabs(state: Bundle?): Boolean {
        val stateUrls = state?.getStringArrayList(STATE_TAB_URLS)
        val stateTitles = state?.getStringArrayList(STATE_TAB_TITLES)
        val stateScrolls = state?.getIntegerArrayList(STATE_TAB_SCROLLS)
        val sessionEntries = if (stateUrls != null) {
            stateUrls.mapIndexedNotNull { index, url ->
                if (isWebUrl(url)) BrowserStore.SessionTab(
                    url,
                    stateTitles?.getOrNull(index)?.take(160)?.ifBlank { "Restored tab" } ?: "Restored tab",
                    stateScrolls?.getOrNull(index)?.coerceAtLeast(0) ?: 0
                ) else null
            }
        } else {
            browserStore.loadSession()?.tabs.orEmpty()
        }
        val entries = sessionEntries.filter { Uri.parse(it.url).host != "yehawapp.vercel.app" }.take(maxTabs)
        if (entries.isEmpty()) return false
        val savedActive = state?.getInt(STATE_ACTIVE_TAB, 0) ?: browserStore.loadSession()?.activeIndex ?: 0
        val activeIndex = savedActive.coerceIn(0, entries.lastIndex)

        entries.forEachIndexed { index, entry ->
            val restoredView = WebView(this)
            configureWebView(restoredView)
            val tab = BrowserTab(webView = restoredView, title = entry.title, currentUrl = entry.url, savedScrollY = entry.scrollY)
            tabs.add(tab)
            if (index == activeIndex) {
                activeTabIndex = index
                pageContainer.addView(restoredView)
                restoredView.loadUrl(entry.url)
            } else {
                restoredView.destroy()
                tab.evicted = true
            }
        }
        val active = tabs[activeIndex]
        addressBar.setText(displayUrl(active.currentUrl))
        addressBar.setSelection(addressBar.text.length)
        updateChromeVisibility(active.currentUrl)
        statusText.text = "Restoring last session…"
        return true
    }

    private fun persistSession() {
        if (!::browserStore.isInitialized || tabs.isEmpty()) return
        val entries = tabs.map { tab ->
            BrowserStore.SessionTab(
                url = if (tab.evicted) tab.currentUrl else tab.webView.url ?: tab.currentUrl,
                title = tab.title,
                scrollY = if (tab.evicted) tab.savedScrollY else tab.webView.scrollY
            )
        }
        browserStore.saveSession(entries, activeTabIndex)
    }

    private fun isWebUrl(value: String): Boolean {
        val uri = Uri.parse(value)
        return uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank()
    }

    private fun isStartPageUrl(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        return Uri.parse(value).host == START_HOST
    }

    /** On the start page the native chrome is fully hidden: exactly one search bar exists. */
    private fun updateChromeVisibility(url: String?) {
        val onHomePage = url.isNullOrBlank() || isStartPageUrl(url)
        val visibility = if (onHomePage) View.GONE else View.VISIBLE
        brandText.visibility = visibility
        addressRow.visibility = visibility
        controlsScroll.visibility = visibility
        libraryControlsScroll.visibility = visibility
        statusText.visibility = visibility
        if (::adBanner.isInitialized) adBanner.visibility = if (onHomePage || isProMode()) View.GONE else View.VISIBLE
    }

    private fun displayUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return if (isStartPageUrl(url)) "" else url
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF080D18.toInt())
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
        }

        brandText = TextView(this).apply {
            text = "YEEHAW  ·  LIGHTWEIGHT BROWSER"
            textSize = 12f
            setTextColor(0xFFB8F36B.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(4))
        }
        root.addView(brandText)

        addressRow = LinearLayout(this).apply {
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
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF18263A.toInt())
            setTextColor(0xFFDCE8F7.toInt())
            setHintTextColor(0xFF7B8BA3.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                    navigateFromAddressBar()
                    true
                } else false
            }
        }
        addressRow.addView(addressBar, LinearLayout.LayoutParams(0, dp(48), 1f))
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
        controlsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(controls)
        }
        root.addView(controlsScroll)

        val libraryControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(2), 0, dp(2), dp(4))
        }
        libraryControls.addView(actionButton("Save") { saveBookmark() })
        libraryControls.addView(actionButton("Find") { findOnPage() })
        libraryControls.addView(actionButton("Share") { shareCurrentPage() })
        libraryControls.addView(actionButton("Save offline") { saveOfflinePage() })
        libraryControls.addView(actionButton("Offline") { showOfflinePages() })
        libraryControls.addView(actionButton("Downloads") { openDownloads() })
        libraryControls.addView(actionButton("Apps") { showSisterApps() })
        libraryControls.addView(actionButton("More") { showBrowserMenu() })
        libraryControlsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(libraryControls)
        }
        root.addView(libraryControlsScroll)

        statusText = TextView(this).apply {
            text = "Ready"
            textSize = 11f
            setTextColor(0xFFA5B4CA.toInt())
            setPadding(dp(12), dp(2), dp(12), dp(4))
            maxLines = 1
        }
        root.addView(statusText)

        adBanner = com.google.android.gms.ads.AdView(this).apply {
            setAdSize(com.google.android.gms.ads.AdSize.BANNER)
            adUnitId = ADMOB_TEST_BANNER_ID
            try {
                loadAd(com.google.android.gms.ads.AdRequest.Builder().build())
            } catch (_: Exception) {
            }
        }
        root.addView(adBanner)

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
        updateChromeVisibility(HOME_URL)
        setContentView(root)
    }

    private fun actionButton(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 11f
            isAllCaps = false
            minWidth = dp(48)
            minHeight = dp(48)
            setPadding(dp(10), 0, dp(10), 0)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF17263B.toInt())
            setTextColor(0xFFDCE8F7.toInt())
            stateListAnimator = null
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
            cacheMode = if (preferOfflineCache) WebSettings.LOAD_CACHE_ELSE_NETWORK else WebSettings.LOAD_DEFAULT
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            offscreenPreRaster = true
            setSupportMultipleWindows(true)
            allowFileAccess = false
            allowContentAccess = true // User-selected content:// URIs are required for file uploads.
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, thirdPartyCookiesEnabled)
        webView.settings.userAgentString = if (desktopMode) DESKTOP_USER_AGENT else CHROME_USER_AGENT

        webView.setOnLongClickListener {
            val hit = webView.hitTestResult
            val linkUrl = hit.extra
            val isLink = hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
            if (!isLink || linkUrl.isNullOrBlank() || !isWebUrl(linkUrl)) {
                false
            } else {
                val actions = arrayOf("Open in new tab", "Open in this tab", "Copy link")
                AlertDialog.Builder(this)
                    .setTitle("Link options")
                    .setItems(actions) { _, which ->
                        when (which) {
                            0 -> createTab(linkUrl)
                            1 -> webView.loadUrl(linkUrl)
                            2 -> {
                                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Website link", linkUrl))
                                Toast.makeText(this, "Link copied.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
        }

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
            // The start page is an in-APK asset: it is served locally and never touches the network.
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val uri = request?.url ?: return null
                if (uri.host == START_HOST) {
                    return try {
                        WebResourceResponse("text/html", "utf-8", assets.open("startpage.html"))
                    } catch (_: Exception) {
                        null
                    }
                }
                if (turboMode) {
                    val path = (uri.path ?: "").lowercase()
                    val heavy = path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") ||
                        path.endsWith(".gif") || path.endsWith(".webp") || path.endsWith(".svg") ||
                        path.endsWith(".ico") || path.endsWith(".mp4") || path.endsWith(".webm") ||
                        path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".ttf") ||
                        path.endsWith(".otf")
                    if (heavy) {
                        return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.host == START_HOST) {
                    // Actions from the start page map directly to real browser functions.
                    if (uri.getQueryParameter("a") != null) {
                        handleStartPageAction(uri)
                        return true
                    }
                    return false
                }
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
                tab.lastHistoryUrl = null
                if (view === currentWebView() && pageContainer.indexOfChild(view) < 0) showTab(activeTabIndex)
                if (!url.isNullOrBlank()) {
                    tab.currentUrl = url
                    if (view === currentWebView()) {
                        addressBar.setText(displayUrl(url))
                        addressBar.setSelection(addressBar.text.length)
                    }
                }
                if (view === currentWebView()) {
                    updateChromeVisibility(url)
                    sampleDataUsage()
                    statusText.text = "Loading…"
                }
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                val tab = tabs.firstOrNull { it.webView === view } ?: return
                if (url.isNullOrBlank() || isStartPageUrl(url)) return
                tab.currentUrl = url
                if (view === currentWebView()) {
                    addressBar.setText(displayUrl(url))
                    addressBar.setSelection(addressBar.text.length)
                    updateChromeVisibility(url)
                }
                if (tab.lastHistoryUrl != url) {
                    browserStore.addHistory(url, tab.title)
                    tab.lastHistoryUrl = url
                }
                persistSession()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val tab = tabs.firstOrNull { it.webView === view }
                if (tab != null && !url.isNullOrBlank() && !isStartPageUrl(url) &&
                    (url.startsWith("https://") || url.startsWith("http://"))) {
                    tab.currentUrl = url
                    if (tab.lastHistoryUrl != url) {
                        browserStore.addHistory(url, tab.title)
                        tab.lastHistoryUrl = url
                    }
                    if (tab.savedScrollY > 0 && view != null) {
                        val restoredView = view
                        val savedY = tab.savedScrollY
                        restoredView.post {
                            restoredView.scrollTo(0, savedY)
                            tab.savedScrollY = 0
                            persistSession()
                        }
                    } else {
                        persistSession()
                    }
                }
                if (view === currentWebView()) {
                    statusText.text = "Ready"
                    sampleDataUsage()
                }
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

            override fun onReceivedSslError(
                view: WebView?,
                handler: android.webkit.SslErrorHandler?,
                error: android.net.http.SslError?
            ) {
                handler?.cancel()
                if (view == null || error?.url != view.url) {
                    if (view === currentWebView()) statusText.text = "Some secure page content was blocked."
                    return
                }
                val tab = tabs.firstOrNull { it.webView === view } ?: return
                tab.failed = true
                if (view === currentWebView()) {
                    showTab(activeTabIndex)
                    statusText.text = "Secure connection blocked."
                }
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

    /** Every start page tile is wired to a real browser function. No placeholders. */
    private fun handleStartPageAction(uri: Uri) {
        when (uri.getQueryParameter("a")) {
            "search" -> {
                val query = uri.getQueryParameter("q").orEmpty().trim()
                if (query.isNotEmpty()) {
                    addressBar.setText(query)
                    navigateFromAddressBar()
                }
            }
            "home" -> loadHome()
            "back" -> currentWebView()?.let { if (it.canGoBack()) it.goBack() }
            "forward" -> currentWebView()?.let { if (it.canGoForward()) it.goForward() }
            "saved" -> showSavedItems(true)
            "history" -> showSavedItems(false)
            "downloads" -> openDownloads()
            "tabs" -> showTabPicker()
            "find" -> findOnPage()
            "newtab" -> createTab(HOME_URL)
            "privacy" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    startActivity(Intent(this, PrivateBrowserActivity::class.java))
                } else {
                    Toast.makeText(this, "Isolated private browsing requires Android 9 or later.", Toast.LENGTH_LONG).show()
                }
            }
            "share" -> shareCurrentPage()
            "save" -> saveBookmark()
            "offline" -> showOfflinePages()
            "more" -> showBrowserMenu()
            "apps" -> showSisterApps()
            "shareapp" -> shareYeeHaw()
            else -> loadHome()
        }
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
        if (scheme == "intent") {
            val parsedIntent = try {
                Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
            } catch (_: Exception) {
                Toast.makeText(this, "Invalid app link.", Toast.LENGTH_SHORT).show()
                return
            }
            val fallback = parsedIntent.getStringExtra("browser_fallback_url")
            parsedIntent.component = null
            parsedIntent.selector = null
            parsedIntent.setPackage(null)
            parsedIntent.action = Intent.ACTION_VIEW
            parsedIntent.flags = 0
            parsedIntent.replaceExtras(android.os.Bundle())
            parsedIntent.categories?.toList()?.forEach { parsedIntent.removeCategory(it) }
            parsedIntent.addCategory(Intent.CATEGORY_BROWSABLE)
            AlertDialog.Builder(this)
                .setTitle("Open another app?")
                .setMessage("This website wants to open an external application. Continue only if you trust the page.")
                .setPositiveButton("Open") { _, _ ->
                    try {
                        startActivity(parsedIntent)
                    } catch (_: Exception) {
                        openHttpFallback(fallback)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
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

    private fun openHttpFallback(value: String?) {
        val fallback = value?.let { Uri.parse(it) }
        if (fallback != null && fallback.scheme in listOf("http", "https") && !fallback.host.isNullOrBlank()) {
            currentWebView()?.loadUrl(fallback.toString())
        } else {
            Toast.makeText(this, "No compatible app or safe web fallback is available.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun downloadFile(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        val uri = Uri.parse(url)
        if (uri.scheme !in listOf("http", "https")) {
            Toast.makeText(this, "Blocked unsupported download URL.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val filename = safeDownloadFilename(URLUtil.guessFileName(url, contentDisposition, mimeType))
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

    private fun safeDownloadFilename(value: String): String {
        val cleaned = value.replace(Regex("[\\/:*?\"<>|\r\n]"), "_").trim().trim('.')
        return cleaned.take(180).ifBlank { "download" }
    }

    private fun showTabPicker() {
        if (tabs.isEmpty()) return
        val labels = tabs.mapIndexed { index, tab ->
            val host = if (isStartPageUrl(tab.currentUrl)) "Start page" else Uri.parse(tab.currentUrl).host ?: "New tab"
            "${index + 1}. ${tab.title}  ·  $host"
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
        var restoreUrl: String? = null
        if (tab.evicted) {
            restoreUrl = tab.currentUrl
            val restoredWebView = WebView(this)
            configureWebView(restoredWebView)
            tab.webView = restoredWebView
            tab.evicted = false
        }
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
            if (restoreUrl != null) tab.webView.loadUrl(restoreUrl)
        }
        addressBar.setText(displayUrl(tab.currentUrl))
        addressBar.setSelection(addressBar.text.length)
        updateChromeVisibility(tab.currentUrl)
        statusText.text = tab.title
        sampleDataUsage()
    }

    private fun discardTabForMemory(tab: BrowserTab) {
        if (tab.evicted) return
        tab.currentUrl = tab.webView.url ?: tab.currentUrl
        tab.savedScrollY = tab.webView.scrollY
        pageContainer.removeView(tab.webView)
        tab.webView.stopLoading()
        tab.webView.onPause()
        tab.webView.clearCache(false)
        tab.webView.destroy()
        tab.evicted = true
    }

    private fun closeCurrentTab() {
        if (tabs.isEmpty() || activeTabIndex !in tabs.indices) return
        val removed = tabs.removeAt(activeTabIndex)
        pageContainer.removeView(removed.webView)
        if (!removed.evicted) {
            removed.webView.stopLoading()
            removed.webView.loadUrl("about:blank")
            removed.webView.destroy()
        }
        if (tabs.isEmpty()) {
            activeTabIndex = -1
            createTab(HOME_URL)
        } else {
            showTab((activeTabIndex - 1).coerceAtLeast(0))
        }
        persistSession()
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
            Toast.makeText(this, "YeeHaw is already the default browser.", Toast.LENGTH_SHORT).show()
            return
        }
        startActivityForResult(roleManager.createRequestRoleIntent(RoleManager.ROLE_BROWSER), REQUEST_BROWSER_ROLE)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && tabs.size > 1) {
            tabs.forEachIndexed { index, tab ->
                if (index != activeTabIndex) discardTabForMemory(tab)
            }
            // Persist the final URL and scroll offsets before the OS can kill this process.
            persistSession()
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

    /** One-tap launcher for the user's other apps — sister-app ecosystem straight from the toolbar. */
    private fun showSisterApps() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { info ->
                val activity = info.activityInfo
                if (activity == null || !activity.enabled || activity.packageName == packageName) null
                else activity.loadLabel(packageManager).toString() to activity.packageName
            }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
        if (apps.isEmpty()) {
            Toast.makeText(this, "No other apps found on this device.", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Open app")
            .setItems(apps.map { it.first }.toTypedArray()) { _, which ->
                try {
                    val launch = packageManager.getLaunchIntentForPackage(apps[which].second)
                    if (launch != null) startActivity(launch)
                    else Toast.makeText(this, "This app cannot be opened.", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    Toast.makeText(this, "This app cannot be opened.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun isProMode(): Boolean = browserPreferences.getBoolean("pro_mode", false)

    /** YeeHaw Pro tier: one-time purchase through Google Play Billing. Free tier stays fully functional forever. */
    private fun openProUpgrade() {
        if (isProMode()) {
            Toast.makeText(this, "YeeHaw Pro is active on this device.", Toast.LENGTH_SHORT).show()
            return
        }
        val product = proProductDetails
        if (product != null && billingClient?.isReady == true) {
            val offerToken = product.subscriptionOfferDetails?.firstOrNull()?.offerToken ?: ""
            val flowParams = com.android.billingclient.api.BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(
                    com.android.billingclient.api.BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(product)
                        .setOfferToken(offerToken)
                        .build()
                ))
                .build()
            billingClient?.launchBillingFlow(this, flowParams)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("YeeHaw Pro")
            .setMessage("Pro removes all ads forever and funds free development. The free tier keeps every tool working forever — nothing is paywalled.\n\nA small monthly subscription, automatically priced for your country by Google Play: UPI in India, M-Pesa in Africa, cards and local wallets worldwide. Cancel anytime in Play Store settings.\n\nPro activates automatically once the store listing is live.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun setupBilling() {
        if (billingClient != null) return
        val purchasesUpdatedListener = com.android.billingclient.api.PurchasesUpdatedListener { result, purchases ->
            if (result.responseCode == com.android.billingclient.api.BillingClient.BillingResponseCode.OK && !purchases.isNullOrEmpty()) {
                browserPreferences.edit().putBoolean("pro_mode", true).apply()
                if (::adBanner.isInitialized) adBanner.visibility = View.GONE
                Toast.makeText(this, "Welcome to YeeHaw Pro. Ads are off forever.", Toast.LENGTH_LONG).show()
            }
        }
        billingClient = com.android.billingclient.api.BillingClient.newBuilder(this)
            .setListener(purchasesUpdatedListener)
            .enablePendingPurchases(
                com.android.billingclient.api.PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
            )
            .build()
        billingClient?.startConnection(object : com.android.billingclient.api.BillingClientStateListener {
            override fun onBillingSetupFinished(result: com.android.billingclient.api.BillingResult) {
                if (result.responseCode == com.android.billingclient.api.BillingClient.BillingResponseCode.OK) queryProProduct()
            }

            override fun onBillingServiceDisconnected() {
            }
        })
    }

    private fun queryProProduct() {
        val client = billingClient ?: return
        val params = com.android.billingclient.api.QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(
                com.android.billingclient.api.QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(PRODUCT_ID_PRO)
                    .setProductType(com.android.billingclient.api.BillingClient.ProductType.SUBS)
                    .build()
            ))
            .build()
        client.queryProductDetailsAsync(params) { result, products ->
            if (result.responseCode == com.android.billingclient.api.BillingClient.BillingResponseCode.OK) {
                proProductDetails = products.firstOrNull { it.productId == PRODUCT_ID_PRO }
            }
        }
    }

    private fun openDownloads() {
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        } catch (_: Exception) {
            Toast.makeText(this, "Downloads app is unavailable on this device.", Toast.LENGTH_SHORT).show()
        }
    }

    /** Real, measured data accounting: network bytes received by this app, plus bytes served from offline storage. */
    private fun sampleDataUsage() {
        try {
            val rx = android.net.TrafficStats.getUidRxBytes(applicationInfo.uid)
            if (rx >= 0) {
                if (lastRxSample >= 0 && rx > lastRxSample) sessionDataUsed += rx - lastRxSample
                lastRxSample = rx
            }
        } catch (_: Exception) {
        }
        updateDataStatsDisplay()
    }

    private fun updateDataStatsDisplay() {
        val view = currentWebView() ?: return
        if (!isStartPageUrl(view.url)) return
        view.evaluateJavascript(
            "window.yeehawStats && window.yeehawStats('" + formatDataBytes(sessionDataUsed) + "','" + formatDataBytes(sessionOfflineSaved) + "')",
            null
        )
    }

    private fun formatDataBytes(bytes: Long): String = when {
        bytes >= 1048576 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1024 -> (bytes / 1024).toString() + " KB"
        else -> bytes.toString() + " B"
    }

    /** One-tap WhatsApp-ready share of the browser itself with a real download link. */
    private fun shareYeeHaw() {
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "YeeHaw Browser — fast, light, and it saves your data. Works great on any Android. Get it free: https://github.com/clyde6205/yeehaw.pro")
        }
        startActivity(Intent.createChooser(share, "Share YeeHaw"))
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
        if (!tab.currentUrl.startsWith("http") || isStartPageUrl(tab.currentUrl)) return
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

    private fun toggleOfflineCachePreference() {
        preferOfflineCache = !preferOfflineCache
        browserPreferences.edit().putBoolean("prefer_offline_cache", preferOfflineCache).apply()
        tabs.filterNot { it.evicted }.forEach { tab ->
            tab.webView.settings.cacheMode = if (preferOfflineCache) WebSettings.LOAD_CACHE_ELSE_NETWORK else WebSettings.LOAD_DEFAULT
        }
        val message = if (preferOfflineCache) {
            "Cached resources are preferred. Only content allowed by the website to be cached may work offline."
        } else {
            "Normal online cache behavior restored."
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun saveOfflinePage() {
        val tab = tabs.getOrNull(activeTabIndex) ?: return
        val webView = currentWebView() ?: return
        val pageUrl = webView.url ?: tab.currentUrl
        if (!isWebUrl(pageUrl) || isStartPageUrl(pageUrl)) {
            Toast.makeText(this, "Only normal web pages can be saved.", Toast.LENGTH_SHORT).show()
            return
        }
        statusText.text = "Saving offline reading copy…"
        webView.evaluateJavascript("(function(){return JSON.stringify({title:document.title||'',text:document.body?document.body.innerText.slice(0,250000):''});})()") { result ->
            try {
                val encoded = JSONTokener(result ?: "null").nextValue() as? String
                    ?: throw IllegalStateException("Page text was unavailable.")
                val snapshot = JSONObject(encoded)
                val text = snapshot.optString("text").trim()
                if (text.isBlank()) throw IllegalStateException("This page has no readable text.")
                val title = snapshot.optString("title").ifBlank { tab.title }
                browserStore.saveOfflinePage(pageUrl, title, text)
                statusText.text = "Saved for offline reading"
                Toast.makeText(this, "Offline reading copy saved on this device.", Toast.LENGTH_LONG).show()
            } catch (error: Exception) {
                statusText.text = "Ready"
                Toast.makeText(this, error.message ?: "Could not save this page.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showOfflinePages() {
        val pages = browserStore.offlinePages()
        if (pages.isEmpty()) {
            Toast.makeText(this, "No offline pages saved yet.", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = pages.map { "${it.title}\n${it.url}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Offline reading library")
            .setItems(labels) { _, index -> showOfflinePage(pages[index]) }
            .setNeutralButton("Clear all") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Delete all offline pages?")
                    .setMessage("Saved offline reading copies will be permanently removed from this device.")
                    .setPositiveButton("Delete all") { _, _ ->
                        browserStore.clearOfflinePages()
                        Toast.makeText(this, "Offline library cleared.", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showOfflinePage(page: BrowserStore.OfflinePage) {
        sessionOfflineSaved += page.content.toByteArray().size
        val content = ScrollView(this).apply {
            isFillViewport = true
            addView(TextView(this@MainActivity).apply {
                text = page.content
                textSize = 16f
                setTextIsSelectable(true)
                setPadding(dp(18), dp(16), dp(18), dp(16))
            })
        }
        AlertDialog.Builder(this)
            .setTitle(page.title)
            .setView(content)
            .setNeutralButton("Delete") { _, _ ->
                browserStore.deleteOfflinePage(page.id)
                Toast.makeText(this, "Offline page deleted.", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("Open website") { _, _ ->
                currentWebView()?.loadUrl(page.url) ?: createTab(page.url)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun findOnPage() {
        val field = EditText(this).apply {
            hint = "Find text on this page"
            isSingleLine = true
        }
        var lastQuery: String? = null
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(actionButton("Previous") {
                val query = field.text.toString()
                val webView = currentWebView()
                if (query.isNotBlank() && webView != null) {
                    if (query != lastQuery) {
                        lastQuery = query
                        webView.findAllAsync(query)
                    } else webView.findNext(false)
                }
            })
            addView(actionButton("Next") {
                val query = field.text.toString()
                val webView = currentWebView()
                if (query.isNotBlank() && webView != null) {
                    if (query != lastQuery) {
                        lastQuery = query
                        webView.findAllAsync(query)
                    } else webView.findNext(true)
                }
            })
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(field)
            addView(controls)
        }
        val dialog = AlertDialog.Builder(this).setTitle("Find in page").setView(content).create()
        dialog.setOnDismissListener { currentWebView()?.clearMatches() }
        dialog.show()
    }

    private fun showBrowserMenu() {
        val choices = arrayOf(
            if (desktopMode) "Use mobile website" else "Request desktop website",
            if (memorySaver) "Disable memory saver" else "Enable memory saver",
            if (thirdPartyCookiesEnabled) "Block third-party cookies" else "Allow third-party cookies",
            "Clear browsing data", "Browser engine information", "Make YeeHaw default browser", "New private window", "Search engine", "App permissions",
            if (isProMode()) "YeeHaw Pro — active" else "Go Pro — ad-free forever",
            if (turboMode) "Turbo mode — ON (fastest, no images/video)" else "Turbo mode — OFF (full sites)"
        )
        AlertDialog.Builder(this).setTitle("Browser settings — YeeHaw v0.4.6").setItems(choices) { _, which ->
            when (which) {
                0 -> {
                    desktopMode = !desktopMode
                    browserPreferences.edit().putBoolean("desktop_mode", desktopMode).apply()
                    tabs.filterNot { it.evicted }.forEach { tab ->
                        tab.webView.settings.userAgentString = if (desktopMode) DESKTOP_USER_AGENT else CHROME_USER_AGENT
                        tab.webView.reload()
                    }
                }
                1 -> {
                    memorySaver = !memorySaver
                    browserPreferences.edit().putBoolean("memory_saver", memorySaver).apply()
                    tabs.forEachIndexed { index, tab ->
                        if (!tab.evicted) {
                            tab.webView.settings.loadsImagesAutomatically = !memorySaver
                            if (index != activeTabIndex) tab.webView.onPause()
                            else tab.webView.onResume()
                        }
                    }
                    Toast.makeText(this, if (memorySaver) "Memory saver enabled." else "Memory saver disabled.", Toast.LENGTH_SHORT).show()
                }
                2 -> {
                    thirdPartyCookiesEnabled = !thirdPartyCookiesEnabled
                    browserPreferences.edit().putBoolean("third_party_cookies", thirdPartyCookiesEnabled).apply()
                    tabs.filterNot { it.evicted }.forEach { CookieManager.getInstance().setAcceptThirdPartyCookies(it.webView, thirdPartyCookiesEnabled) }
                    Toast.makeText(this, if (thirdPartyCookiesEnabled) "Third-party cookies allowed." else "Third-party cookies blocked.", Toast.LENGTH_SHORT).show()
                }
                3 -> AlertDialog.Builder(this).setTitle("Clear browsing data?")
                    .setMessage("This clears history, bookmarks, saved offline reading pages, cookies, cached files and website storage. You may be signed out.")
                    .setPositiveButton("Clear") { _, _ ->
                        browserStore.clearHistory()
                        browserStore.clearBookmarks()
                        browserStore.clearOfflinePages()
                        CookieManager.getInstance().removeAllCookies(null)
                        CookieManager.getInstance().flush()
                        tabs.filterNot { it.evicted }.forEach {
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
                9 -> openProUpgrade()
                10 -> {
                    turboMode = !turboMode
                    browserPreferences.edit().putBoolean("turbo_mode", turboMode).apply()
                    currentWebView()?.reload()
                    Toast.makeText(this, if (turboMode) "Turbo: images and video blocked for the fastest loads." else "Turbo off: full sites with images.", Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, if (held) "YeeHaw is now the default browser." else "Default browser selection was not changed.", Toast.LENGTH_LONG).show()
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
        persistSession()
        tabs.filterNot { it.evicted }.forEach {
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
        private const val STATE_TAB_URLS = "yehaw.saved_tab_urls"
        private const val STATE_TAB_TITLES = "yehaw.saved_tab_titles"
        private const val STATE_TAB_SCROLLS = "yehaw.saved_tab_scrolls"
        private const val STATE_ACTIVE_TAB = "yehaw.saved_active_tab"
        private const val REQUEST_BROWSER_ROLE = 4102
        private const val REQUEST_FILE_CHOOSER = 4103
        private const val REQUEST_SITE_PERMISSION = 4104
        private const val REQUEST_GEO_PERMISSION = 4105
        private const val PRODUCT_ID_PRO = "yeehaw_pro"
        private const val ADMOB_TEST_BANNER_ID = "ca-app-pub-3940256099942544/6300978111"
        private const val CHROME_USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36"
        private const val DESKTOP_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        // The start page is bundled in the APK and served from memory: no network, instant on any device.
        private const val START_HOST = "start.yeehaw.internal"
        private const val HOME_URL = "https://start.yeehaw.internal/"
        private val SEARCH_ENGINES = linkedMapOf(
            "Google" to "https://www.google.com/search?q=",
            "DuckDuckGo" to "https://duckduckgo.com/?q=",
            "Bing" to "https://www.bing.com/search?q=",
            "Brave" to "https://search.brave.com/search?q="
        )
    }
}
