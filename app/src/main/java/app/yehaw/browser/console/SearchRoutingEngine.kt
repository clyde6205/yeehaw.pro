package app.yehaw.browser.console

/**
 * Hook between your search bar / start-page form and the WebView.
 *
 *   webView.loadUrl(SearchRoutingEngine.resolve(typedText))
 *
 * The revenue-sharing template from [RemoteConfigManager] is used only when the user has not picked a different
 * engine in Settings; pass [userEngineTemplate] (an https URL containing {query}) to honour an explicit choice.
 */
object SearchRoutingEngine {
    fun resolve(input: String, userEngineTemplate: String? = null): String {
        val cfg = RemoteConfigManager.current
        return if (userEngineTemplate != null && ConfigRules.isValidSearchTemplate(userEngineTemplate)) {
            SearchRouting.resolve(input, userEngineTemplate, "")
        } else {
            SearchRouting.resolve(input, cfg.defaultSearchUrl, cfg.affiliateTag)
        }
    }

    fun buildSearchUrl(query: String): String {
        val cfg = RemoteConfigManager.current
        return SearchRouting.format(cfg.defaultSearchUrl, cfg.affiliateTag, query)
    }
}
