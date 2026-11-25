package co.yehaw.browser.util

import java.net.URI
import java.net.URLEncoder

/** Pure-Kotlin URL helpers (no Android deps, unit-testable). Never rewrites a URL the user asked for. */
object UrlUtils {
    const val DEFAULT_SEARCH = "https://www.google.com/search?q="

    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:(?!\\d)")
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/.*)?$")
    private val HOSTLIKE = Regex("^[\\p{L}\\p{N}-]+(\\.[\\p{L}\\p{N}-]+)+(:\\d+)?([/?#].*)?$")

    /** Turns whatever the user typed into a loadable URL: address as-is (https:// added) or a search. */
    fun resolveInput(raw: String, searchTemplate: String = DEFAULT_SEARCH): String {
        val text = raw.trim()
        if (text.isEmpty()) return ""
        if (text.contains(' ')) return search(text, searchTemplate)
        if (SCHEME.containsMatchIn(text)) {
            val lower = text.lowercase()
            return if (lower.startsWith("http://") || lower.startsWith("https://") ||
                lower.startsWith("file://") || lower.startsWith("about:")
            ) text else search(text, searchTemplate)
        }
        if (text.startsWith("localhost") || IPV4.matches(text)) return "http://$text"
        if (HOSTLIKE.matches(text)) return "https://$text"
        return search(text, searchTemplate)
    }

    fun search(query: String, template: String = DEFAULT_SEARCH): String =
        template + URLEncoder.encode(query, "UTF-8")

    fun isHttp(url: String?): Boolean {
        if (url == null) return false
        val l = url.lowercase()
        return l.startsWith("http://") || l.startsWith("https://")
    }

    fun isHttps(url: String?): Boolean = url?.lowercase()?.startsWith("https://") == true

    /** Lower-case host without port, or null. */
    fun host(url: String?): String? = try {
        if (url.isNullOrBlank()) null else URI(url).host?.lowercase()
    } catch (e: Exception) {
        null
    }

    fun displayHost(url: String?): String = host(url)?.removePrefix("www.") ?: (url ?: "")
}
