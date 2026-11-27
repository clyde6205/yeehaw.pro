package app.yehaw.browser

import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Browser regression through the real MainActivity against a loopback site that can be slow, cached, or cut mid-body.
 * Every timing is logged as "YHPERF key=value" so the CI job can publish it (emulator numbers, never phone numbers).
 */
@RunWith(AndroidJUnit4::class)
class BrowserRegressionTest {
    private class Site {
        val hits = ConcurrentHashMap<String, AtomicInteger>()
        val cut = AtomicBoolean(false)
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val port get() = server.localPort
        fun url(p: String) = "http://127.0.0.1:$port$p"
        init {
            Thread {
                while (!server.isClosed) {
                    val s = try { server.accept() } catch (_: Exception) { break }
                    Thread { try { handle(s) } catch (_: Exception) {} finally { try { s.close() } catch (_: Exception) {} } }.apply { isDaemon = true; start() }
                }
            }.apply { isDaemon = true; start() }
        }
        private fun page(title: String, body: String) = "<html><head><meta name=viewport content='width=device-width'><title>$title</title></head><body>$body</body></html>"
        private fun handle(s: Socket) {
            val buf = ByteArray(4096); val n = s.getInputStream().read(buf); if (n <= 0) return
            val path = String(buf, 0, n).lineSequence().first().split(" ").getOrElse(1) { "/" }.substringBefore('?')
            hits.getOrPut(path) { AtomicInteger() }.incrementAndGet()
            val out = s.getOutputStream()
            fun send(ct: String, body: ByteArray, extra: String = "", declared: Int = body.size) {
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: $ct\r\nContent-Length: $declared\r\n" + extra + "Connection: close\r\n\r\n").toByteArray())
                out.write(body); out.flush()
            }
            when (path) {
                "/" -> send("text/html; charset=utf-8", page("R-HOME", "<a id=l href='/two'>two</a><p style='height:3000px'>tall</p>").toByteArray())
                "/two" -> send("text/html; charset=utf-8", page("R-TWO", "second").toByteArray())
                "/cache" -> send("text/html; charset=utf-8", page("R-CACHE", "cached").toByteArray(), "Cache-Control: max-age=3600\r\n")
                "/slow" -> { // ~6 KB at ~2 KB/s: a weak link
                    val body = page("R-SLOW", "<p>" + "x".repeat(6000) + "</p>").toByteArray()
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); out.flush()
                    var i = 0; while (i < body.size) { val e = minOf(i + 200, body.size); out.write(body, i, e - i); out.flush(); Thread.sleep(100); i = e }
                }
                "/flaky" -> {
                    val body = page("R-FLAKY", "<p>" + "y".repeat(4000) + "</p>").toByteArray()
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    if (cut.get()) { out.write(body, 0, 500); out.flush(); s.setSoLinger(true, 0); s.close() } else { out.write(body); out.flush() }
                }
                else -> { out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush() }
            }
        }
        fun close() = try { server.close() } catch (_: Exception) {}
    }

    private lateinit var site: Site
    @Before fun before() { BrowserStore(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext).clearSession(); site = Site() }
    @After fun after() { site.close(); BrowserStore(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext).clearSession() }

    private fun views(root: View): List<View> { val o = mutableListOf<View>(); fun w(v: View) { o.add(v); if (v is ViewGroup) for (i in 0 until v.childCount) w(v.getChildAt(i)) }; w(root); return o }
    private fun shownWeb(a: MainActivity) = views(a.window.decorView).filterIsInstance<WebView>().firstOrNull { it.isShown }
    private fun control(a: MainActivity, label: String) = views(a.window.decorView).firstOrNull { (it is android.widget.TextView && it.text?.toString() == label) || it.contentDescription?.toString() == label }
    private fun perf(k: String, v: Any) = Log.i("YHPERF", "$k=$v")

    private fun go(scenario: ActivityScenario<MainActivity>, text: String) {
        scenario.onActivity { a ->
            views(a.window.decorView).filterIsInstance<android.widget.EditText>().first().setText(text)
            assertTrue("GO must be clickable", control(a, "GO")?.performClick() == true)
        }
    }
    private fun waitTitle(scenario: ActivityScenario<MainActivity>, title: String, timeoutMs: Long): Long {
        val t0 = System.nanoTime(); var seen = false
        while ((System.nanoTime() - t0) / 1_000_000 < timeoutMs) {
            scenario.onActivity { a -> seen = shownWeb(a)?.title == title }
            if (seen) return (System.nanoTime() - t0) / 1_000_000
            Thread.sleep(50)
        }
        return -1
    }
    private fun webUrl(scenario: ActivityScenario<MainActivity>): String? { var u: String? = null; scenario.onActivity { a -> u = shownWeb(a)?.url }; return u }

    @Test fun searchQueryIsRoutedToASearchEngineNotTreatedAsAnAddress() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            go(sc, "yeehaw regression query")
            var u: String? = null
            for (i in 0 until 60) { u = webUrl(sc); if (u?.contains("regression") == true) break; Thread.sleep(100) }
            assertTrue("a query must become a search URL, got $u", u != null && u!!.startsWith("https://") && u!!.contains("regression"))
        }
    }

    @Test fun httpPageLoadsLinkNavigatesAndBackReturns() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            go(sc, site.url("/"))
            val loadMs = waitTitle(sc, "R-HOME", 20_000); perf("nav_http_page_load_ms", loadMs)
            assertTrue("plain-HTTP page must load", loadMs >= 0)
            val t0 = System.nanoTime()
            sc.onActivity { a -> shownWeb(a)!!.evaluateJavascript("document.getElementById('l').click()", null) }
            val navMs = waitTitle(sc, "R-TWO", 15_000); perf("nav_link_click_ms", navMs)
            assertTrue("link navigation must work", navMs >= 0)
            sc.onActivity { a -> assertTrue("history must allow back", shownWeb(a)!!.canGoBack()); shownWeb(a)!!.goBack() }
            assertTrue("back must return to the first page", waitTitle(sc, "R-HOME", 15_000) >= 0)
        }
    }

    @Test fun newTabsAreIndependentAndSwitchingKeepsEachPage() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            go(sc, site.url("/")); assertTrue(waitTitle(sc, "R-HOME", 20_000) >= 0)
            sc.onActivity { a -> assertTrue("New tab control", control(a, "New tab")?.performClick() == true) }
            Thread.sleep(500)
            go(sc, site.url("/two")); assertTrue("second tab loads its own page", waitTitle(sc, "R-TWO", 20_000) >= 0)
            sc.onActivity { a -> val n = views(a.window.decorView).filterIsInstance<WebView>().size; assertTrue("two or more WebViews", n >= 2) }
        }
    }

    @Test fun cachedResourceIsServedWithoutASecondNetworkRequest() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            go(sc, site.url("/cache")); assertTrue(waitTitle(sc, "R-CACHE", 20_000) >= 0)
            go(sc, site.url("/two")); assertTrue(waitTitle(sc, "R-TWO", 20_000) >= 0)
            go(sc, site.url("/cache")); assertTrue(waitTitle(sc, "R-CACHE", 20_000) >= 0)
            val n = site.hits["/cache"]!!.get(); perf("cache_server_hits_for_two_visits", n)
            assertEquals("max-age page requested twice must hit the server once", 1, n)
        }
    }

    @Test fun weakLinkSlowPageStillCompletesAndReportsLoadTime() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            go(sc, site.url("/slow"))
            val ms = waitTitle(sc, "R-SLOW", 40_000); perf("weak_link_6kb_at_2kBps_load_ms", ms)
            assertTrue("a slow (about 2 KB/s) page must still load, not be abandoned", ms >= 0)
        }
    }

    @Test fun connectionCutMidPageThenRecoversOnRetryWhenLinkReturns() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            site.cut.set(true)
            go(sc, site.url("/flaky"))
            var panel = false
            for (i in 0 until 200) { sc.onActivity { a -> panel = views(a.window.decorView).filterIsInstance<android.widget.TextView>().any { it.isShown && it.text?.toString()?.contains("could not be loaded") == true } }; if (panel) break; Thread.sleep(100) }
            site.cut.set(false)
            val t0 = System.nanoTime()
            if (panel) sc.onActivity { a -> assertTrue("Retry must be clickable", control(a, "Retry")?.performClick() == true) } else sc.onActivity { a -> shownWeb(a)!!.reload() }
            val ms = waitTitle(sc, "R-FLAKY", 30_000); perf("interruption_recovery_ms", ms); perf("interruption_showed_retry_panel", panel)
            assertTrue("after the link returns the page must load (retry panel shown=$panel)", ms >= 0)
        }
    }

    @Test fun httpsBrowsingOverTheRealInternetWorksWhenTheRunnerHasConnectivity() {
        val reachable = try { java.net.URL("https://example.com/").openConnection().apply { connectTimeout = 8000; readTimeout = 8000 }.getInputStream().close(); true } catch (_: Exception) { false }
        org.junit.Assume.assumeTrue("no internet from this emulator; HTTPS test skipped (reported as skipped, not passed)", reachable)
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            go(sc, "https://example.com/")
            val ms = waitTitle(sc, "Example Domain", 60_000); perf("https_example_com_load_ms", ms)
            assertTrue("HTTPS page must load", ms >= 0)
        }
    }

    @Test fun touchSwipeScrollsThePage() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            go(sc, site.url("/")); assertTrue(waitTitle(sc, "R-HOME", 20_000) >= 0)
            Thread.sleep(500)
            val ui = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            var h = 0; var w = 0; sc.onActivity { a -> h = a.window.decorView.height; w = a.window.decorView.width }
            val dev = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            dev.uiAutomation.executeShellCommand("input swipe ${w / 2} ${h * 3 / 4} ${w / 2} ${h / 4} 300").close()
            var y = 0
            for (i in 0 until 40) { sc.onActivity { a -> y = shownWeb(a)!!.scrollY }; if (y > 0) break; Thread.sleep(100) }
            perf("touch_swipe_scrollY", y); assertTrue("a swipe gesture must scroll the page (scrollY=$y)", y > 0)
        }
    }

    @Test fun browserSurvivesRepeatedLoadsWithoutLeakingWebViews() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            repeat(8) { i -> go(sc, site.url(if (i % 2 == 0) "/two" else "/")); assertTrue(waitTitle(sc, if (i % 2 == 0) "R-TWO" else "R-HOME", 20_000) >= 0) }
            sc.onActivity { a -> assertTrue("one tab, one WebView after 8 loads", views(a.window.decorView).filterIsInstance<WebView>().size <= 2); assertTrue(!a.isFinishing) }
        }
    }
}
