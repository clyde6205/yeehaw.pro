package app.yehaw.browser.console

import java.net.URLEncoder

/** Pure search/URL resolution (no Android dependencies). Real URLs are never rewritten. */
object SearchRouting {
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?([/?#].*)?$")
    private val LOCAL = Regex("^localhost(:\\d+)?([/?#].*)?$", RegexOption.IGNORE_CASE)
    private val HOSTLIKE = Regex("^[^\\s/?#:]+\\.[^\\s/?#:]{2,}(:\\d+)?([/?#].*)?$")
    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:(?!\\d)")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Fills {query} and {tag} in a validated template. */
    fun format(template: String, tag: String, query: String): String =
        template.replace("{query}", enc(query)).replace("{tag}", enc(tag))

    /**
     * Turns whatever was typed into a loadable URL: addresses go straight through (https:// added when missing);
     * everything else becomes a search using [template]. Dangerous schemes (javascript:, data:, file: ...) are searched, never loaded.
     */
    fun resolve(input: String, template: String, tag: String): String {
        val t = input.trim()
        if (t.isEmpty()) return ""
        val lower = t.lowercase()
        if (lower.startsWith("https://") || lower.startsWith("http://")) return t
        if (t.any { it.isWhitespace() }) return format(template, tag, t)
        if (SCHEME.containsMatchIn(t)) return format(template, tag, t)
        if (LOCAL.matches(t) || IPV4.matches(t)) return "http://$t"
        if (HOSTLIKE.matches(t)) return "https://$t"
        return format(template, tag, t)
    }
}
