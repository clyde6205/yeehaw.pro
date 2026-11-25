package app.yehaw.browser

import android.content.ComponentCallbacks2
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Drives the real MainActivity: start page, many tabs, memory pressure, recreation. */
@RunWith(AndroidJUnit4::class)
class BrowserBehaviorTest {

    private fun webViews(root: View): List<WebView> {
        val out = mutableListOf<WebView>()
        fun walk(v: View) {
            if (v is WebView) out.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun allViews(root: View): List<View> {
        val out = mutableListOf<View>()
        fun walk(v: View) {
            out.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    /** Finds a control by text or contentDescription regardless of visibility (the toolbar is hidden on the start page by design). */
    private fun findControl(activity: MainActivity, label: String): View? =
        allViews(activity.window.decorView).firstOrNull {
            (it is android.widget.TextView && it.text?.toString() == label) || it.contentDescription?.toString() == label
        }

    private fun clickByLabel(activity: MainActivity, label: String): Boolean =
        findControl(activity, label)?.performClick() ?: false

    @Test
    fun startPageLoadsFromBundledAssets() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var url: String? = null
            var progress = 0
            repeat(40) {
                scenario.onActivity { a ->
                    val w = webViews(a.window.decorView).firstOrNull { it.isShown }
                    url = w?.url
                    progress = w?.progress ?: 0
                }
                if (url?.startsWith("https://start.yeehaw.internal") == true && progress == 100) return@use
                Thread.sleep(500)
            }
            assertTrue("start page did not finish loading, url=$url progress=$progress", url?.startsWith("https://start.yeehaw.internal") == true && progress == 100)
        }
    }

    @Test
    fun openingMoreTabsThanTheCapNeverCrashes() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var clicked = 0
            repeat(15) {
                scenario.onActivity { a -> if (clickByLabel(a, "New tab")) clicked++ }
                Thread.sleep(150)
            }
            assertTrue("the New tab control must be found and clickable (clicked=$clicked)", clicked == 15)
            scenario.onActivity { a ->
                assertFalse("activity must stay alive", a.isFinishing || a.isDestroyed)
                val live = webViews(a.window.decorView)
                assertTrue("a WebView must still be attached", live.isNotEmpty())
                assertTrue("attached WebViews must stay within the hard tab cap", live.size <= 12)
            }
        }
    }

    @Test
    fun criticalMemoryPressureKeepsTheBrowserUsable() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var clicked = 0
            repeat(5) {
                scenario.onActivity { a -> if (clickByLabel(a, "New tab")) clicked++ }
                Thread.sleep(150)
            }
            assertTrue("the New tab control must be found and clickable (clicked=$clicked)", clicked == 5)
            scenario.onActivity { a ->
                a.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
                a.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
            }
            Thread.sleep(500)
            scenario.onActivity { a ->
                assertFalse(a.isFinishing || a.isDestroyed)
                assertTrue("a visible WebView must remain after trimming", webViews(a.window.decorView).any { it.isShown })
                assertNotNull(a.window.decorView)
            }
        }
    }

    @Test
    fun recreationKeepsBrowserControls() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.recreate()
            Thread.sleep(500)
            scenario.onActivity { a ->
                assertNotNull("More control must exist after recreate", findControl(a, "More"))
            }
        }
    }

    @Test
    fun unreachableSiteShowsTheRetryPanelAndRetryDoesNotCrash() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                val edit = allViews(a.window.decorView).filterIsInstance<android.widget.EditText>().first()
                edit.setText("https://yehaw-unreachable.invalid/")
                assertTrue("GO must be clickable", clickByLabel(a, "GO"))
            }
            var shown = false
            repeat(40) {
                scenario.onActivity { a ->
                    shown = allViews(a.window.decorView).any { it is android.widget.TextView && it.isShown && it.text?.toString()?.contains("could not be loaded") == true }
                }
                if (shown) return@repeat
                Thread.sleep(500)
            }
            assertTrue("the failed-page panel with Retry must appear for an unreachable site", shown)
            scenario.onActivity { a -> assertTrue("Retry must be clickable", clickByLabel(a, "Retry")) }
            Thread.sleep(1500)
            scenario.onActivity { a -> assertFalse(a.isFinishing || a.isDestroyed) }
        }
    }
}
