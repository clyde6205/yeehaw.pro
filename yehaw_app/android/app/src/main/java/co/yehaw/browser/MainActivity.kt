package co.yehaw.browser

import android.Manifest
import android.app.SearchManager
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import co.yehaw.browser.archive.PageArchiver
import co.yehaw.browser.browser.ContainerManager
import co.yehaw.browser.browser.Tab
import co.yehaw.browser.browser.TabHost
import co.yehaw.browser.browser.TabKind
import co.yehaw.browser.data.ArchiveEntity
import co.yehaw.browser.data.DownloadEntity
import co.yehaw.browser.data.DownloadState
import co.yehaw.browser.ui.Row
import co.yehaw.browser.ui.RowListDialog
import co.yehaw.browser.util.DomainUtils
import co.yehaw.browser.util.UrlUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Single activity: browser chrome (URL pill + bottom bar) around whichever tab's WebView is active. */
class MainActivity : AppCompatActivity(), TabHost {

    private lateinit var graph: AppGraph
    private val tabs get() = graph.tabs

    private lateinit var browserColumn: View
    private lateinit var webContainer: ViewGroup
    private lateinit var fullscreenContainer: ViewGroup
    private lateinit var urlField: EditText
    private lateinit var securityIcon: ImageView
    private lateinit var reloadButton: ImageButton
    private lateinit var backButton: ImageButton
    private lateinit var forwardButton: ImageButton
    private lateinit var progressBar: ProgressBar
    private lateinit var tabsCount: TextView

    private val tabsDialog by lazy { RowListDialog(this) }
    private val listDialog by lazy { RowListDialog(this) }
    private var downloadsJob: Job? = null

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val fileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCallback = null
    }
    private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        pushStartPageState()
        if (isDefaultBrowser()) toast(R.string.default_set)
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        graph = (application as YeeHawApp).graph
        setContentView(R.layout.activity_main)
        bindViews()
        setupChrome()
        tabs.attachActivity(this, this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack(this)
        })

        lifecycleScope.launch {
            if (!tabs.sessionRestored) tabs.restoreSession()
            if (tabs.tabs.isEmpty()) tabs.openTab(null)
            if (savedInstanceState == null) handleIntent(intent)
            showActiveWebView()
            refreshChrome()
            maybeShowOnboarding()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        lifecycleScope.launch { handleIntent(intent) }
    }

    override fun onStart() {
        super.onStart()
        tabs.attachActivity(this, this)
        tabs.setForeground(true)
        tabs.ensureActiveLive()
        showActiveWebView()
        refreshChrome()
        graph.downloads.resumePending()
    }

    override fun onStop() {
        tabs.setForeground(false)
        tabs.persistSession()
        super.onStop()
    }

    override fun onDestroy() {
        downloadsJob?.cancel()
        tabsDialog.dismiss()
        listDialog.dismiss()
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        webContainer.removeAllViews()
        tabs.detachActivity(this)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ view setup

    private fun bindViews() {
        browserColumn = findViewById(R.id.browserColumn)
        webContainer = findViewById(R.id.webContainer)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        urlField = findViewById(R.id.urlField)
        securityIcon = findViewById(R.id.securityIcon)
        reloadButton = findViewById(R.id.reloadButton)
        backButton = findViewById(R.id.backButton)
        forwardButton = findViewById(R.id.forwardButton)
        progressBar = findViewById(R.id.progress)
        tabsCount = findViewById(R.id.tabsCount)
    }

    private fun setupChrome() {
        urlField.setOnEditorActionListener { _, actionId, event ->
            val go = actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (go) submitUrl(urlField.text.toString())
            go
        }
        urlField.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                val t = tabs.activeTab
                urlField.setText(if (t == null || t.kind != TabKind.WEB) "" else t.url)
                urlField.post { urlField.selectAll() }
            } else {
                refreshChrome()
            }
        }
        reloadButton.setOnClickListener {
            val wv = tabs.activeTab?.webView ?: return@setOnClickListener
            if (progressBar.visibility == View.VISIBLE) wv.stopLoading() else wv.reload()
        }
        backButton.setOnClickListener {
            val t = tabs.activeTab ?: return@setOnClickListener
            when {
                t.canGoBack -> t.webView?.goBack()
                t.openerId != null || t.kind == TabKind.ARCHIVE -> tabs.close(t)
            }
        }
        forwardButton.setOnClickListener { tabs.activeTab?.webView?.takeIf { it.canGoForward() }?.goForward() }
        findViewById<View>(R.id.homeButton).setOnClickListener { tabs.activeTab?.let { tabs.goHome(it) } }
        findViewById<View>(R.id.tabsButton).setOnClickListener { showTabs() }
        findViewById<View>(R.id.menuButton).setOnClickListener { showMenu(it) }
    }

    private fun submitUrl(text: String) {
        hideKeyboard()
        urlField.clearFocus()
        val t = tabs.activeTab ?: return
        tabs.navigate(t, text)
    }

    private fun handleBack(callback: OnBackPressedCallback) {
        val t = tabs.activeTab
        when {
            customView != null -> exitFullscreen(notify = true)
            urlField.hasFocus() -> { hideKeyboard(); urlField.clearFocus() }
            t?.canGoBack == true -> t.webView?.goBack()
            t != null && (t.openerId != null || t.kind == TabKind.ARCHIVE) -> tabs.close(t)
            else -> {
                callback.isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                callback.isEnabled = true
            }
        }
    }

    // ------------------------------------------------------------------ chrome state

    private fun showActiveWebView() {
        val wv = tabs.activeTab?.webView
        if (wv == null) {
            webContainer.removeAllViews()
            return
        }
        if (wv.parent !== webContainer) {
            (wv.parent as? ViewGroup)?.removeView(wv)
            webContainer.removeAllViews()
            webContainer.addView(wv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun displayFor(tab: Tab): String = when (tab.kind) {
        TabKind.START -> ""
        TabKind.ARCHIVE -> getString(R.string.offline_badge) + " · " + tab.title
        TabKind.WEB -> UrlUtils.displayHost(tab.url)
    }

    private fun refreshChrome() {
        tabsCount.text = tabs.tabs.size.toString()
        val tab = tabs.activeTab ?: return
        if (!urlField.hasFocus()) urlField.setText(displayFor(tab))
        securityIcon.visibility = if (tab.kind == TabKind.START) View.GONE else View.VISIBLE
        securityIcon.setImageResource(if (UrlUtils.isHttps(tab.url)) R.drawable.ic_lock else R.drawable.ic_info)
        val canBack = tab.canGoBack || tab.openerId != null || tab.kind == TabKind.ARCHIVE
        backButton.alpha = if (canBack) 1f else 0.35f
        forwardButton.alpha = if (tab.canGoForward) 1f else 0.35f
        val loading = tab.progress in 1..99
        reloadButton.setImageResource(if (loading) R.drawable.ic_close else R.drawable.ic_refresh)
        progressBar.visibility = if (loading) View.VISIBLE else View.INVISIBLE
        progressBar.progress = tab.progress
        // Banking and wallet pages: block screenshots and the recents thumbnail.
        if (DomainUtils.isProtectedHost(UrlUtils.host(tab.url))) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    // ------------------------------------------------------------------ TabHost

    override fun onTabsChanged() {
        refreshChrome()
        if (tabsDialog.isShowing) tabsDialog.update(tabRows())
    }

    override fun onActiveTabChanged(tab: Tab?) {
        showActiveWebView()
        refreshChrome()
    }

    override fun onTabUpdated(tab: Tab) {
        if (tab === tabs.activeTab) refreshChrome()
    }

    override fun onProgress(tab: Tab, progress: Int) {
        if (tab === tabs.activeTab) refreshChrome()
    }

    override fun onDownloadRequested(tab: Tab, url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        if (!UrlUtils.isHttp(url)) {
            toast(R.string.download_unsupported)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        val cookie = tabs.containers.cookieManager(tab.containerId).getCookie(url)
        val name = graph.downloads.enqueue(url, userAgent, tab.url.takeIf { UrlUtils.isHttp(it) }, cookie, contentDisposition, mimeType)
        Toast.makeText(this, getString(R.string.download_started, name), Toast.LENGTH_SHORT).show()
    }

    override fun onFileChooser(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = callback
        return try {
            fileLauncher.launch(params.createIntent())
            true
        } catch (e: Exception) {
            fileCallback = null
            callback.onReceiveValue(null)
            false
        }
    }

    override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        fullscreenContainer.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fullscreenContainer.visibility = View.VISIBLE
        browserColumn.visibility = View.GONE
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onHideCustomView() = exitFullscreen(notify = false)

    private fun exitFullscreen(notify: Boolean) {
        if (customView == null) return
        val cb = customViewCallback
        customView = null
        customViewCallback = null
        fullscreenContainer.removeAllViews()
        fullscreenContainer.visibility = View.GONE
        browserColumn.visibility = View.VISIBLE
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        if (notify) cb?.onCustomViewHidden()
    }

    override fun onExternalIntent(intent: Intent, fallbackUrl: String?) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            val fb = fallbackUrl?.takeIf { UrlUtils.isHttp(it) }
            if (fb != null) tabs.activeTab?.let { tabs.navigate(it, fb) }
        } catch (e: SecurityException) {
            // Not allowed to start this intent: ignore.
        }
    }

    override fun onMessage(resId: Int) = toast(resId)

    // ------------------------------------------------------------------ start page bridge

    override fun startPageJson(): String = JSONObject().apply {
        put("defaultBrowser", isDefaultBrowser())
        put("blocked", graph.adBlock.blockedTotal)
        put("containersSupported", tabs.containers.isSupported)
        put("containers", JSONArray().apply {
            tabs.containers.all().forEach { put(JSONObject().put("id", it.id).put("label", it.label)) }
        })
    }.toString()

    private fun pushStartPageState() {
        val t = tabs.activeTab ?: return
        if (t.kind == TabKind.START && tabs.isStartPageUrl(t.url)) {
            t.webView?.evaluateJavascript("window.YH&&window.YH.init(${startPageJson()})", null)
        }
    }

    /** Actions from yeehaw:// links; TabWebViewClient only forwards them from the bundled start page. */
    override fun onStartPageAction(tab: Tab, uri: Uri) {
        when (uri.host) {
            "go" -> uri.getQueryParameter("q")?.let { tabs.navigate(tab, it) }
            "open" -> {
                val url = uri.getQueryParameter("url")?.takeIf { UrlUtils.isHttp(it) } ?: return
                val container = uri.getQueryParameter("c")?.takeIf { tabs.containers.exists(it) } ?: tab.containerId
                if (container == tab.containerId) tabs.navigate(tab, url) else tabs.openTab(url, container)
            }
            "container-new" -> {
                val label = uri.getQueryParameter("label").orEmpty()
                val url = uri.getQueryParameter("url")?.takeIf { UrlUtils.isHttp(it) }
                lifecycleScope.launch {
                    if (!tabs.containers.isSupported) {
                        toast(R.string.containers_unsupported)
                        return@launch
                    }
                    val c = tabs.containers.create(label)
                    if (c == null) toast(R.string.containers_unsupported)
                    else if (url != null) tabs.openTab(url, c.id) else pushStartPageState()
                }
            }
            "default-browser" -> requestDefaultBrowser()
            "offline" -> showOfflinePages()
            "downloads" -> showDownloads()
            "tabs" -> showTabs()
        }
    }

    // ------------------------------------------------------------------ intents

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString?.takeIf { UrlUtils.isHttp(it) }?.let { openFromOutside(it) }
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                openFromOutside(UrlUtils.resolveInput(it.trim().lineSequence().first(), graph.settings.searchTemplate))
            }
            Intent.ACTION_WEB_SEARCH -> intent.getStringExtra(SearchManager.QUERY)?.let {
                openFromOutside(UrlUtils.search(it, graph.settings.searchTemplate))
            }
            ACTION_SHOW_DOWNLOADS -> showDownloads()
        }
    }

    /** Opens the exact URL given (never rewritten). Reuses a pristine start-page tab instead of stacking tabs. */
    private fun openFromOutside(url: String) {
        if (url.isBlank()) return
        val t = tabs.activeTab
        if (t != null && t.kind == TabKind.START && tabs.tabs.size == 1) tabs.navigate(t, url) else tabs.openTab(url)
    }

    // ------------------------------------------------------------------ tabs UI

    private fun tabRows(): List<Row> = tabs.tabs.map { t ->
        val host = UrlUtils.displayHost(t.url).takeIf { it.isNotBlank() && t.kind == TabKind.WEB }
        val account = if (t.containerId != ContainerManager.DEFAULT_ID) tabs.containers.labelOf(t.containerId) else null
        Row(
            id = t.id,
            title = t.title.ifBlank { host ?: getString(R.string.home) },
            subtitle = listOfNotNull(host, account).joinToString(" · ").ifBlank { null },
            badge = when {
                t === tabs.activeTab -> getString(R.string.tab_active)
                t.isSleeping -> getString(R.string.tab_sleeping)
                else -> null
            },
            actionLabel = getString(R.string.close),
        )
    }

    private fun showTabs() {
        tabsDialog.show(
            title = getString(R.string.tabs),
            rows = tabRows(),
            emptyText = getString(R.string.no_tabs),
            onClick = { row ->
                tabs.tabs.firstOrNull { it.id == row.id }?.let { tabs.select(it) }
                tabsDialog.dismiss()
            },
            onAction = { row ->
                tabs.tabs.firstOrNull { it.id == row.id }?.let { tabs.close(it) }
                tabsDialog.update(tabRows())
            },
            positive = getString(R.string.new_tab) to { tabs.openTab(null); Unit },
            neutral = getString(R.string.close_all) to { tabs.closeAll() },
        )
    }

    // ------------------------------------------------------------------ menu

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.browser_menu, popup.menu)
        val t = tabs.activeTab
        val isWeb = t?.kind == TabKind.WEB && UrlUtils.isHttp(t.url)
        popup.menu.findItem(R.id.menu_save_offline).isVisible = isWeb
        popup.menu.findItem(R.id.menu_share).isVisible = isWeb
        popup.menu.findItem(R.id.menu_copy_link).isVisible = isWeb
        popup.menu.findItem(R.id.menu_default_browser).isVisible = !isDefaultBrowser()
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_new_tab -> tabs.openTab(null)
                R.id.menu_new_account_tab -> showAccountPicker()
                R.id.menu_save_offline -> saveOffline()
                R.id.menu_offline_pages -> showOfflinePages()
                R.id.menu_downloads -> showDownloads()
                R.id.menu_share -> shareCurrent()
                R.id.menu_copy_link -> copyCurrent()
                R.id.menu_clear_data -> showClearData()
                R.id.menu_settings -> showSettings()
                R.id.menu_default_browser -> requestDefaultBrowser()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    private fun shareCurrent() {
        val t = tabs.activeTab ?: return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t.url), null))
    }

    private fun copyCurrent() {
        val t = tabs.activeTab ?: return
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("url", t.url))
        toast(R.string.link_copied)
    }

    private fun saveOffline() {
        val t = tabs.activeTab ?: return
        graph.archiver.save(t) { result ->
            toast(
                when (result) {
                    PageArchiver.Result.SAVED -> R.string.page_saved
                    PageArchiver.Result.NO_SPACE -> R.string.low_storage
                    else -> R.string.page_save_failed
                }
            )
        }
    }

    // ------------------------------------------------------------------ accounts (containers)

    private fun showAccountPicker() {
        if (!tabs.containers.isSupported) {
            toast(R.string.containers_unsupported)
            return
        }
        val rows = tabs.containers.all().map { Row(it.id, it.label) } + Row(NEW_ACCOUNT_ID, "+ " + getString(R.string.new_account))
        listDialog.show(
            title = getString(R.string.new_account_tab),
            rows = rows,
            emptyText = "",
            onClick = { row ->
                listDialog.dismiss()
                if (row.id == NEW_ACCOUNT_ID) {
                    if (tabs.containers.all().size > ContainerManager.MAX_CONTAINERS) {
                        Toast.makeText(this, getString(R.string.container_limit, ContainerManager.MAX_CONTAINERS), Toast.LENGTH_SHORT).show()
                    } else {
                        promptInput(getString(R.string.new_account), getString(R.string.account_label_hint), "") { label ->
                            lifecycleScope.launch {
                                val c = tabs.containers.create(label)
                                if (c != null) tabs.openTab(null, c.id) else toast(R.string.containers_unsupported)
                            }
                        }
                    }
                } else tabs.openTab(null, row.id)
            },
        )
    }

    // ------------------------------------------------------------------ offline pages / downloads

    private fun showOfflinePages() {
        lifecycleScope.launch {
            val df = DateFormat.getDateInstance(DateFormat.MEDIUM)
            fun toRows(list: List<ArchiveEntity>) = list.map {
                Row(
                    it.id.toString(), it.title,
                    UrlUtils.displayHost(it.url) + " · " + Formatter.formatShortFileSize(this@MainActivity, it.sizeBytes) + " · " + df.format(Date(it.savedAt)),
                    null, getString(R.string.delete),
                )
            }
            var current = graph.archiver.list()
            listDialog.show(
                title = getString(R.string.offline_pages),
                rows = toRows(current),
                emptyText = getString(R.string.no_offline_pages),
                onClick = { row ->
                    current.firstOrNull { it.id.toString() == row.id }?.let {
                        listDialog.dismiss()
                        tabs.openArchive(graph.archiver.fileUrl(it), it.title)
                    }
                },
                onAction = { row ->
                    current.firstOrNull { it.id.toString() == row.id }?.let { e ->
                        lifecycleScope.launch {
                            graph.archiver.delete(e)
                            current = graph.archiver.list()
                            listDialog.update(toRows(current))
                        }
                    }
                },
            )
        }
    }

    private fun downloadRows(list: List<DownloadEntity>): List<Row> = list.map { d ->
        val size = if (d.totalBytes > 0)
            Formatter.formatShortFileSize(this, d.receivedBytes) + " / " + Formatter.formatShortFileSize(this, d.totalBytes)
        else Formatter.formatShortFileSize(this, d.receivedBytes)
        val state = getString(
            when (d.state) {
                DownloadState.QUEUED -> R.string.state_queued
                DownloadState.RUNNING -> R.string.state_running
                DownloadState.PAUSED_USER -> R.string.state_paused
                DownloadState.PAUSED_NETWORK -> R.string.state_waiting_network
                DownloadState.COMPLETED -> R.string.state_done
                else -> R.string.state_failed
            }
        )
        Row(
            d.id.toString(), d.fileName, "$state · $size", null,
            when (d.state) {
                DownloadState.QUEUED, DownloadState.RUNNING, DownloadState.PAUSED_NETWORK -> getString(R.string.pause)
                DownloadState.PAUSED_USER -> getString(R.string.resume)
                DownloadState.FAILED -> getString(R.string.retry)
                else -> null
            }
        )
    }

    private fun showDownloads() {
        downloadsJob?.cancel()
        var latest: List<DownloadEntity> = emptyList()
        listDialog.show(
            title = getString(R.string.downloads),
            rows = emptyList(),
            emptyText = getString(R.string.no_downloads),
            onClick = { row ->
                latest.firstOrNull { it.id.toString() == row.id }?.let { d ->
                    when (d.state) {
                        DownloadState.COMPLETED -> openDownloaded(d)
                        DownloadState.PAUSED_USER, DownloadState.PAUSED_NETWORK -> confirmCancel(d)
                    }
                }
            },
            onAction = { row ->
                latest.firstOrNull { it.id.toString() == row.id }?.let { d ->
                    when (d.state) {
                        DownloadState.QUEUED, DownloadState.RUNNING, DownloadState.PAUSED_NETWORK -> graph.downloads.pause(d.id)
                        DownloadState.PAUSED_USER, DownloadState.FAILED -> graph.downloads.resume(d.id)
                    }
                }
            },
            neutral = getString(R.string.clear_finished) to { graph.downloads.clearFinished() },
        )
        downloadsJob = lifecycleScope.launch {
            graph.downloads.observeAll().collect { list ->
                latest = list
                if (listDialog.isShowing) listDialog.update(downloadRows(list))
            }
        }
    }

    private fun confirmCancel(d: DownloadEntity) {
        AlertDialog.Builder(this).setMessage(R.string.cancel_download_q)
            .setPositiveButton(R.string.yes) { _, _ ->
                graph.downloads.remove(d.id, getExternalFilesDir(null)?.let { File(it, "downloads") })
            }
            .setNegativeButton(R.string.no, null).show()
    }

    private fun openDownloaded(d: DownloadEntity) {
        val uri = d.savedUri?.let { Uri.parse(it) } ?: return
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, d.mimeType ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: ActivityNotFoundException) {
            toast(R.string.cannot_open_file)
        }
    }

    // ------------------------------------------------------------------ settings / data

    private fun showSettings() {
        val s = graph.settings
        val labels = arrayOf(
            getString(R.string.settings_adblock), getString(R.string.settings_datasaver),
            getString(R.string.settings_textonly), getString(R.string.settings_autoplay), getString(R.string.settings_autosleep),
        )
        val checked = booleanArrayOf(s.adBlockEnabled, s.dataSaverEnabled, s.textOnly, s.blockAutoplay, s.autoSleepTabs)
        AlertDialog.Builder(this)
            .setTitle(R.string.settings)
            .setMultiChoiceItems(labels, checked) { _, which, on ->
                when (which) {
                    0 -> s.adBlockEnabled = on
                    1 -> s.dataSaverEnabled = on
                    2 -> s.textOnly = on
                    3 -> s.blockAutoplay = on
                    4 -> s.autoSleepTabs = on
                }
                tabs.applyRuntimeSettings()
            }
            .setNeutralButton(R.string.settings_proxy) { _, _ ->
                promptInput(getString(R.string.settings_proxy), getString(R.string.proxy_hint), s.imageProxyTemplate) { value ->
                    if (value.isBlank() || (value.startsWith("https://") && value.contains("{url}"))) {
                        s.imageProxyTemplate = value
                        toast(R.string.proxy_saved)
                    } else toast(R.string.proxy_invalid)
                }
            }
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun showClearData() {
        val t = tabs.activeTab
        val items = arrayOf(getString(R.string.clear_this_account), getString(R.string.clear_all))
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_title)
            .setItems(items) { _, which ->
                tabs.clearBrowsingData(if (which == 0) (t?.containerId ?: ContainerManager.DEFAULT_ID) else null)
                toast(R.string.cleared)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun promptInput(title: String, hint: String, initial: String, onOk: (String) -> Unit) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_input, null)
        val field = view.findViewById<EditText>(R.id.inputField)
        field.hint = hint
        field.setText(initial)
        AlertDialog.Builder(this).setTitle(title).setView(view)
            .setPositiveButton(R.string.ok) { _, _ -> onOk(field.text.toString().trim()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ default browser

    private fun isDefaultBrowser(): Boolean =
        getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_BROWSER) == true

    private fun requestDefaultBrowser() {
        val rm = getSystemService(RoleManager::class.java)
        when {
            rm == null -> openDefaultAppsSettings()
            rm.isRoleHeld(RoleManager.ROLE_BROWSER) -> toast(R.string.default_set)
            rm.isRoleAvailable(RoleManager.ROLE_BROWSER) -> roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_BROWSER))
            else -> openDefaultAppsSettings()
        }
    }

    private fun openDefaultAppsSettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    /** One-time onboarding. The system role prompt only ever appears after the user taps the button. */
    private fun maybeShowOnboarding() {
        val s = graph.settings
        if (s.onboardingShown || isDefaultBrowser() || isFinishing) return
        s.onboardingShown = true
        AlertDialog.Builder(this)
            .setTitle(R.string.onboarding_title)
            .setMessage(R.string.default_cta_help)
            .setPositiveButton(R.string.default_cta) { _, _ -> requestDefaultBrowser() }
            .setNegativeButton(R.string.not_now, null)
            .show()
    }

    // ------------------------------------------------------------------ helpers

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(urlField.windowToken, 0)
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()

    companion object {
        const val ACTION_SHOW_DOWNLOADS = "co.yehaw.browser.SHOW_DOWNLOADS"
        private const val NEW_ACCOUNT_ID = "__new__"
    }
}
