package app.yehaw.browser

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Offline reading end to end: save a real page through the "Save offline" button, lose the network, restart the
 * browser, and read it back from storage; refusals and recovery; deletion.
 */
@RunWith(AndroidJUnit4::class)
class OfflinePagesTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val marker = "YH-OFFLINE-MARKER-4217"

    @Before fun before() { BrowserStore(ctx).apply { clearSession(); clearOfflinePages() } }
    @After fun after() { BrowserStore(ctx).apply { clearSession(); clearOfflinePages() } }

    private fun allViews(root: View): List<View> {
        val out = mutableListOf<View>()
        fun walk(v: View) { out.add(v); if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
        walk(root); return out
    }

    private fun serve(): ServerSocket {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) {
                    try {
                        s.getInputStream().read(ByteArray(4096))
                        val body = "<html><head><title>Offline Test Page</title></head><body><p>$marker is the readable text of this page.</p></body></html>".toByteArray()
                        s.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(body); flush()
                        }
                        s.close()
                    } catch (_: Exception) {}
                }
            }
        }
        return server
    }

    private fun waitFor(ms: Long = 30_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(150) }
        return cond()
    }

    private fun navigate(scenario: ActivityScenario<MainActivity>, url: String) {
        scenario.onActivity { a ->
            allViews(a.window.decorView).filterIsInstance<android.widget.EditText>().first().setText(url)
            allViews(a.window.decorView).first { it is android.widget.TextView && it.text?.toString() == a.getString(R.string.label_go) }.performClick()
        }
    }

    private fun pageTitle(scenario: ActivityScenario<MainActivity>): String {
        var t = ""
        scenario.onActivity { a -> t = allViews(a.window.decorView).filterIsInstance<WebView>().mapNotNull { it.title }.firstOrNull { it == "Offline Test Page" } ?: "" }
        return t
    }

    private fun tapSaveOffline(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { a ->
            val label = a.getString(R.string.label_save_offline)
            allViews(a.window.decorView).first { it is android.widget.TextView && it.text?.toString() == label }.performClick()
        }
    }

    @Test fun savedPageSurvivesNetworkLossAndABrowserRestart() {
        val server = serve()
        try {
            val url = "http://127.0.0.1:${server.localPort}/"
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                navigate(scenario, url)
                assertTrue("live page must load before saving", waitFor { pageTitle(scenario) == "Offline Test Page" })
                tapSaveOffline(scenario)
                assertTrue("page must be saved", waitFor { BrowserStore(ctx).offlinePageSummaries().isNotEmpty() })
            }
            server.close() // the network (the origin) is gone from here on
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->   // a fresh browser instance
                scenario.onActivity { _ ->
                    val fresh = BrowserStore(ctx)
                    val summary = fresh.offlinePageSummaries().single()
                    assertEquals(url, summary.url)
                    val full = fresh.offlinePage(summary.id)
                    assertNotNull(full)
                    assertTrue("saved text must contain the page's readable text", full!!.content.contains(marker))
                    assertEquals("Offline Test Page", full.title)
                }
            }
        } finally { try { server.close() } catch (_: Exception) {} }
    }

    @Test fun nonWebPagesAreRefusedAndLaterSavingRecovers() {
        val server = serve()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                tapSaveOffline(scenario)   // the bundled start page is not a normal web page
                Thread.sleep(1500)
                assertTrue("start page must not be stored", BrowserStore(ctx).offlinePageSummaries().isEmpty())
                navigate(scenario, "http://127.0.0.1:${server.localPort}/")
                assertTrue(waitFor { pageTitle(scenario) == "Offline Test Page" })
                tapSaveOffline(scenario)
                assertTrue("saving works after the earlier refusal", waitFor { BrowserStore(ctx).offlinePageSummaries().size == 1 })
            }
        } finally { server.close() }
    }

    @Test fun deletingAndClearingRemovesSavedPages() {
        val store = BrowserStore(ctx)
        val a = store.saveOfflinePage("https://example.org/a", "A", "text a")
        store.saveOfflinePage("https://example.org/b", "B", "text b")
        assertEquals(2, store.offlinePageSummaries().size)
        store.deleteOfflinePage(a)
        assertNull(store.offlinePage(a))
        assertEquals(1, store.offlinePageSummaries().size)
        store.clearOfflinePages()
        assertEquals(0, store.offlinePageSummaries().size)
    }
}
