package app.yehaw.browser

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Private-browser data wipe, proven by behavior: a local origin sets a cookie and localStorage in a private session,
 * then the session ends (back key = normal close, or the process is killed = crash) and a new private session
 * must not send the cookie nor see the localStorage value.
 */
@RunWith(AndroidJUnit4::class)
class PrivateWipeTest {
    private class Hit(val path: String, val cookie: String?)

    private val hits = CopyOnWriteArrayList<Hit>()

    private fun startServer(): Pair<ServerSocket, Int> {
        val server = ServerSocket(0, 20, java.net.InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val c = try { server.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) {
                    try {
                        val reader = c.getInputStream().bufferedReader()
                        val first = reader.readLine() ?: return@thread
                        val path = first.split(" ").getOrElse(1) { "/" }
                        var cookie: String? = null
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Cookie:", true)) cookie = line.substringAfter(':').trim()
                        }
                        hits.add(Hit(path, cookie))
                        val body = if (path.startsWith("/probe")) "ok" else
                            "<html><title>YH-PRIV</title><body><script>" +
                            "var before=localStorage.getItem('yhk');localStorage.setItem('yhk','1');" +
                            "fetch('/probe?ls='+encodeURIComponent(before));</script>private</body></html>"
                        val headers = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                            (if (path.startsWith("/probe")) "" else "Set-Cookie: yhk=1; Path=/\r\n") +
                            "Cache-Control: no-store\r\nConnection: close\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n"
                        c.getOutputStream().use { it.write(headers.toByteArray()); it.write(body.toByteArray()) }
                    } catch (_: Exception) {
                    } finally { try { c.close() } catch (_: Exception) {} }
                }
            }
        }
        return server to server.localPort
    }

    /** Other tests leave a private window open in the :private_browser process; start every test from a clean process. */
    private fun killPrivateProcess() {
        privateProcessPid()?.let { android.os.Process.killProcess(it) }
        waitFor(15_000) { privateProcessPid() == null }
    }

    @org.junit.Before fun before() = killPrivateProcess()
    @org.junit.After fun after() = killPrivateProcess()

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun privateProcessPid(): Int? =
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).runningAppProcesses.orEmpty()
            .firstOrNull { it.processName.endsWith(":private_browser") }?.pid

    private fun launchPrivate(url: String) {
        ctx.startActivity(Intent(ctx, PrivateBrowserActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(PrivateBrowserActivity.EXTRA_START_URL, url))
    }

    private fun waitFor(timeoutMs: Long = 40_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(250) }
        return cond()
    }

    private fun shell(cmd: String) {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(cmd)).use { it.readBytes() }
    }

    private fun session(url: String, end: () -> Unit): Pair<Hit, Hit> {
        val before = hits.size
        launchPrivate(url)
        assertTrue("private page must request the origin", waitFor { hits.drop(before).any { it.path == "/" } })
        assertTrue("private page script must call back", waitFor { hits.drop(before).any { it.path.startsWith("/probe") } })
        val page = hits.drop(before).first { it.path == "/" }
        val probe = hits.drop(before).first { it.path.startsWith("/probe") }
        end()
        return page to probe
    }

    private fun runScenario(end: () -> Unit) {
        assumeTrue("isolated private browsing needs API 28+", Build.VERSION.SDK_INT >= 28)
        val (server, port) = startServer()
        try {
            val url = "http://127.0.0.1:$port/"
            val (page1, probe1) = session(url, end)
            assertEquals("first visit has no cookie", null, page1.cookie)
            assertTrue("control: the cookie must be usable inside the session (else this test proves nothing)",
                probe1.cookie?.contains("yhk=1") == true)
            assertTrue("control: localStorage starts empty", probe1.path.contains("ls=null"))
            Thread.sleep(1500)
            val (page2, probe2) = session(url) { shell("input keyevent 4"); waitFor(10_000) { privateProcessPid() == null || true } }
            assertFalse("cookie from the ended private session must be gone: ${page2.cookie}", page2.cookie?.contains("yhk") == true)
            assertTrue("localStorage from the ended private session must be gone: ${probe2.path}", probe2.path.contains("ls=null"))
        } finally { server.close() }
    }

    @Test fun normalCloseWipesCookiesAndLocalStorage() = runScenario {
        shell("input keyevent 4")                       // back from the only page closes the private window
        Thread.sleep(3000)
    }

    @Test fun processKillDuringPrivateSessionIsWipedAtNextStart() = runScenario {
        privateProcessPid()?.let { android.os.Process.killProcess(it) }   // same uid: simulates a crash / OOM kill
        Thread.sleep(3000)
    }
}
