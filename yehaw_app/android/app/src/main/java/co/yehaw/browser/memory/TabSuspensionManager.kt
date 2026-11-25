package co.yehaw.browser.memory

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Parcel
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import co.yehaw.browser.browser.Tab
import co.yehaw.browser.browser.TabKind
import co.yehaw.browser.data.TabStateDao
import co.yehaw.browser.data.TabStateEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Serializes a tab (URL, title, scroll, WebView navigation back-stack) into Room and tears the WebView down
 * so a sleeping tab costs ~0 MB, then re-hydrates it on demand. All WebView calls must be on the main thread;
 * database writes are dispatched to IO.
 */
class TabSuspensionManager(
    private val context: Context,
    private val dao: TabStateDao,
    private val scope: CoroutineScope,
) {
    private val webViewVersion: String? by lazy {
        runCatching { WebViewCompat.getCurrentWebViewPackage(context)?.versionName }.getOrNull()
    }

    /** Main thread. Captures everything needed to rebuild the tab. */
    fun snapshot(tab: Tab, position: Int, isActive: Boolean): TabStateEntity {
        val wv = tab.webView
        var blob: ByteArray? = null
        if (wv != null && tab.kind == TabKind.WEB) {
            val bundle = Bundle()
            if (wv.saveState(bundle) != null) blob = marshall(bundle)
        } else if (tab.kind == TabKind.WEB) {
            blob = tab.savedState?.webState
        }
        val url = wv?.url?.takeIf { it.isNotBlank() && !it.startsWith("data:") } ?: tab.url
        val title = wv?.title?.takeIf { it.isNotBlank() } ?: tab.title
        return TabStateEntity(
            id = tab.id, containerId = tab.containerId, kind = tab.kind.code,
            url = url, title = title, scrollY = wv?.scrollY ?: tab.scrollY,
            position = position, lastActiveAt = tab.lastActiveAt, isActive = isActive,
            webState = blob, sdkInt = Build.VERSION.SDK_INT, webViewVersion = webViewVersion,
        )
    }

    /** Main thread. Snapshots, destroys the WebView and persists asynchronously. */
    fun suspend(tab: Tab, position: Int, isActive: Boolean): Boolean {
        val wv = tab.webView ?: return false
        val entity = snapshot(tab, position, isActive)
        tab.savedState = entity
        tab.url = entity.url
        tab.title = entity.title
        tab.scrollY = entity.scrollY
        teardown(wv)
        tab.webView = null
        scope.launch(Dispatchers.IO) { dao.upsert(entity) }
        return true
    }

    /**
     * Restores history and page into a fresh [webView]. Prefers the in-memory snapshot (the async DB write may
     * still be in flight). Falls back to loading the URL when the blob is missing, too old, or from a different
     * Android/WebView build (state blobs are not guaranteed stable across upgrades).
     */
    fun rehydrate(tab: Tab, webView: WebView): Boolean {
        val st = tab.savedState ?: return false
        tab.pendingScrollY = st.scrollY
        val blob = st.webState
        if (blob != null && st.sdkInt == Build.VERSION.SDK_INT && st.webViewVersion == webViewVersion) {
            val bundle = unmarshall(blob)
            if (bundle != null && webView.restoreState(bundle) != null) {
                tab.savedState = null
                return true
            }
        }
        webView.loadUrl(st.url)
        tab.savedState = null
        return true
    }

    fun teardown(wv: WebView) {
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.stopLoading()
        wv.webChromeClient = null
        wv.webViewClient = WebViewClient()
        wv.setDownloadListener(null)
        wv.removeAllViews()
        wv.destroy()
    }

    suspend fun loadAll(): List<TabStateEntity> = dao.getAll()

    fun persistSession(entities: List<TabStateEntity>) {
        scope.launch(Dispatchers.IO) { dao.replaceAll(entities) }
    }

    fun delete(id: String) {
        scope.launch(Dispatchers.IO) { dao.delete(id) }
    }

    fun deleteAll() {
        scope.launch(Dispatchers.IO) { dao.deleteAll() }
    }

    fun clearWebState(containerId: String?) {
        scope.launch(Dispatchers.IO) {
            if (containerId == null) dao.clearAllWebState() else dao.clearWebState(containerId)
        }
    }

    private fun marshall(bundle: Bundle): ByteArray? {
        val p = Parcel.obtain()
        return try {
            p.writeBundle(bundle)
            p.marshall().takeIf { it.size <= MAX_BLOB_BYTES }
        } catch (e: Exception) {
            null
        } finally {
            p.recycle()
        }
    }

    private fun unmarshall(bytes: ByteArray): Bundle? {
        val p = Parcel.obtain()
        return try {
            p.unmarshall(bytes, 0, bytes.size)
            p.setDataPosition(0)
            p.readBundle(WebView::class.java.classLoader)
        } catch (e: Exception) {
            null
        } finally {
            p.recycle()
        }
    }

    companion object {
        private const val MAX_BLOB_BYTES = 512 * 1024
    }
}
