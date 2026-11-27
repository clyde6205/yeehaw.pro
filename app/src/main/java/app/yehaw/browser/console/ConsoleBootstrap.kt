package app.yehaw.browser.console

import android.app.Activity
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * One-line wiring for MainActivity: init/onResume/onPageFinished.
 * Ads are NOT bundled in this build (see docs/ADS_DECISION.md): the console's ads switch is stored and
 * signed but nothing in the app renders ads until an AdMob build is added with real unit ids.
 */
object ConsoleBootstrap {
    private const val START_PAGE_PREFIX = "https://start.yeehaw.internal/"

    private var startPage: WeakReference<WebView>? = null
    private val onConfig: (RemoteConfig) -> Unit = { startPage?.get()?.let { injectSpeedDial(it) } }

    fun init(activity: Activity) {
        TelemetryManager.init(activity)
        RemoteConfigManager.init(activity)
        RemoteConfigManager.removeListener(onConfig)
        RemoteConfigManager.addListener(onConfig)
    }

    fun onResume() {
        RemoteConfigManager.refresh(force = false)
    }

    fun onDestroy() {
        RemoteConfigManager.removeListener(onConfig)
    }

    /** Pushes the promoted speed dial into the bundled start page (empty by default: layout unchanged). */
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
