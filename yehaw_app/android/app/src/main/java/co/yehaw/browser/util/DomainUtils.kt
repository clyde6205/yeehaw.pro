package co.yehaw.browser.util

/** Domain helpers incl. a PH-aware registrable-domain guess and the financial-host allow-list. */
object DomainUtils {
    private val TWO_LEVEL = setOf(
        "com.ph", "net.ph", "org.ph", "gov.ph", "edu.ph", "mil.ph", "ngo.ph",
        "co.uk", "com.au", "co.jp", "com.sg", "com.my", "co.id", "com.hk", "co.nz"
    )

    /** Hosts handling money or identity. No request rewriting, proxying, or ad-block interference there. */
    private val PROTECTED = setOf(
        "gcash.com", "mynt.xyz", "maya.ph", "paymaya.com", "bdo.com.ph", "bpi.com.ph",
        "metrobank.com.ph", "unionbankph.com", "landbank.com", "securitybank.com", "rcbc.com",
        "pnb.com.ph", "chinabank.ph", "eastwestbanker.com", "sss.gov.ph", "pagibigfund.gov.ph",
        "philhealth.gov.ph", "bir.gov.ph", "paypal.com", "coins.ph", "gotyme.com.ph", "seabank.ph"
    )

    fun registrableDomain(host: String?): String? {
        if (host.isNullOrBlank()) return null
        val h = host.lowercase().trimEnd('.')
        val parts = h.split('.')
        if (parts.size <= 2) return h
        val lastTwo = parts.takeLast(2).joinToString(".")
        return if (lastTwo in TWO_LEVEL) parts.takeLast(3).joinToString(".") else lastTwo
    }

    fun isThirdParty(requestHost: String?, pageHost: String?): Boolean {
        val a = registrableDomain(requestHost) ?: return false
        val b = registrableDomain(pageHost) ?: return true
        return a != b
    }

    fun isProtectedHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase()
        return PROTECTED.any { h == it || h.endsWith(".$it") }
    }
}
