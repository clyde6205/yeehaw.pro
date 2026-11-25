package app.yehaw.browser.console

/** Single place for the back-office wiring. Change [API_BASE_URL] to wherever you deploy the backend. */
object BackendConfig {
    /** HTTPS only. Cleartext endpoints are refused at runtime. */
    const val API_BASE_URL = "https://api.yeehaw.pro"

    const val PATH_BOOT = "/v1/telemetry/boot"
    const val PATH_FORGET = "/v1/telemetry/forget"
    const val PATH_AD_EVENT = "/v1/telemetry/ad-event"
    const val PATH_APP_CONFIG = "/v1/config/app-config"
    const val PATH_SHARE_EVENT = "/v1/telemetry/share-event"

    /** Landing link inside every QR code / shared message. Your website redirects it to the store or APK page. */
    const val SHARE_BASE_URL = "https://yeehaw.pro/get"

    /**
     * Asset holding the Base64 X.509 public key that verifies signed remote configs.
     * Produced by `node scripts/gen-keys.js` in the backend. If it is missing, remote config stays OFF (fail closed).
     */
    const val PUBLIC_KEY_ASSET = "yeehaw_config_pubkey.txt"

    /** Google's published TEST banner unit. Replace with your real unit id before a store release. */
    const val BANNER_AD_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"

    /** Do not hit the network more often than this unless a refresh is forced. */
    const val MIN_REFRESH_INTERVAL_MS = 6L * 60 * 60 * 1000

    /** A new telemetry "boot" is not sent again within this window (Activity re-creation, quick relaunch). */
    const val BOOT_DEBOUNCE_MS = 10L * 60 * 1000

    /** Regions where analytics/ads identifiers need explicit opt-in (EEA, UK, Switzerland). */
    val CONSENT_REQUIRED_COUNTRIES: Set<String> = setOf(
        "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IE", "IT", "LV", "LT", "LU",
        "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE", "IS", "LI", "NO", "GB", "CH"
    )
}
