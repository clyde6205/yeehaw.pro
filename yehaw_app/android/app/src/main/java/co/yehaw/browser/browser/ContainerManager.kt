package co.yehaw.browser.browser

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import co.yehaw.browser.data.ContainerDao
import co.yehaw.browser.data.ContainerEntity
import java.util.UUID

/**
 * Isolated web containers backed by androidx.webkit Profiles (WebView 110+). Each profile owns its own
 * cookies, DOM storage, cache and service workers, so sellers can run several Shopee/Facebook accounts
 * side by side. Android's global CookieManager cannot be partitioned, so Profiles are the only real way;
 * on WebViews without MULTI_PROFILE only the Main container exists.
 */
class ContainerManager(private val context: Context, private val dao: ContainerDao) {

    val isSupported: Boolean get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    private val cache = mutableListOf<ContainerEntity>()

    suspend fun load() {
        cache.clear()
        cache.add(ContainerEntity(DEFAULT_ID, "Main", 0L))
        if (isSupported) {
            dao.getAll().filter { it.id != DEFAULT_ID }.forEach {
                cache.add(it)
                runCatching { ProfileStore.getInstance().getOrCreateProfile(profileName(it.id)) }
            }
        }
    }

    fun all(): List<ContainerEntity> = cache.toList()
    fun labelOf(id: String): String = cache.firstOrNull { it.id == id }?.label ?: "Main"
    fun exists(id: String): Boolean = cache.any { it.id == id }

    fun profileName(id: String): String =
        if (id == DEFAULT_ID) ProfileStore.DEFAULT_PROFILE_NAME else "yh_$id"

    /** Must run before the WebView loads anything. */
    fun bind(webView: WebView, containerId: String) {
        if (!isSupported || containerId == DEFAULT_ID) return
        try {
            WebViewCompat.setProfile(webView, profileName(containerId))
        } catch (e: Exception) {
            // Profile missing or WebView already navigated: fall back to default rather than crash.
        }
    }

    fun cookieManager(containerId: String): CookieManager {
        if (isSupported && containerId != DEFAULT_ID) {
            val p = runCatching { ProfileStore.getInstance().getProfile(profileName(containerId)) }.getOrNull()
            if (p != null) return p.cookieManager
        }
        return CookieManager.getInstance()
    }

    suspend fun create(label: String): ContainerEntity? {
        if (!isSupported || cache.size > MAX_CONTAINERS) return null
        val entity = ContainerEntity(UUID.randomUUID().toString().take(8), label.trim().take(24).ifBlank { "Account" }, System.currentTimeMillis())
        return try {
            ProfileStore.getInstance().getOrCreateProfile(profileName(entity.id))
            dao.upsert(entity)
            cache.add(entity)
            entity
        } catch (e: Exception) {
            null
        }
    }

    /** Caller must have destroyed every WebView bound to this container first. */
    suspend fun delete(id: String): Boolean {
        if (id == DEFAULT_ID) return false
        runCatching { ProfileStore.getInstance().deleteProfile(profileName(id)) }
        dao.delete(id)
        cache.removeAll { it.id == id }
        return true
    }

    /** Clears cookies and DOM storage of one container (cache/history are cleared per WebView by the caller). */
    fun clearStorage(id: String) {
        if (isSupported && id != DEFAULT_ID) {
            val p = runCatching { ProfileStore.getInstance().getProfile(profileName(id)) }.getOrNull()
            p?.cookieManager?.removeAllCookies(null)
            p?.cookieManager?.flush()
            p?.webStorage?.deleteAllData()
        } else {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        }
    }

    companion object {
        const val DEFAULT_ID = "default"
        const val MAX_CONTAINERS = 6
    }
}
