package app.yehaw.browser.console

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Over-the-air control. A config is only accepted if its ECDSA signature verifies against the public key bundled
 * in the app, it validates field by field, and its version is not older than the one already in use.
 * The last good config is cached, so the browser stays instant and fully functional offline or on 2G/3G.
 */
object RemoteConfigManager {
    private const val TAG = "RemoteConfig"
    private const val PREFS = "yeehaw_console"
    private const val K_BODY = "cfg_body"
    private const val K_SIG = "cfg_sig"
    private const val K_ETAG = "cfg_etag"
    private const val K_FETCHED = "cfg_fetched_ms"

    @Volatile var current: RemoteConfig = RemoteConfig.DEFAULT
        private set

    private var appContext: Context? = null
    private val listeners = CopyOnWriteArrayList<(RemoteConfig) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    internal fun publicKey(c: Context): String? = try {
        c.assets.open(BackendConfig.PUBLIC_KEY_ASSET).bufferedReader().use { it.readText().trim() }.ifEmpty { null }
    } catch (_: Throwable) {
        null
    }

    /** Call once from MainActivity.onCreate. Loads the cached config synchronously (tiny), refreshes in the background. */
    fun init(context: Context) {
        val c = context.applicationContext
        appContext = c
        val key = publicKey(c)
        if (key == null) {
            Log.w(TAG, "${BackendConfig.PUBLIC_KEY_ASSET} missing from assets: remote config disabled")
            return
        }
        val p = prefs(c)
        val body = p.getString(K_BODY, null)
        val sig = p.getString(K_SIG, null)
        if (body != null && sig != null) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            if (ConfigRules.verifySignature(key, bytes, sig)) parse(body)?.let { current = it }
        }
        refresh(force = false)
    }

    fun addListener(l: (RemoteConfig) -> Unit) { listeners.addIfAbsent(l) }
    fun removeListener(l: (RemoteConfig) -> Unit) { listeners.remove(l) }

    /** Non-blocking. Skips the network if the cache is fresh, unless [force]. */
    fun refresh(force: Boolean) {
        val c = appContext ?: return
        val key = publicKey(c) ?: return
        val p = prefs(c)
        val age = System.currentTimeMillis() - p.getLong(K_FETCHED, 0L)
        if (!force && age < BackendConfig.MIN_REFRESH_INTERVAL_MS && p.getString(K_BODY, null) != null) return
        BackendHttp.async {
            val etag = p.getString(K_ETAG, null)
            val headers = if (etag != null) mapOf("If-None-Match" to etag) else emptyMap()
            val r = BackendHttp.get(BackendConfig.PATH_APP_CONFIG, headers) ?: return@async
            when (r.code) {
                304 -> p.edit().putLong(K_FETCHED, System.currentTimeMillis()).apply()
                200 -> {
                    val sig = r.header("x-config-signature") ?: return@async
                    if (!ConfigRules.verifySignature(key, r.body, sig)) {
                        Log.w(TAG, "signature check failed: config ignored")
                        return@async
                    }
                    val text = String(r.body, Charsets.UTF_8)
                    val cfg = parse(text) ?: return@async
                    if (cfg.version < current.version) {
                        Log.w(TAG, "older config version rejected")
                        return@async
                    }
                    p.edit().putString(K_BODY, text).putString(K_SIG, sig).putString(K_ETAG, r.header("etag"))
                        .putLong(K_FETCHED, System.currentTimeMillis()).apply()
                    if (cfg != current) {
                        current = cfg
                        main.post { listeners.forEach { l -> try { l(cfg) } catch (_: Throwable) { } } }
                    }
                }
            }
        }
    }

    internal fun parse(text: String): RemoteConfig? = ConfigParser.parse(text)

    // ---- emergency domain blacklist -------------------------------------------------------------------------

    fun isBlocked(host: String?): Boolean = ConfigRules.isHostBlocked(host, current.blacklistedDomains)

    fun shouldBlock(uri: Uri?): Boolean {
        val scheme = uri?.scheme?.lowercase()
        return (scheme == "http" || scheme == "https") && isBlocked(uri?.host)
    }

    /**
     * Drop-in response for WebViewClient.shouldInterceptRequest. Main-frame navigations get a readable
     * "blocked for your safety" page, sub-resources get an empty 403.
     */
    fun blockedResponse(host: String?, isMainFrame: Boolean): WebResourceResponse {
        if (!isMainFrame) {
            return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
        val safe = android.text.TextUtils.htmlEncode(host ?: "this site")
        val html = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>" +
            "<body style='font:16px system-ui;background:#080d18;color:#f3f7ff;padding:32px;text-align:center'>" +
            "<h2>Blocked for your safety</h2><p style='color:#a5b4ca'>$safe was blocked by YeeHaw.</p></body>"
        return WebResourceResponse("text/html", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(html.toByteArray()))
    }
}
