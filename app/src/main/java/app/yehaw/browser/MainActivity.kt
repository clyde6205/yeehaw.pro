package app.yehaw.browser

import android.app.Activity
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
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.URLUtil
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
    private var activeTabIndex = -1
    private val maxTabs = 4
    private var liteModeEnabled = true
    private lateinit var modeButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            singleLine = true
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
        modeButton = actionButton("Lite ON") { toggleLiteMode() }
        controls.addView(modeButton)
        controls.addView(actionButton("Default") { requestDefaultBrowserRole() })
        root.addView(controls)

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
        val initialUrl = toBrowserUrl(url)
        tabs.add(BrowserTab(webView = webView, currentUrl = initialUrl))
        showTab(tabs.lastIndex)
        webView.loadUrl(initialUrl)
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
            supportZoom = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)

        webView.webChromeClient = object : WebChromeClient() {
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
                    "http", "https" -> {
                        if (liteModeEnabled && !isYehawServiceUrl(uri)) {
                            view.loadUrl(toBrowserUrl(uri.toString()))
                            true
                        } else false
                    }
                    else -> {
                        openExternalScheme(uri)
                        true
                    }
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                val tab = tabs.firstOrNull { it.webView === view } ?: return
                if (!url.isNullOrBlank()) {
                    tab.currentUrl = displayUrl(url)
                    if (view === currentWebView()) {
                        addressBar.setText(displayUrl(url))
                        addressBar.setSelection(addressBar.text.length)
                    }
                }
                if (view === currentWebView()) statusText.text = "Loading…"
            }

            override fun onPageFinished(view: WebView?, url: String?) {
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
        currentWebView()?.loadUrl(toBrowserUrl(destination)) ?: createTab(destination)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        if (uri.scheme == "http" || uri.scheme == "https") {
            if (tabs.isEmpty()) createTab(uri.toString()) else currentWebView()?.loadUrl(toBrowserUrl(uri.toString()))
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
        pageContainer.removeAllViews()
        activeTabIndex = index
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


    private fun isYehawServiceUrl(uri: Uri): Boolean =
        uri.host.equals(SERVICE_HOST, ignoreCase = true)

    private fun toBrowserUrl(destination: String): String {
        val uri = Uri.parse(destination)
        val isWeb = uri.scheme.equals("http", true) || uri.scheme.equals("https", true)
        return if (liteModeEnabled && isWeb && !isYehawServiceUrl(uri)) {
            "$SERVICE_ORIGIN/api/render?url=${Uri.encode(destination)}"
        } else destination
    }

    private fun displayUrl(actualUrl: String): String {
        val uri = Uri.parse(actualUrl)
        return if (isYehawServiceUrl(uri) && uri.path == "/api/render") {
            uri.getQueryParameter("url") ?: actualUrl
        } else actualUrl
    }

    private fun toggleLiteMode() {
        val current = currentWebView()?.url?.let { displayUrl(it) }
        liteModeEnabled = !liteModeEnabled
        modeButton.text = if (liteModeEnabled) "Lite ON" else "Direct"
        Toast.makeText(
            this,
            if (liteModeEnabled) "Yehaw Lite mode enabled." else "Direct browsing enabled.",
            Toast.LENGTH_SHORT
        ).show()
        if (!current.isNullOrBlank()) currentWebView()?.loadUrl(toBrowserUrl(current))
    }

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

    @Deprecated("Uses the platform role chooser to remain dependency-free.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_BROWSER_ROLE) {
            val held = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_BROWSER)
            Toast.makeText(
                this,
                if (held) "Yehaw is now your default browser." else "Yehaw was not selected as the default browser.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && tabs.size > 1) {
            val active = currentWebView()
            tabs.filter { it.webView !== active }.forEach {
                it.webView.stopLoading()
                it.webView.clearHistory()
                it.webView.clearCache(false)
            }
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
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_BROWSER_ROLE = 4102
        private const val SERVICE_HOST = "yehawapp.vercel.app"
        private const val SERVICE_ORIGIN = "https://yehawapp.vercel.app"
        private const val HOME_URL = "https://yehawapp.vercel.app/"
    }
}
