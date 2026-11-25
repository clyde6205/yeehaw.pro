package app.yehaw.browser.console

import java.net.URI
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64 as JBase64

/**
 * Pure, dependency-free rules shared by the managers (and unit-testable on a plain JVM).
 * Everything that arrives from the network is checked here before it can influence the browser.
 */
object ConfigRules {

    private val HOST_RE = Regex("^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")

    private fun b64(s: String): ByteArray = try {
        android.util.Base64.decode(s, android.util.Base64.DEFAULT)
    } catch (_: Throwable) {
        JBase64.getMimeDecoder().decode(s) // plain JVM (unit tests)
    }

    /** ECDSA P-256 / SHA-256 (DER signature) over the exact response bytes. Never throws. */
    fun verifySignature(publicKeyB64: String, body: ByteArray, signatureB64: String): Boolean = try {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(b64(publicKeyB64.trim())))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(body)
            verify(b64(signatureB64.trim()))
        }
    } catch (_: Throwable) {
        false
    }

    private fun publicHttpsHost(url: String): Boolean = try {
        val u = URI(url)
        val h = u.host?.lowercase()
        u.scheme == "https" && u.userInfo == null && h != null && h != "localhost" &&
            !h.contains(':') && !Regex("^\\d+(\\.\\d+){3}$").matches(h) && !h.endsWith(".local") && !h.endsWith(".internal")
    } catch (_: Throwable) {
        false
    }

    /** https template with {query} exactly once and no placeholder other than {tag}. */
    fun isValidSearchTemplate(t: String): Boolean {
        if (t.length > 500 || t.split("{query}").size != 2) return false
        val left = Regex("\\{[^}]*\\}").findAll(t.replace("{query}", "")).map { it.value }.toList()
        if (left.any { it != "{tag}" }) return false
        return publicHttpsHost(t.replace("{query}", "x").replace("{tag}", "x"))
    }

    fun isValidHttpsUrl(url: String): Boolean = url.length <= 600 && publicHttpsHost(url)

    fun normalizeDomain(raw: String): String? {
        val d = raw.trim().lowercase().removePrefix("*.").trimEnd('.')
        return if (HOST_RE.matches(d)) d else null
    }

    /** True if [host] or any parent domain (a.b.c.com -> b.c.com -> c.com) is in [blocked]. */
    fun isHostBlocked(host: String?, blocked: Set<String>): Boolean {
        if (host.isNullOrEmpty() || blocked.isEmpty()) return false
        var h = host.lowercase().trimEnd('.')
        while (h.contains('.')) {
            if (h in blocked) return true
            h = h.substringAfter('.')
        }
        return false
    }
}
