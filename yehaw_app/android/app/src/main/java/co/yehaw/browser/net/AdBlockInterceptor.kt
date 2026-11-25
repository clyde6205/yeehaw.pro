package co.yehaw.browser.net

import android.content.Context
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import co.yehaw.browser.data.SettingsStore
import co.yehaw.browser.util.DomainUtils
import co.yehaw.browser.util.UrlUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Fast request filter used inside WebViewClient.shouldInterceptRequest().
 * 1) Host match against the compiled hash index (parent-domain aware).
 * 2) Third-party-only path heuristics for ad servers and tracking pixels.
 * Never blocks main-frame navigations and stays out of the way on financial pages.
 */
class AdBlockInterceptor(
    private val context: Context,
    private val settings: SettingsStore,
) {
    @Volatile private var index: BlocklistIndex = BlocklistIndex.EMPTY
    private val blockedSession = AtomicLong(0)
    private val unsaved = AtomicLong(0)

    val blockedTotal: Long get() = settings.blockedTotal + unsaved.get()

    fun loadAsync(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            try {
                context.assets.open("blocklist/hosts.bin").use { index = BlocklistIndex.load(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Blocklist unavailable, ad blocking limited to path rules", e)
            }
        }
    }

    fun flushStats() {
        val n = unsaved.getAndSet(0)
        if (n > 0) settings.blockedTotal = settings.blockedTotal + n
    }

    fun shouldBlock(
        requestUrl: String,
        pageHost: String?,
        isMainFrame: Boolean,
        accept: String? = null,
    ): Boolean {
        if (!settings.adBlockEnabled || isMainFrame) return false
        if (!UrlUtils.isHttp(requestUrl)) return false
        if (DomainUtils.isProtectedHost(pageHost)) return false
        val host = UrlUtils.host(requestUrl) ?: return false
        val thirdParty = DomainUtils.isThirdParty(host, pageHost)

        // Host list: applies to first- and third-party (a listed ad host loaded by itself is still an ad host).
        if (thirdParty && index.matchesHostOrParent(host)) return true
        if (!thirdParty) return false

        val lower = requestUrl.lowercase()
        if (AD_PATH_FRAGMENTS.any { lower.contains(it) }) return true
        val isImage = accept?.contains("image/") == true || IMAGE_EXT.any { lower.substringBefore('?').endsWith(it) }
        if (isImage && PIXEL_FRAGMENTS.any { lower.contains(it) }) return true
        return false
    }

    /** Returns an empty 403 response if blocked, otherwise null (let the WebView load normally). */
    fun intercept(request: WebResourceRequest, pageUrl: String?): WebResourceResponse? {
        val url = request.url.toString()
        val block = shouldBlock(
            url, UrlUtils.host(pageUrl), request.isForMainFrame, request.requestHeaders["Accept"]
        )
        if (!block) return null
        blockedSession.incrementAndGet()
        if (unsaved.incrementAndGet() >= 100) flushStats()
        return WebResourceResponse(
            "text/plain", "utf-8", 403, "Blocked by YeeHaw", emptyMap(), ByteArrayInputStream(ByteArray(0))
        )
    }

    companion object {
        private const val TAG = "AdBlock"
        private val IMAGE_EXT = arrayOf(".gif", ".png", ".jpg", ".jpeg", ".webp")
        private val AD_PATH_FRAGMENTS = arrayOf(
            "/adserver", "/ad_server", "/adframe", "/ads/banner", "/banner_ads", "/pagead/",
            "/adsbygoogle", "/prebid", "/adsystem/", "/ad-delivery", "/advert/", "/sponsored-ads"
        )
        private val PIXEL_FRAGMENTS = arrayOf(
            "/pixel", "/beacon", "/track/", "/tracking/", "/collect?", "/impression", "/__utm.gif", "/b/ss/"
        )
    }
}
