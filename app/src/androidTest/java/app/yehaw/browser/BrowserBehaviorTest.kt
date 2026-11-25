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

    private fun clickByLabel(activity: MainActivity, label: String): Boolean {
        val found = ArrayList<View>()
        activity.window.decorView.findViewsWithText(found, label, View.FIND_VIEWS_WITH_TEXT or View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
        val v = found.firstOrNull { it.isShown && it.isEnabled } ?: return false
        return v.performClick()
    }

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
            repeat(15) {
                scenario.onActivity { a -> clickByLabel(a, "New tab") }
                Thread.sleep(150)
            }
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
            repeat(5) {
                scenario.onActivity { a -> clickByLabel(a, "New tab") }
                Thread.sleep(150)
            }
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
                val found = ArrayList<View>()
                a.window.decorView.findViewsWithText(found, "More", View.FIND_VIEWS_WITH_TEXT)
                assertTrue("More control must exist after recreate", found.isNotEmpty())
            }
        }
    }
}
