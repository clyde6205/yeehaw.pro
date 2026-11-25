package app.yehaw.browser.console

import android.app.Activity
import android.view.ViewGroup
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * One-line wiring for the existing MainActivity:
 *
 *   override fun onCreate(...) { ...; ConsoleBootstrap.init(this, adContainer) }
 *   override fun onResume()  { super.onResume();  ConsoleBootstrap.onResume() }
 *   override fun onPause()   { ConsoleBootstrap.onPause(); super.onPause() }
 *   override fun onDestroy() { ConsoleBootstrap.onDestroy(); super.onDestroy() }
 *
 * and in your WebViewClient.onPageFinished:  ConsoleBootstrap.onPageFinished(view, url)
 */
object ConsoleBootstrap {
    /** Your start page is served from this origin. */
    private const val START_PAGE_PREFIX = "https://start.yeehaw.internal/"

    private var startPage: WeakReference<WebView>? = null
    private val onConfig: (RemoteConfig) -> Unit = { startPage?.get()?.let { injectSpeedDial(it) } }

    fun init(activity: Activity, adContainer: ViewGroup? = null) {
        TelemetryManager.init(activity)
        RemoteConfigManager.init(activity)
        RemoteConfigManager.removeListener(onConfig)
        RemoteConfigManager.addListener(onConfig)
        AdMonetizationManager.init(activity, adContainer)
    }

    fun onResume() {
        AdMonetizationManager.onResume()
        RemoteConfigManager.refresh(force = false)
    }

    fun onPause() = AdMonetizationManager.onPause()

    fun onDestroy() {
        RemoteConfigManager.removeListener(onConfig)
        AdMonetizationManager.onDestroy()
    }

    /** Call from WebViewClient.onPageFinished; pushes the promoted speed dial into your bundled start page. */
    fun onPageFinished(webView: WebView, url: String?) {
        if (url != null && url.startsWith(START_PAGE_PREFIX)) {
            startPage = WeakReference(webView)
            injectSpeedDial(webView)
        }
    }

    private fun injectSpeedDial(webView: WebView) {
        val arr = JSONArray()
        RemoteConfigManager.current.speedDial.forEach {
            arr.put(JSONObject().put("title", it.title).put("url", it.url).put("sponsored", it.sponsored))
        }
        webView.post { webView.evaluateJavascript("window.yeehawSpeedDial&&window.yeehawSpeedDial($arr)", null) }
    }
}
