package app.yehaw.browser.core

/**
 * Built-in blocking of third-party trackers and ad/analytics scripts, tuned for feed-style sites (Facebook, Instagram,
 * news feeds) on slow phones. Pure Kotlin, no Android types: it only decides, the WebViewClient acts.
 *
 * Safety rules: never touches a main-frame navigation; never blocks the site's own hosts (a page's content and login
 * keep working); the list is small and explicit, so it cannot silently break unrelated sites.
 */
object TrackerPolicy {
    /** Hosts that only exist to collect analytics or serve ads. Matched as the host or any subdomain. */
    private val TRACKER_HOSTS = listOf(
        "google-analytics.com", "googletagmanager.com", "googletagservices.com", "googlesyndication.com",
        "googleadservices.com", "doubleclick.net", "scorecardresearch.com", "hotjar.com", "mixpanel.com",
        "segment.io", "segment.com", "amplitude.com", "adnxs.com", "criteo.com", "taboola.com", "outbrain.com",
        "clarity.ms", "bat.bing.com", "analytics.tiktok.com", "ads-twitter.com", "analytics.twitter.com",
        "pixel.facebook.com", "pixel.wp.com", "stats.wp.com", "quantserve.com", "chartbeat.net"
    )

    /** Facebook-family sites: their own hosts are first party there. */
    private val FACEBOOK_FAMILY = listOf("facebook.com", "fb.com", "fbcdn.net", "messenger.com", "instagram.com", "fb.watch", "facebook.net")

    /** Facebook's tracking SDK/pixel host: third party everywhere except on Facebook itself. */
    private const val FB_SDK_HOST = "connect.facebook.net"

    private fun hostIn(host: String, list: List<String>): Boolean =
        list.any { host == it || host.endsWith(".$it") }

    /**
     * @param pageHost host of the page that issued the request (null when unknown)
     * @param requestHost host being requested
     * @param requestPath path of the request (may be empty)
     */
    fun shouldBlock(pageHost: String?, requestHost: String?, requestPath: String?, isMainFrame: Boolean): Boolean {
        if (isMainFrame) return false
        val host = requestHost?.lowercase()?.trimEnd('.') ?: return false
        val page = pageHost?.lowercase()?.trimEnd('.')
        // Same-site requests are the page's own content.
        if (page != null && (host == page || host.endsWith(".$page") || page.endsWith(".$host"))) return false
        if (hostIn(host, TRACKER_HOSTS)) {
            // pixel.facebook.com is a tracker even for Facebook pages; keep the rest of the family working.
            return true
        }
        val path = (requestPath ?: "").lowercase()
        val onFacebook = page != null && hostIn(page, FACEBOOK_FAMILY)
        if (!onFacebook) {
            if (host == FB_SDK_HOST) return true
            // The Meta pixel image/beacon on other sites: facebook.com/tr or /tr/
            if ((host == "www.facebook.com" || host == "facebook.com") && (path == "/tr" || path.startsWith("/tr/"))) return true
        }
        return false
    }

    /** Sites where feed smoothing (lazy images, no preloaded video) is applied. */
    fun isFeedSite(host: String?): Boolean {
        val h = host?.lowercase()?.trimEnd('.') ?: return false
        return hostIn(h, FACEBOOK_FAMILY.filter { it != "facebook.net" })
    }

    /**
     * Injected after a feed page loads. Marks images/iframes lazy and stops video from preloading, and keeps doing
     * so for items the feed adds while scrolling. Idempotent; touches no data, only loading hints.
     */
    const val FEED_SMOOTHING_JS = "(function(){if(window.__yhFeed)return;window.__yhFeed=1;" +
        "function f(n){try{if(n.tagName==='IMG'){n.loading='lazy';n.decoding='async';}" +
        "else if(n.tagName==='IFRAME'){n.loading='lazy';}" +
        "else if(n.tagName==='VIDEO'){n.preload='none';n.autoplay=false;n.removeAttribute('autoplay');}}catch(e){}}" +
        "function s(r){var l=r.querySelectorAll?r.querySelectorAll('img,iframe,video'):[];for(var i=0;i<l.length;i++)f(l[i]);}" +
        "s(document);new MutationObserver(function(m){for(var i=0;i<m.length;i++){var a=m[i].addedNodes;" +
        "for(var j=0;j<a.length;j++){if(a[j].nodeType===1){f(a[j]);s(a[j]);}}}}).observe(document.documentElement,{childList:true,subtree:true});})();"
}
