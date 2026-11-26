package app.yehaw.browser.tools

/**
 * Safe handling of scanned QR text. Nothing is ever opened automatically. Only plain http(s) web links can be offered to
 * the browser, and only after the person sees the real address; every other kind of content is shown as text.
 */
object QrContent {
    enum class Kind { WEB_LINK, UNSAFE_LINK, TEXT }

    class Result(val kind: Kind, val text: String, val host: String? = null, val warnings: List<Warning> = emptyList())

    enum class Warning { NOT_ENCRYPTED, LOOKALIKE_HOST, ADDRESS_IS_NUMBERS, HIDDEN_LOGIN_PART }

    private val BLOCKED_SCHEMES = setOf(
        "javascript", "data", "file", "content", "intent", "market", "android-app", "vbscript", "blob", "about", "ftp", "chrome",
    )

    fun classify(raw: String): Result {
        val text = raw.trim()
        val scheme = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):").find(text)?.groupValues?.get(1)?.lowercase()
        if (scheme == null) return Result(Kind.TEXT, text)
        if (scheme == "http" || scheme == "https") {
            if (text.any { it.isWhitespace() || it.code < 32 }) return Result(Kind.UNSAFE_LINK, text)
            val authority = text.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#')
            if (authority.isEmpty()) return Result(Kind.UNSAFE_LINK, text)
            val warnings = mutableListOf<Warning>()
            if (scheme == "http") warnings += Warning.NOT_ENCRYPTED
            val hasUserInfo = authority.contains('@')
            if (hasUserInfo) warnings += Warning.HIDDEN_LOGIN_PART
            val hostPort = authority.substringAfterLast('@')
            val host = if (hostPort.startsWith("[")) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':')
            if (host.isBlank()) return Result(Kind.UNSAFE_LINK, text)
            if (host.split('.').any { it.startsWith("xn--") } || host.any { it.code > 127 }) warnings += Warning.LOOKALIKE_HOST
            if (Regex("^[0-9.]+$").matches(host) || host.startsWith("[")) warnings += Warning.ADDRESS_IS_NUMBERS
            return Result(Kind.WEB_LINK, text, host.lowercase(), warnings)
        }
        if (scheme in BLOCKED_SCHEMES) return Result(Kind.UNSAFE_LINK, text)
        // tel:, mailto:, sms:, geo:, WIFI:, BEGIN:VCARD ... are shown as text; the person decides what to do with them.
        return Result(Kind.TEXT, text)
    }
}
