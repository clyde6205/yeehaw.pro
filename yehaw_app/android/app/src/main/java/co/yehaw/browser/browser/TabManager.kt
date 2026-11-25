package co.yehaw.browser.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.MutableContextWrapper
import android.net.Uri
import android.text.TextUtils
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.annotation.StringRes
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewFeature
import co.yehaw.browser.R
import co.yehaw.browser.data.SettingsStore
import co.yehaw.browser.memory.TabSuspensionManager
import co.yehaw.browser.net.AdBlockInterceptor
import co.yehaw.browser.net.DataSaverClient
import co.yehaw.browser.util.NetworkUtil
import co.yehaw.browser.util.UrlUtils
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.util.UUID

/** Owns every tab and WebView. Lives at application scope so tabs survive Activity recreation. */
class TabManager(
    private val app: Application,
    val settings: SettingsStore,
    val containers: ContainerManager,
    val adBlock: AdBlockInterceptor,
    val dataSaver: DataSaverClient,
    private val suspension: TabSuspensionManager,
    private val scope: CoroutineScope,
) {
    private val _tabs = mutableListOf<Tab>()
    val tabs: List<Tab> get() = _tabs
    var activeTab: Tab? = null
        private set
    var host: TabHost? = null
        private set
    @Volatile var isForeground: Boolean = false
        private set
    var sessionRestored: Boolean = false
        private set

    // WebViews are created against this wrapper so they never pin a destroyed Activity.
    private val contextWrapper = MutableContextWrapper(app)

    val archivesDirPath: String = File(app.filesDir, "archives").apply { mkdirs() }.absolutePath

    val assetLoader: WebViewAssetLoader = WebViewAssetLoader.Builder()
        .setHttpAllowed(false)
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(app))
        .build()

    fun string(@StringRes id: Int): String = app.getString(id)

    // ---------------------------------------------------------------- lifecycle wiring

    fun attachActivity(activity: Activity, host: TabHost) {
        contextWrapper.baseContext = activity
        this.host = host
    }

    fun detachActivity(activity: Activity) {
        if (contextWrapper.baseContext === activity) contextWrapper.baseContext = app
        if (this.host != null && (host as? Activity) === activity) this.host = null
    }

    fun setForeground(foreground: Boolean) {
        isForeground = foreground
        val wv = activeTab?.webView
        if (foreground) {
            wv?.onResume()
            wv?.resumeTimers()
            activeTab?.lastActiveAt = System.currentTimeMillis()
        } else {
            activeTab?.lastActiveAt = System.currentTimeMillis()
            wv?.onPause()
            wv?.pauseTimers()
        }
    }

    // ---------------------------------------------------------------- session

    suspend fun restoreSession() {
        if (sessionRestored) return
        containers.load()
        val rows = suspension.loadAll()
        rows.forEach { e ->
            val container = if (containers.exists(e.containerId)) e.containerId else ContainerManager.DEFAULT_ID
            _tabs.add(Tab(e.id, container, TabKind.fromCode(e.kind)).also {
                it.url = e.url; it.title = e.title; it.scrollY = e.scrollY
                it.lastActiveAt = e.lastActiveAt; it.savedState = e
            })
        }
        sessionRestored = true
        val activeId = rows.firstOrNull { it.isActive }?.id
        val target = _tabs.firstOrNull { it.id == activeId } ?: _tabs.lastOrNull()
        if (target != null) select(target) else openTab(null)
    }

    fun persistSession() {
        adBlock.flushStats()
        val list = _tabs.mapIndexed { i, t ->
            if (t.isLive) suspension.snapshot(t, i, t === activeTab)
            else (t.savedState ?: suspension.snapshot(t, i, t === activeTab)).copy(position = i, isActive = t === activeTab)
        }
        suspension.persistSession(list)
    }

    // ---------------------------------------------------------------- tab operations

    fun openTab(
        url: String?,
        containerId: String = ContainerManager.DEFAULT_ID,
        select: Boolean = true,
        openerId: String? = null,
    ): Tab {
        trimToLimit()
        val isStart = url.isNullOrBlank()
        val tab = Tab(
            UUID.randomUUID().toString(),
            if (containers.exists(containerId)) containerId else ContainerManager.DEFAULT_ID,
            if (isStart) TabKind.START else TabKind.WEB,
        )
        tab.url = if (isStart) START_URL else url!!
        tab.openerId = openerId
        _tabs.add(tab)
        if (select) select(tab) else host?.onTabsChanged()
        return tab
    }

    fun openArchive(fileUrl: String, title: String): Tab {
        trimToLimit()
        val tab = Tab(UUID.randomUUID().toString(), ContainerManager.DEFAULT_ID, TabKind.ARCHIVE)
        tab.url = fileUrl
        tab.title = title
        _tabs.add(tab)
        select(tab)
        return tab
    }

    /** For window.open(): the WebView must exist before the engine hands it the new window. */
    fun openPopupTab(opener: Tab): Tab? {
        if (_tabs.size >= MAX_TABS) return null
        val tab = Tab(UUID.randomUUID().toString(), opener.containerId, TabKind.WEB)
        tab.openerId = opener.id
        createWebView(tab)
        _tabs.add(tab)
        select(tab)
        return tab
    }

    fun select(tab: Tab) {
        val prev = activeTab
        if (prev != null && prev !== tab) {
            prev.lastActiveAt = System.currentTimeMillis()
            prev.webView?.onPause()
        }
        activeTab = tab
        tab.lastActiveAt = System.currentTimeMillis()
        ensureLive(tab)
        tab.webView?.onResume()
        host?.onActiveTabChanged(tab)
        host?.onTabsChanged()
    }

    fun ensureActiveLive() {
        val t = activeTab ?: return
        if (!t.isLive) {
            ensureLive(t)
            t.webView?.onResume()
            host?.onActiveTabChanged(t)
            host?.onTabsChanged()
        }
    }

    fun close(tab: Tab) {
        val idx = _tabs.indexOf(tab)
        if (idx < 0) return
        _tabs.removeAt(idx)
        tab.webView?.let { suspension.teardown(it) }
        tab.webView = null
        suspension.delete(tab.id)
        if (activeTab === tab) {
            activeTab = null
            val next = _tabs.firstOrNull { it.id == tab.openerId }
                ?: _tabs.getOrNull(idx.coerceAtMost(_tabs.size - 1))
            if (next == null) openTab(null) else select(next)
        } else {
            host?.onTabsChanged()
        }
    }

    fun closeAll() {
        _tabs.toList().forEach { t -> t.webView?.let { suspension.teardown(it) }; t.webView = null }
        _tabs.clear()
        activeTab = null
        suspension.deleteAll()
        openTab(null)
    }

    fun closeByWebView(wv: WebView) {
        _tabs.firstOrNull { it.webView === wv }?.let { close(it) }
    }

    fun closeContainerTabs(containerId: String) {
        _tabs.filter { it.containerId == containerId }.forEach { close(it) }
    }

    /** Puts a tab to sleep: state to Room, WebView destroyed. The visible tab is never suspended. */
    fun suspendTab(tab: Tab): Boolean {
        if (!tab.isLive) return false
        if (tab === activeTab && isForeground) return false
        if (tab.kind != TabKind.WEB) {
            // Start page and offline archives are rebuilt from their URL; nothing worth serializing.
            tab.webView?.let { suspension.teardown(it) }
            tab.webView = null
            tab.savedState = null
        } else {
            suspension.suspend(tab, _tabs.indexOf(tab), tab === activeTab)
        }
        host?.onTabsChanged()
        return true
    }

    fun liveTabs(): List<Tab> = _tabs.filter { it.isLive }

    // ---------------------------------------------------------------- navigation

    fun navigate(tab: Tab, input: String) {
        val url = UrlUtils.resolveInput(input, settings.searchTemplate)
        if (url.isEmpty()) return
        if (tab.kind != TabKind.ARCHIVE) tab.kind = TabKind.WEB
        tab.url = url
        ensureLive(tab)
        tab.webView?.loadUrl(url)
        host?.onTabUpdated(tab)
    }

    fun goHome(tab: Tab) {
        tab.kind = TabKind.START
        tab.url = START_URL
        ensureLive(tab)
        tab.webView?.loadUrl(START_URL)
        host?.onTabUpdated(tab)
    }

    fun isStartPageUrl(url: String?): Boolean = url != null && url.startsWith(START_URL_PREFIX)

    // ---------------------------------------------------------------- WebView factory

    private fun ensureLive(tab: Tab) {
        if (tab.webView != null) return
        val wv = createWebView(tab)
        val online = NetworkUtil.isOnline(app)
        when {
            tab.savedState != null && tab.kind == TabKind.WEB -> {
                if (!online) {
                    wv.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                    tab.offlineRestore = true
                }
                suspension.rehydrate(tab, wv)
            }
            tab.kind == TabKind.START || tab.url.isBlank() -> {
                tab.kind = TabKind.START
                tab.url = START_URL
                wv.loadUrl(START_URL)
            }
            else -> {
                if (!online && tab.kind == TabKind.WEB) {
                    wv.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                    tab.offlineRestore = true
                }
                wv.loadUrl(tab.url)
            }
        }
        tab.savedState = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(tab: Tab): WebView {
        val wv = WebView(contextWrapper)
        containers.bind(wv, tab.containerId)
        wv.webViewClient = TabWebViewClient(tab, this)
        wv.webChromeClient = TabChromeClient(tab, this)
        wv.setDownloadListener { url, ua, cd, mime, _ -> host?.onDownloadRequested(tab, url, ua, cd, mime) }
        wv.overScrollMode = WebView.OVER_SCROLL_NEVER
        val s = wv.settings
        val archive = tab.kind == TabKind.ARCHIVE
        s.javaScriptEnabled = !archive
        s.domStorageEnabled = true
        s.allowFileAccess = archive
        s.allowContentAccess = false
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.mediaPlaybackRequiresUserGesture = settings.blockAutoplay
        s.blockNetworkImage = settings.textOnly
        s.setGeolocationEnabled(false)
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.userAgentString = browserUserAgent(s.userAgentString)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(s, true)
        }
        tab.webView = wv
        return wv
    }

    /** Present as a regular mobile browser (drops the "; wv" and "Version/x.y" WebView markers). */
    private fun browserUserAgent(ua: String): String =
        ua.replace("; wv)", ")").replace(Regex(" Version/\\d+\\.\\d+"), "")

    /** Re-applies settings that can change while WebViews are alive. */
    fun applyRuntimeSettings() {
        liveTabs().forEach { t ->
            t.webView?.settings?.let {
                it.blockNetworkImage = settings.textOnly
                it.mediaPlaybackRequiresUserGesture = settings.blockAutoplay
            }
        }
    }

    // ---------------------------------------------------------------- errors / recovery

    fun showError(tab: Tab, title: String, message: String, failingUrl: String) {
        val wv = tab.webView ?: return
        val html = try {
            app.assets.open("startpage/error.html").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "<html><body><h3>{{TITLE}}</h3><p>{{MESSAGE}}</p></body></html>"
        }
        val page = html
            .replace("{{TITLE}}", TextUtils.htmlEncode(title))
            .replace("{{MESSAGE}}", TextUtils.htmlEncode(message))
            .replace("{{HOST}}", TextUtils.htmlEncode(UrlUtils.displayHost(failingUrl)))
            .replace("{{URL}}", TextUtils.htmlEncode(failingUrl))
            .replace("{{RETRY}}", TextUtils.htmlEncode(string(R.string.retry)))
            .replace("{{HINT}}", TextUtils.htmlEncode(string(R.string.err_offline_hint)))
        wv.loadDataWithBaseURL(failingUrl, page, "text/html", "utf-8", failingUrl)
    }

    /** The WebView renderer died (usually OOM on 2 GB phones). Rebuild the tab rather than crash. */
    fun recoverTab(tab: Tab) {
        val wv = tab.webView ?: return
        val url = wv.url?.takeIf { !it.startsWith("data:") } ?: tab.url
        val now = System.currentTimeMillis()
        tab.crashes = if (now - tab.lastCrashAt < 60_000) tab.crashes + 1 else 1
        tab.lastCrashAt = now
        tab.url = url
        suspension.teardown(wv)
        tab.webView = null
        tab.savedState = null
        if (tab === activeTab) {
            ensureLive(tab)
            tab.webView?.onResume()
            if (tab.crashes > 2 && tab.kind == TabKind.WEB) {
                showError(tab, string(R.string.err_title_heavy), string(R.string.err_msg_heavy), url)
            }
            host?.onActiveTabChanged(tab)
        }
        host?.onTabsChanged()
        host?.onMessage(R.string.tab_reloaded)
    }

    fun handleExternalScheme(uri: Uri) {
        val s = uri.toString()
        val intent = try {
            if (s.startsWith("intent:")) {
                Intent.parseUri(s, Intent.URI_INTENT_SCHEME).apply {
                    component = null
                    selector = null
                    addCategory(Intent.CATEGORY_BROWSABLE)
                }
            } else Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        } catch (e: Exception) {
            return
        }
        intent.flags = 0
        host?.onExternalIntent(intent, intent.getStringExtra("browser_fallback_url"))
    }

    // ---------------------------------------------------------------- browsing data

    /** [containerId] null = every container. */
    fun clearBrowsingData(containerId: String?) {
        val ids = if (containerId == null) containers.all().map { it.id } else listOf(containerId)
        ids.forEach { containers.clearStorage(it) }
        _tabs.filter { containerId == null || it.containerId == containerId }.forEach { t ->
            t.webView?.let { it.clearCache(true); it.clearHistory(); it.clearFormData() }
            t.savedState = t.savedState?.copy(webState = null)
        }
        activeTab?.webView?.clearCache(true)
        suspension.clearWebState(containerId)
    }

    private fun trimToLimit() {
        if (_tabs.size < MAX_TABS) return
        val victim = _tabs.firstOrNull { it !== activeTab && !it.isLive } ?: _tabs.firstOrNull { it !== activeTab }
        if (victim != null) close(victim)
    }

    companion object {
        const val MAX_TABS = 24
        const val ASSET_HOST = "appassets.androidplatform.net"
        const val START_URL_PREFIX = "https://$ASSET_HOST/assets/startpage/"
        const val START_URL = "${START_URL_PREFIX}index.html"
    }
}
