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
import android.os.Handler
import android.os.Looper
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
import app.yehaw.browser.console.ConsoleBootstrap
import app.yehaw.browser.console.DeviceStatusManager
import app.yehaw.browser.console.HealthReporter
import app.yehaw.browser.console.ProEntitlement
import app.yehaw.browser.console.RemoteConfigManager
import app.yehaw.browser.console.SearchRoutingEngine
import app.yehaw.browser.console.ShareManager
import app.yehaw.browser.console.TelemetryManager
import app.yehaw.browser.core.ClipboardWatcher
import app.yehaw.browser.core.DataSaverPolicy
import app.yehaw.browser.core.Guardian
import app.yehaw.browser.core.MarkupScreen
import app.yehaw.browser.core.QuickPasteStore
import app.yehaw.browser.core.ScreenshotTool
import app.yehaw.browser.core.StorageGuard
import app.yehaw.browser.core.RetryScheduler
import app.yehaw.browser.core.SavingsMeter
import app.yehaw.browser.core.SignalLevel
import app.yehaw.browser.core.SignalMonitor
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
        /** False when the failed request was not a GET (e.g. a form POST): never re-sent automatically. */
        var failedWasGet: Boolean = true,
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
    private var pendingDownload: Array<String?>? = null
    private var lastRxSample = -1L
    private var sessionDataUsed = 0L
    private var sessionOfflineSaved = 0L
    private val statsHandler = Handler(Looper.getMainLooper())
    private val statsTicker = object : Runnable {
        override fun run() {
            sampleDataUsage()
            statsHandler.postDelayed(this, STATS_REFRESH_INTERVAL_MS)
        }
    }
    private val activityManager by lazy { getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager }
    private lateinit var signalMonitor: SignalMonitor
    private lateinit var savings: SavingsMeter
    private val retryScheduler = RetryScheduler()
    private val loadStartedAt = java.util.WeakHashMap<WebView, Long>()
    // A load that neither finishes nor errors (stalled DNS or a dead connection on a weak network) must not spin forever.
    private val stallWatch = java.util.WeakHashMap<WebView, Runnable>()
    private var startedSafe = false
    private var adaptiveSaver = true
    private var systemSaverOn = false
    private lateinit var clipboardWatcher: ClipboardWatcher
    private lateinit var quickPaste: QuickPasteStore
    private var billingClient: com.android.billingclient.api.BillingClient? = null
    private var proProductDetails: com.android.billingclient.api.ProductDetails? = null
    /** How many tabs may keep a live WebView (and its renderer memory) at once. */
    private val liveTabLimit: Int by lazy {
        BrowserPerformancePolicy.maxLiveTabs(activityManager.isLowRamDevice, activityManager.memoryClass)
    }
    /** Total tabs allowed. Suspended tabs hold only a URL, title and scroll offset, so this can be generous. */
    private val maxTabs = BrowserPerformancePolicy.MAX_TOTAL_TABS

    /** Suspend the oldest background tabs until no more than [liveTabLimit] WebViews are alive. Nothing is deleted. */
    private fun enforceLiveTabLimit() {
        while (tabs.count { !it.evicted } > liveTabLimit) {
            val victim = tabs.filterIndexed { i, t -> !t.evicted && i != activeTabIndex }.firstOrNull() ?: return
            discardTabForMemory(victim)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        browserStore = BrowserStoreRepair.open(this)
        savings = SavingsMeter(this)
        signalMonitor = SignalMonitor(this) { level -> onSignalChanged(level) }
        startedSafe = Guardian.shouldStartSafe(this)
        sessionDataUsed = browserPreferences.getLong(PREF_TOTAL_DATA_RECEIVED, 0L).coerceAtLeast(0L)
        sessionOfflineSaved = browserPreferences.getLong(PREF_TOTAL_OFFLINE_BYTES_REUSED, 0L).coerceAtLeast(0L)
        desktopMode = browserPreferences.getBoolean("desktop_mode", false)
        memorySaver = browserPreferences.getBoolean(
            "memory_saver",
            BrowserPerformancePolicy.defaultMemorySaver(activityManager.isLowRamDevice, activityManager.memoryClass)
        )
        preferOfflineCache = browserPreferences.getBoolean("prefer_offline_cache", false)
        // Turbo (blocks all network images) is opt-in; Adaptive Data Saver (default on) skips heavy assets only on weak signal or Android Data Saver.
        turboMode = browserPreferences.getBoolean("turbo_mode", false)
        adaptiveSaver = browserPreferences.getBoolean("adaptive_saver", true)
        clipboardWatcher = ClipboardWatcher(this)
        quickPaste = QuickPasteStore(this)
        ConsoleBootstrap.init(this)
        window.decorView.post { maybeAskAnalyticsConsent() }
        thirdPartyCookiesEnabled = browserPreferences.getBoolean("third_party_cookies", false)
        searchEngine = browserPreferences.getString("search_engine", "Google") ?: "Google"
        if (searchEngine !in SEARCH_ENGINES.keys) searchEngine = "Google"
        buildInterface()
        CookieManager.getInstance().setAcceptCookie(true)
        val restoredTabs = if (startedSafe) false else restoreTabs(savedInstanceState)
        if (startedSafe) {
            Toast.makeText(this, "Safe start after a problem. Your saved pages and bookmarks are untouched.", Toast.LENGTH_LONG).show()
        }
        signalMonitor.start()
        statsHandler.postDelayed({ Guardian.markStable(this) }, 30_000L)
        if (!restoredTabs) handleIncomingIntent(intent)
        if (tabs.isEmpty()) createTab(HOME_URL)
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
        Guardian.setForeground(false)
        savings.flush()
        statsHandler.removeCallbacks(statsTicker)
        persistSession()
        tabs.filterNot { it.evicted }.forEach { it.webView.onPause() }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        Guardian.setForeground(true)
        systemSaverOn = DataSaverPolicy.systemDataSaverOn(this)
        ConsoleBootstrap.onResume()
        reportHealthAndStatus()
        currentWebView()?.onResume()
        statsHandler.removeCallbacks(statsTicker)
        statsHandler.post(statsTicker)
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
                restoredView.loadSaving(entry.url)
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
        libraryControls.addView(actionButton("Apps") { showAppsHub() })
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
            contentDescription = when (label) {
                "‹" -> "Back"
                "›" -> "Forward"
                "↻" -> "Reload"
                "+" -> "New tab"
                "GO" -> "Go to address"
                else -> null
            }
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
        enforceLiveTabLimit()
        webView.loadSaving(url)
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
            loadsImagesAutomatically = !memorySaver && !turboMode
            blockNetworkImage = turboMode
            cacheMode = if (preferOfflineCache) WebSettings.LOAD_CACHE_ELSE_NETWORK else WebSettings.LOAD_DEFAULT
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            // Pre-rasterizing off-screen content increases memory use; keep it disabled for low-RAM phones.
            offscreenPreRaster = false
            setSupportMultipleWindows(true)
            allowFileAccess = false
            allowContentAccess = true // User-selected content:// URIs are required for file uploads.
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
        }
        applySignalPolicy(webView.settings)
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
                        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                            grantPendingGeoPermission(true)
                        } else requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_GEO_PERMISSION)
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
                enforceLiveTabLimit()
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
                if (RemoteConfigManager.shouldBlock(request?.url)) {
                    return RemoteConfigManager.blockedResponse(request?.url?.host, request?.isForMainFrame == true)
                }
                if ((turboMode || signalMonitor.level.textFirst || DataSaverPolicy.shouldBlockHeavy(adaptiveSaver, signalMonitor.level, systemSaverOn)) && request?.isForMainFrame != true) {
                    val path = (uri.path ?: "").lowercase()
                    if (SavingsMeter.isHeavyAsset(path)) {
                        savings.recordSkipped(path)
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
                if (view != null && !isStartPageUrl(url)) {
                    loadStartedAt[view] = android.os.SystemClock.elapsedRealtime()
                    armStallWatch(view, tab)
                }
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
                if (view != null) ConsoleBootstrap.onPageFinished(view, url)
                val tab = tabs.firstOrNull { it.webView === view }
                if (view != null && tab != null && !tab.failed) {
                    cancelStallWatch(view)
                    val started = loadStartedAt.remove(view)
                    if (started != null) signalMonitor.recordLoad(android.os.SystemClock.elapsedRealtime() - started, true)
                    retryScheduler.reset(view)
                }
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
                val lostUrl = tabs[index].currentUrl
                val lostTitle = tabs[index].title
                retryScheduler.reset(view)
                pageContainer.removeView(view)
                tabs.removeAt(index)
                view.destroy()
                val rescue = isWebUrl(lostUrl) && Guardian.allowRenderRescue()
                if (rescue) {
                    // Rebuild the lost tab in the same position with a fresh renderer and reload its page.
                    val fresh = WebView(this@MainActivity)
                    configureWebView(fresh)
                    tabs.add(index.coerceAtMost(tabs.size), BrowserTab(webView = fresh, title = lostTitle, currentUrl = lostUrl))
                    activeTabIndex = index.coerceAtMost(tabs.lastIndex)
                    showTab(activeTabIndex)
                    fresh.loadUrl(lostUrl)
                    Guardian.noteRepair(this@MainActivity)
                    Toast.makeText(this@MainActivity, "That page ran out of memory. It was restarted automatically.", Toast.LENGTH_LONG).show()
                    return true
                }
                if (tabs.isEmpty()) {
                    activeTabIndex = -1
                    createTab(HOME_URL)
                } else {
                    if (activeTabIndex > index) activeTabIndex--
                    else if (activeTabIndex == index) activeTabIndex = -1
                    showTab(if (activeTabIndex < 0) index.coerceAtMost(tabs.lastIndex) else activeTabIndex)
                }
                Toast.makeText(this@MainActivity, "A page used too much memory and was closed.", Toast.LENGTH_LONG).show()
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
                    tab.failedWasGet = request.method.equals("GET", ignoreCase = true)
                    if (tab.failedWasGet) request.url?.toString()?.takeIf { isWebUrl(it) }?.let { tab.currentUrl = it }
                    cancelStallWatch(view)
                    loadStartedAt.remove(view)
                    signalMonitor.recordLoad(0, false)
                    if (view === currentWebView()) showTab(activeTabIndex)
                    if (tab.failedWasGet) {
                        statusText.text = "Page could not be loaded. Retrying automatically…"
                        retryScheduler.schedule(view) { retryTab(tab) }
                    } else {
                        // A form or other non-GET request failed. Re-sending it could repeat an order or payment,
                        // so it is never retried automatically; the user decides.
                        statusText.text = "That request failed. It was not re-sent automatically."
                    }
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
            "apps" -> showAppsHub()
            "shareapp" -> showShareMenu()
            else -> loadHome()
        }
    }

    private fun navigateFromAddressBar() {
        val raw = addressBar.text?.toString()?.trim().orEmpty()
        if (raw.isBlank()) return
        val destination = if (raw.contains(" ") || (!raw.contains(".") && !raw.startsWith("http", true))) {
            val userChose = browserPreferences.contains("search_engine")
            SearchRoutingEngine.resolve(raw, if (userChose) SEARCH_ENGINES.getValue(searchEngine) + "{query}" else null)
        } else {
            val candidate = if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw else "https://$raw"
            val parsed = Uri.parse(candidate)
            if (parsed.scheme !in listOf("http", "https") || parsed.host.isNullOrBlank()) {
                Toast.makeText(this, "Enter a valid website address or search.", Toast.LENGTH_SHORT).show()
                return
            }
            candidate
        }
        currentWebView()?.loadSaving(destination) ?: createTab(destination)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        if (uri.scheme == "http" || uri.scheme == "https") {
            if (tabs.isEmpty()) createTab(uri.toString()) else currentWebView()?.loadSaving(uri.toString())
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
        // Never fill a nearly-full phone. Nothing is deleted: the download is simply not started.
        if (StorageGuard.level(this) == StorageGuard.Level.CRITICAL) {
            Toast.makeText(this, "Not enough free space to download (${StorageGuard.format(StorageGuard.freeBytes(this))} left). Free some space first.", Toast.LENGTH_LONG).show()
            return
        }
        // Android 6-9 (API 23-28) needs WRITE_EXTERNAL_STORAGE to save into the public Downloads folder; ask once.
        if (Build.VERSION.SDK_INT in 23..28 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = arrayOf(url, userAgent, contentDisposition, mimeType)
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_STORAGE_PERMISSION)
            return
        }
        enqueueDownload(url, userAgent, contentDisposition, mimeType, publicFolder = true)
    }

    private fun enqueueDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?, publicFolder: Boolean) {
        try {
            val filename = safeDownloadFilename(URLUtil.guessFileName(url, contentDisposition, mimeType))
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(filename)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            if (publicFolder) request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
            else request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, filename) // needs no permission
            if (!userAgent.isNullOrBlank()) request.addRequestHeader("User-Agent", userAgent)
            CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            Toast.makeText(this, if (publicFolder) "Download started." else "Download started (saved in the app's own folder).", Toast.LENGTH_SHORT).show()
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
        if (restoreUrl != null) enforceLiveTabLimit()
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
                reloadSafely(tab)
            })
            pageContainer.addView(errorPanel, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        } else {
            pageContainer.addView(tab.webView)
            if (restoreUrl != null) tab.webView.loadSaving(restoreUrl)
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
        // Avoid purging WebView's in-memory resource cache during tab eviction.
        // The disk cache remains managed by Android System WebView.
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

    /**
     * Loads [url]; when any data-saving mode is on, asks the site for its lighter version with the standard
     * `Save-Data: on` request header (honoured by many sites; ignored by the rest). Only the page request can
     * carry it: WebView does not let an app add headers to the sub-resources the page then fetches.
     */
    private fun WebView.loadSaving(url: String) {
        // Start the stall timer when the load is requested, not only when the engine reports it: some WebViews never
        // call onPageStarted for a navigation that gets no response, which is exactly the dead-connection case.
        if (isWebUrl(url) && Uri.parse(url).host != START_HOST) {
            tabs.firstOrNull { it.webView === this }?.let { t ->
                t.failed = false
                t.currentUrl = url
                loadStartedAt[this] = android.os.SystemClock.elapsedRealtime()
                armStallWatch(this, t)
            }
        }
        val saving = memorySaver || turboMode || systemSaverOn || (adaptiveSaver && signalMonitor.level.textFirst)
        if (saving && isWebUrl(url) && Uri.parse(url).host != START_HOST) loadUrl(url, mapOf("Save-Data" to "on")) else loadUrl(url)
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
        // Suspend background tabs only under real memory pressure (running low/critical, or the process is
        // a background candidate). TRIM_MEMORY_UI_HIDDEN just means the app left the screen: save state only.
        val pressure = level in ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW..ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) persistSession()
        if (pressure && tabs.size > 1) {
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
            REQUEST_GEO_PERMISSION -> grantPendingGeoPermission(grantResults.any { it == PackageManager.PERMISSION_GRANTED })
            REQUEST_STORAGE_PERMISSION -> {
                val d = pendingDownload
                pendingDownload = null
                // Declined: still deliver the file, into the app's own folder, rather than losing the download.
                if (d != null) enqueueDownload(d[0]!!, d[1], d[2], d[3], publicFolder = granted)
            }
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
        val options = mutableListOf("ChatMe — Tap to Chat (private or groups)")
        options.addAll(apps.map { it.first })
        AlertDialog.Builder(this)
            .setTitle("YeeHaw family apps")
            .setItems(options.toTypedArray()) { _, which ->
                if (which == 0) {
                    openChatMe()
                    return@setItems
                }
                val app = apps.getOrNull(which - 1) ?: return@setItems
                try {
                    val launch = packageManager.getLaunchIntentForPackage(app.second)
                    if (launch != null) startActivity(launch)
                    else Toast.makeText(this, "This app cannot be opened.", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    Toast.makeText(this, "This app cannot be opened.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** ChatMe is the sister messenger: launch it if installed, otherwise open its page. */
    private fun openChatMe() {
        val launch = try {
            packageManager.getLaunchIntentForPackage(CHATME_PACKAGE)
        } catch (_: Exception) {
            null
        }
        if (launch != null) {
            startActivity(launch)
            return
        }
        currentWebView()?.loadUrl(CHATME_URL) ?: createTab(CHATME_URL)
        Toast.makeText(this, "ChatMe is not installed yet — opening chatme.pro to get it.", Toast.LENGTH_LONG).show()
    }

    private fun isProMode(): Boolean = ProEntitlement.isPro(this)

    /**
     * YeeHaw Pro: a Google Play subscription. Pro is granted ONLY after the YeeHaw server confirms the
     * purchase token with Google Play (signed answer); no local flag or client callback unlocks it.
     * The free tier keeps every tool working.
     */
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
            .setMessage("Pro is not available to buy yet. The free browser keeps every tool working. Pro opens only after Google Play confirms a purchase with the YeeHaw server.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun setupBilling() {
        if (billingClient != null) return
        val purchasesUpdatedListener = com.android.billingclient.api.PurchasesUpdatedListener { result, purchases ->
            if (result.responseCode == com.android.billingclient.api.BillingClient.BillingResponseCode.OK && !purchases.isNullOrEmpty()) {
                handlePurchases(purchases)
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
                if (result.responseCode == com.android.billingclient.api.BillingClient.BillingResponseCode.OK) { queryProProduct(); restorePurchases() }
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

    /**
     * Measures this app UID's received network bytes. The first sample after process start is
     * a baseline; subsequent positive deltas are persisted across launches. This includes all
     * app traffic (not just page payloads), so the UI labels it as app data received.
     */
    private fun sampleDataUsage() {
        try {
            val rx = android.net.TrafficStats.getUidRxBytes(applicationInfo.uid)
            if (rx >= 0) {
                if (lastRxSample >= 0 && rx >= lastRxSample) {
                    val delta = rx - lastRxSample
                    if (delta > 0) {
                        sessionDataUsed = (sessionDataUsed + delta).coerceAtLeast(sessionDataUsed)
                        browserPreferences.edit().putLong(PREF_TOTAL_DATA_RECEIVED, sessionDataUsed).apply()
                    }
                }
                // A lower value indicates a counter reset (for example, after reboot).
                lastRxSample = rx
            }
        } catch (_: Exception) {
            // TrafficStats may be unavailable on a device; retain the last verified totals.
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
        val repairs = Guardian.totalCrashesRecovered(this) + Guardian.totalRepairs(this)
        view.evaluateJavascript(
            "window.yeehawLive && window.yeehawLive('" + signalMonitor.level.label + "'," + signalMonitor.level.ordinal + "," +
                savings.skippedRequests + ",'" + formatDataBytes(savings.estimatedBytesAvoided) + "','" +
                formatDuration(savings.lowSignalSecs) + "'," + repairs + ")",
            null
        )
    }

    private fun formatDuration(seconds: Long): String = when {
        seconds >= 3600 -> (seconds / 3600).toString() + "h " + ((seconds % 3600) / 60) + "m"
        seconds >= 60 -> (seconds / 60).toString() + "m " + (seconds % 60) + "s"
        else -> seconds.toString() + "s"
    }

    /** Adapts every tab to the connection the user actually has. Never claims to improve signal. */
    private fun applySignalPolicy(settings: WebSettings) {
        val lowSignal = signalMonitor.level.textFirst
        settings.cacheMode = if (lowSignal || preferOfflineCache) WebSettings.LOAD_CACHE_ELSE_NETWORK else WebSettings.LOAD_DEFAULT
    }

    private fun onSignalChanged(level: SignalLevel) {
        savings.onSignal(level)
        tabs.filterNot { it.evicted }.forEach { applySignalPolicy(it.webView.settings) }
        statusText.text = when (level) {
            SignalLevel.OFFLINE -> "Offline. Saved pages still open."
            SignalLevel.POOR -> "Weak signal: text-first mode saves your data."
            SignalLevel.FAIR -> "Fair signal."
            SignalLevel.GOOD -> "Signal OK."
        }
        if (level != SignalLevel.OFFLINE) {
            // The connection is back (or usable): resume every page that was interrupted.
            tabs.filter { it.failed && !it.evicted }.forEach { tab -> retryScheduler.runNow(tab.webView) { retryTab(tab) } }
        }
        updateDataStatsDisplay()
    }

    private fun retryTab(tab: BrowserTab) {
        if (!tabs.contains(tab) || tab.evicted || !tab.failed) return
        tab.failed = false
        if (tabs.indexOf(tab) == activeTabIndex) {
            pageContainer.removeAllViews()
            pageContainer.addView(tab.webView)
        }
        reloadSafely(tab)
    }

    private fun cancelStallWatch(view: WebView?) {
        view ?: return
        stallWatch.remove(view)?.let { view.removeCallbacks(it) }
    }

    private fun armStallWatch(view: WebView, tab: BrowserTab, baseline: Int = view.progress) {
        cancelStallWatch(view)
        val r = Runnable {
            stallWatch.remove(view)
            // loadStartedAt is cleared by onPageFinished/onReceivedError, so its absence means the load already ended.
            if (isFinishing || isDestroyed || tab.failed || !loadStartedAt.containsKey(view)) return@Runnable
            // view.progress still holds the PREVIOUS page's value (often 100) until the new load reports, so progress only
            // counts as real when it is past the initial 10% and differs from what it was when the load was requested.
            val p = view.progress
            if (p > 10 && p != baseline) { armStallWatch(view, tab, p); return@Runnable } // slow but advancing (2G, heavy page)
            view.stopLoading()
            loadStartedAt.remove(view)
            tab.failed = true
            tab.failedWasGet = true // the user presses Retry; nothing is re-sent automatically
            signalMonitor.recordLoad(0, false)
            if (view === currentWebView()) {
                showTab(activeTabIndex)
                statusText.text = "This page is taking too long. Tap Retry."
            }
        }
        stallWatch[view] = r
        view.postDelayed(r, LOAD_STALL_MS)
    }

    /** Retry a failed page without ever re-sending a POST: a failed non-GET is replaced by a plain GET of its address. */
    private fun reloadSafely(tab: BrowserTab) {
        // reload() would re-run the last page that actually loaded, which is the wrong page when this navigation never
        // committed (stalled or failed first load). Always load the address that failed, as a plain GET.
        tab.webView.loadSaving(tab.currentUrl)
        tab.failedWasGet = true
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
                val item = items[index]
                AlertDialog.Builder(this)
                    .setTitle(item.title.ifBlank { item.url }.take(80))
                    .setMessage(item.url.take(200))
                    .setPositiveButton("Open") { _, _ -> currentWebView()?.loadSaving(item.url) ?: createTab(item.url) }
                    .setNeutralButton("Remove this one") { _, _ ->
                        if (bookmarks) browserStore.deleteBookmark(item.url) else browserStore.deleteHistoryEntry(item.url)
                        Toast.makeText(this, "Removed.", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNeutralButton(if (bookmarks) "Clear bookmarks" else "Clear history") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle(if (bookmarks) "Delete ALL bookmarks?" else "Delete ALL history?")
                    .setMessage("This cannot be undone.")
                    .setPositiveButton("Delete all") { _, _ ->
                        if (bookmarks) browserStore.clearBookmarks() else browserStore.clearHistory()
                    }
                    .setNegativeButton("Keep them", null)
                    .show()
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
        if (!StorageGuard.canWrite(this, 2L * 1024 * 1024)) {
            Toast.makeText(this, "Storage is almost full. Free some space on your phone, then save this page.", Toast.LENGTH_LONG).show()
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
        val pages = browserStore.offlinePageSummaries()
        if (pages.isEmpty()) {
            Toast.makeText(this, "No offline pages saved yet.", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = pages.map { "${it.title}\n${it.url}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Offline reading library")
            .setItems(labels) { _, index ->
                // Load only the chosen page's text (low-memory safe).
                val full = browserStore.offlinePage(pages[index].id)
                if (full != null) showOfflinePage(full) else Toast.makeText(this, "That offline page is no longer available.", Toast.LENGTH_SHORT).show()
            }
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
        val reusedBytes = BrowserStore.utf8ByteCount(page.content)
        sessionOfflineSaved = (sessionOfflineSaved + reusedBytes).coerceAtLeast(sessionOfflineSaved)
        browserPreferences.edit().putLong(PREF_TOTAL_OFFLINE_BYTES_REUSED, sessionOfflineSaved).apply()
        updateDataStatsDisplay()
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
            "YeeHaw Pro — not yet available",
            if (turboMode) "Turbo mode — ON (blocks network images)" else "Turbo mode — OFF (full images)"
        )
        AlertDialog.Builder(this).setTitle("Browser settings — YeeHaw v0.6.0").setItems(choices) { _, which ->
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
                            tab.webView.settings.loadsImagesAutomatically = !memorySaver && !turboMode
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
                    tabs.filterNot { it.evicted }.forEach { tab ->
                        tab.webView.settings.blockNetworkImage = turboMode
                        tab.webView.settings.loadsImagesAutomatically = !memorySaver && !turboMode
                    }
                    currentWebView()?.reload()
                    Toast.makeText(
                        this,
                        if (turboMode) "Turbo blocks network images to reduce data use. Some pages may look incomplete; turn Turbo off for full images."
                        else "Turbo off: websites can load images normally.",
                        Toast.LENGTH_LONG
                    ).show()
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
        ConsoleBootstrap.onDestroy()
        retryScheduler.cancelAll()
        signalMonitor.stop()
        savings.flush()
        browserStore.close()
        super.onDestroy()
    }



    /** Pro unlocks only after the server confirms the purchase with Google Play; the purchase is acknowledged after that. */
    private fun handlePurchases(purchases: List<com.android.billingclient.api.Purchase>) {
        purchases.filter { it.purchaseState == com.android.billingclient.api.Purchase.PurchaseState.PURCHASED }.forEach { purchase ->
            val product = purchase.products.firstOrNull() ?: PRODUCT_ID_PRO
            ProEntitlement.verify(this, product, purchase.purchaseToken) { verified ->
                when (verified) {
                    true -> {
                        if (!purchase.isAcknowledged) {
                            val ack = com.android.billingclient.api.AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
                            billingClient?.acknowledgePurchase(ack) { }
                        }
                        Toast.makeText(this, "Welcome to YeeHaw Pro. Ads are off.", Toast.LENGTH_LONG).show()
                    }
                    false -> Toast.makeText(this, "Google Play did not confirm this purchase.", Toast.LENGTH_LONG).show()
                    null -> Toast.makeText(this, "Purchase received. Pro will activate when YeeHaw can reach its server.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Restores Pro on a new phone or after a failed check: asks Play for current subscriptions and re-verifies. */
    private fun restorePurchases() {
        val client = billingClient ?: return
        if (!client.isReady) return
        val params = com.android.billingclient.api.QueryPurchasesParams.newBuilder()
            .setProductType(com.android.billingclient.api.BillingClient.ProductType.SUBS).build()
        client.queryPurchasesAsync(params) { result, list ->
            if (result.responseCode == com.android.billingclient.api.BillingClient.BillingResponseCode.OK) {
                runOnUiThread { handlePurchases(list) }
            }
        }
    }

    private fun reportHealthAndStatus() {
        val totals = mapOf(
            "crash" to Guardian.totalCrashesRecovered(this).toLong(),
            "render_rescue" to Guardian.totalRepairs(this).toLong(),
            "skipped_requests" to savings.skippedRequests,
            "est_bytes_avoided" to savings.estimatedBytesAvoided,
            "low_signal_seconds" to savings.lowSignalSecs,
            "data_received_bytes" to sessionDataUsed
        )
        HealthReporter.maybeReport(this, totals)
        val versionCode = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
            else @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, 0).versionCode
        } catch (_: Exception) { 0 }
        DeviceStatusManager.check(this, versionCode) { status ->
            if (isFinishing) return@check
            if (status.blocked) {
                AlertDialog.Builder(this)
                    .setTitle("YeeHaw is paused for this device")
                    .setMessage(status.blockedReason.ifBlank { "Contact support at yeehaw.pro." })
                    .setCancelable(false)
                    .setPositiveButton("Close") { _, _ -> finish() }
                    .show()
            } else if (status.updateAvailable && status.updateUrl.isNotEmpty()) {
                val key = "update_prompt_" + status.latestVersionName
                if (status.updateRequired || !browserPreferences.getBoolean(key, false)) {
                    browserPreferences.edit().putBoolean(key, true).apply()
                    val builder = AlertDialog.Builder(this)
                        .setTitle(if (status.updateRequired) "Update required" else "Update available")
                        .setMessage(status.message.ifBlank { "A newer YeeHaw (${status.latestVersionName}) is ready." })
                        .setPositiveButton("Update") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(status.updateUrl))) }
                    if (!status.updateRequired) builder.setNegativeButton("Later", null) else builder.setCancelable(false)
                    builder.show()
                }
            }
        }
        if (ProEntitlement.needsRecheck(this)) {
            ProEntitlement.storedToken(this)?.let { (product, token) -> ProEntitlement.verify(this, product, token) { } }
        }
    }

    private fun maybeAskAnalyticsConsent() {
        if (!TelemetryManager.isConsentRequired() || browserPreferences.getBoolean("consent_asked", false)) return
        AlertDialog.Builder(this)
            .setTitle("Help improve YeeHaw?")
            .setMessage("Share anonymous app health (crashes, app version, phone memory, region). No browsing data, no ads ID. You can delete it anytime from Tools.")
            .setCancelable(false)
            .setPositiveButton("Share") { _, _ -> TelemetryManager.setConsent(this, true); browserPreferences.edit().putBoolean("consent_asked", true).apply() }
            .setNegativeButton("No thanks") { _, _ -> TelemetryManager.setConsent(this, false); browserPreferences.edit().putBoolean("consent_asked", true).apply() }
            .show()
    }

    private fun showShareMenu() {
        val canApk = ShareManager.canShareApk(this)
        val labels = mutableListOf("QR code (works with no data)", "WhatsApp", "Messenger", "Telegram", "Viber", "SMS", "Other apps…")
        if (canApk) labels.add("Send the app by Bluetooth / Nearby (no data)")
        AlertDialog.Builder(this)
            .setTitle("Share YeeHaw")
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    0 -> ShareManager.showQrDialog(this)
                    1 -> ShareManager.shareVia(this, ShareManager.Channel.WHATSAPP)
                    2 -> ShareManager.shareVia(this, ShareManager.Channel.MESSENGER)
                    3 -> ShareManager.shareVia(this, ShareManager.Channel.TELEGRAM)
                    4 -> ShareManager.shareVia(this, ShareManager.Channel.VIBER)
                    5 -> ShareManager.shareVia(this, ShareManager.Channel.SMS)
                    6 -> ShareManager.shareVia(this, ShareManager.Channel.SYSTEM)
                    else -> ShareManager.shareApk(this)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun confirmForgetMe() {
        AlertDialog.Builder(this)
            .setTitle("Stop sharing and delete my data")
            .setMessage("This deletes YeeHaw's anonymous app-health record for this phone from our server and stops sharing it. Your bookmarks and pages stay on your phone.")
            .setPositiveButton("Delete") { _, _ ->
                TelemetryManager.forgetMe(this) { ok ->
                    runOnUiThread { Toast.makeText(this, if (ok) "Deleted. Nothing more will be shared." else "Could not reach the server. Try again with a connection.", Toast.LENGTH_LONG).show() }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- Apps hub (tools)

    private fun toggleMemorySaver() {
        memorySaver = !memorySaver
        browserPreferences.edit().putBoolean("memory_saver", memorySaver).apply()
        tabs.forEachIndexed { index, tab ->
            if (!tab.evicted) {
                tab.webView.settings.loadsImagesAutomatically = !memorySaver
                if (index != activeTabIndex) tab.webView.onPause() else tab.webView.onResume()
            }
        }
        Toast.makeText(this, if (memorySaver) "Memory Saver on." else "Memory Saver off.", Toast.LENGTH_SHORT).show()
    }

    private fun showAppsHub() {
        val onOff = { value: Boolean -> if (value) "ON" else "OFF" }
        val labels = arrayOf(
            "Memory Saver — ${onOff(memorySaver)}",
            "Adaptive Data Saver — ${onOff(adaptiveSaver)}",
            "Storage Protection",
            "Quick Paste",
            "Clipboard Convenience — ${onOff(clipboardWatcher.enabled)}",
            "Screenshot & Markup",
            "Offline Reading",
            "Stop sharing & delete my data",
            "YeeHaw family apps"
        )
        AlertDialog.Builder(this)
            .setTitle("YeeHaw tools")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> toggleMemorySaver()
                    1 -> {
                        adaptiveSaver = !adaptiveSaver
                        browserPreferences.edit().putBoolean("adaptive_saver", adaptiveSaver).apply()
                        Toast.makeText(
                            this,
                            if (adaptiveSaver) "Adaptive Data Saver on: images and video are skipped on weak signal or when Android Data Saver is on."
                            else "Adaptive Data Saver off.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    2 -> showStorageProtection()
                    3 -> showQuickPaste()
                    4 -> {
                        clipboardWatcher.enabled = !clipboardWatcher.enabled
                        Toast.makeText(
                            this,
                            if (clipboardWatcher.enabled) "Clipboard Convenience on: when you copy a link, YeeHaw offers to open it. Nothing is sent anywhere."
                            else "Clipboard Convenience off.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    5 -> startScreenshotMarkup()
                    6 -> AlertDialog.Builder(this)
                        .setTitle("Offline Reading")
                        .setItems(arrayOf("Save this page for offline reading", "Open my offline library")) { _, choice ->
                            if (choice == 0) saveOfflinePage() else showOfflinePages()
                        }
                        .setNegativeButton("Close", null)
                        .show()
                    7 -> confirmForgetMe()
                    else -> showSisterApps()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showStorageProtection() {
        val level = StorageGuard.level(this)
        val status = when (level) {
            StorageGuard.Level.OK -> "Healthy"
            StorageGuard.Level.LOW -> "Low — consider freeing space"
            StorageGuard.Level.CRITICAL -> "Almost full — YeeHaw will not save new offline pages"
        }
        val message = "Phone free space: ${StorageGuard.format(StorageGuard.freeBytes(this))} of ${StorageGuard.format(StorageGuard.totalBytes(this))}\n" +
            "Status: $status\n\n" +
            "YeeHaw data (bookmarks, history, saved pages): ${StorageGuard.format(StorageGuard.appDataBytes(this))}\n" +
            "Temporary cache: ${StorageGuard.format(StorageGuard.appCacheBytes(this))}\n\n" +
            "YeeHaw never deletes your saved pages, bookmarks or history by itself. Clearing the cache only removes temporary files; sites may load a little slower once."
        AlertDialog.Builder(this)
            .setTitle("Storage Protection")
            .setMessage(message)
            .setPositiveButton("Clear temporary cache") { _, _ ->
                tabs.filterNot { it.evicted }.forEach { it.webView.clearCache(true) }
                Toast.makeText(this, "Temporary cache cleared. Your saved data is untouched.", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showQuickPaste() {
        val items = quickPaste.list()
        val labels = mutableListOf("＋ Add from clipboard")
        labels.addAll(items.map { if (it.length > 60) it.take(60) + "…" else it })
        AlertDialog.Builder(this)
            .setTitle("Quick Paste — tap to insert, hold to delete")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) {
                    val clip = try {
                        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                    } catch (_: Exception) {
                        null
                    }
                    val added = !clip.isNullOrBlank() && quickPaste.add(clip)
                    Toast.makeText(
                        this,
                        if (added) "Saved to Quick Paste." else "Nothing to add (copy some text first; the list holds ${QuickPasteStore.MAX_ITEMS}).",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    insertQuickPaste(items[which - 1])
                }
            }
            .setNegativeButton("Close", null)
            .create().also { dialog ->
                dialog.listView.setOnItemLongClickListener { _, _, position, _ ->
                    if (position > 0) {
                        quickPaste.remove(position - 1)
                        dialog.dismiss()
                        Toast.makeText(this, "Removed.", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                dialog.show()
            }
    }

    private fun insertQuickPaste(text: String) {
        val web = currentWebView()
        if (web == null) {
            copyForPaste(text)
            return
        }
        web.evaluateJavascript(QuickPasteStore.insertScript(text)) { result ->
            if (result != "true") copyForPaste(text)
        }
    }

    private fun copyForPaste(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Quick Paste", text))
        Toast.makeText(this, "Tap a text box first. Copied — long-press the box and choose Paste.", Toast.LENGTH_LONG).show()
    }

    private fun startScreenshotMarkup() {
        val web = currentWebView()
        val shot = if (web != null) ScreenshotTool.capture(web) else null
        if (shot == null) {
            Toast.makeText(this, "Could not take a screenshot right now.", Toast.LENGTH_SHORT).show()
            return
        }
        MarkupScreen.show(this, shot) { message -> Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val link = clipboardWatcher.pendingLink() ?: return
        AlertDialog.Builder(this)
            .setTitle("Open copied link?")
            .setMessage(if (link.length > 120) link.take(120) + "…" else link)
            .setPositiveButton("Open") { _, _ -> createTab(link) }
            .setNegativeButton("No thanks", null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val LOAD_STALL_MS = 25_000L
        private const val STATE_TAB_URLS = "yehaw.saved_tab_urls"
        private const val STATE_TAB_TITLES = "yehaw.saved_tab_titles"
        private const val STATE_TAB_SCROLLS = "yehaw.saved_tab_scrolls"
        private const val STATE_ACTIVE_TAB = "yehaw.saved_active_tab"
        private const val REQUEST_STORAGE_PERMISSION = 4104
        private const val REQUEST_BROWSER_ROLE = 4102
        private const val REQUEST_FILE_CHOOSER = 4103
        private const val REQUEST_SITE_PERMISSION = 4104
        private const val REQUEST_GEO_PERMISSION = 4105
        private const val PRODUCT_ID_PRO = "yeehaw_pro"
        private const val CHROME_USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36"
        private const val DESKTOP_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        // The start page is bundled in the APK and served from memory: no network, instant on any device.
        private const val START_HOST = "start.yeehaw.internal"
        private const val STATS_REFRESH_INTERVAL_MS = 1_000L
        private const val PREF_TOTAL_DATA_RECEIVED = "total_data_received_bytes"
        private const val PREF_TOTAL_OFFLINE_BYTES_REUSED = "total_offline_bytes_reused"
        private const val CHATME_PACKAGE = "com.clyde6205.chatmepro"
        private const val CHATME_URL = "https://chatme.pro"
        private const val HOME_URL = "https://start.yeehaw.internal/"
        private val SEARCH_ENGINES = linkedMapOf(
            "Google" to "https://www.google.com/search?q=",
            "DuckDuckGo" to "https://duckduckgo.com/?q=",
            "Bing" to "https://www.bing.com/search?q=",
            "Brave" to "https://search.brave.com/search?q="
        )
    }
}
