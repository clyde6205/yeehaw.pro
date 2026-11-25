package app.yehaw.browser.console

import java.security.SecureRandom
import java.util.Locale

/** Pure helpers for the share flow (no Android dependencies, unit-testable). */
object ShareCore {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567" // Base32: no 0/O/1/I confusion

    /** 8-character random referral code, e.g. K7M2QX3D. Not derived from any device or user identifier. */
    fun newReferralCode(random: java.util.Random = SecureRandom()): String =
        buildString { repeat(8) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    fun isValidReferralCode(code: String) = code.length == 8 && code.all { it in ALPHABET }

    fun link(base: String, code: String, channel: String): String {
        require(isValidReferralCode(code)) { "bad referral code" }
        require(channel.matches(Regex("^[a-z_]{2,16}$"))) { "bad channel" }
        return "$base?r=$code&c=$channel"
    }

    private val MESSAGES = mapOf(
        "en" to "Try YeeHaw, the browser that runs fast on any phone and saves your data: %s",
        "fil" to "Subukan ang YeeHaw, ang browser na mabilis kahit sa lumang phone at matipid sa data: %s",
        "tl" to "Subukan ang YeeHaw, ang browser na mabilis kahit sa lumang phone at matipid sa data: %s",
        "id" to "Coba YeeHaw, browser yang cepat di HP apa pun dan hemat kuota: %s",
        "hi" to "YeeHaw आज़माएँ: हर फ़ोन पर तेज़ चलने वाला और डेटा बचाने वाला ब्राउज़र: %s",
        "bn" to "YeeHaw ব্যবহার করে দেখুন: যেকোনো ফোনে দ্রুত চলে এবং ডেটা বাঁচায়: %s",
        "vi" to "Thử YeeHaw, trình duyệt chạy nhanh trên mọi điện thoại và tiết kiệm dữ liệu: %s",
        "es" to "Prueba YeeHaw, el navegador rápido en cualquier celular que ahorra datos: %s",
        "pt" to "Experimente o YeeHaw, o navegador rápido em qualquer celular que economiza dados: %s",
    )

    fun message(languageCode: String, link: String): String {
        val tpl = MESSAGES[languageCode.lowercase(Locale.ROOT)] ?: MESSAGES.getValue("en")
        return tpl.replace("%s", link)
    }
}
