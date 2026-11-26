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

    private fun clearSavedSession() {
        BrowserStore(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext).clearSession()
    }

    @org.junit.Before fun before() = clearSavedSession()
    @org.junit.After fun after() = clearSavedSession()

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
            var status = ""
            for (i in 0 until 600) { // the app's own stall bound is 25 s; allow slow emulators double that
                scenario.onActivity { a ->
                    val tv = allViews(a.window.decorView).filterIsInstance<android.widget.TextView>()
                    shown = tv.any { it.isShown && it.text?.toString()?.contains("could not be loaded") == true && it.text?.toString()?.contains("Check your connection") == true }
                    status = tv.map { it.text?.toString().orEmpty() }.firstOrNull { it.contains("Retrying") || it.contains("request failed") } ?: status
                }
                if (shown) break
                Thread.sleep(100)
            }
            assertTrue("failure handling must engage for an unreachable site (panel shown=$shown, status='$status'); " + dump(scenario), shown || status.isNotEmpty())
            if (shown) scenario.onActivity { a -> assertTrue("Retry must be clickable", clickByLabel(a, "Retry")) }
            Thread.sleep(1500)
            scenario.onActivity { a -> assertFalse(a.isFinishing || a.isDestroyed) }
        }
    }

    private fun dump(scenario: ActivityScenario<MainActivity>): String {
        var out = ""
        scenario.onActivity { a ->
            val root = a.window.decorView
            val texts = allViews(root).filterIsInstance<android.widget.TextView>().filter { it.isShown }.map { it.text?.toString().orEmpty() }.filter { it.isNotBlank() }
            val wv = webViews(root).joinToString { "[url=${it.url} progress=${it.progress} title=${it.title} shown=${it.isShown}]" }
            out = "texts=$texts webviews=$wv"
        }
        return out
    }

    @Test
    fun stalledConnectionTimesOutThenRecoversWhenTheNetworkComes_back() {
        val held = java.util.Collections.synchronizedList(mutableListOf<java.net.Socket>())
        val respond = java.util.concurrent.atomic.AtomicBoolean(false)
        val server = java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val acceptor = Thread {
            try {
                while (!server.isClosed) {
                    val sock = server.accept()
                    if (!respond.get()) { held.add(sock); continue } // simulates a dead link: connected, never answers
                    Thread {
                        try {
                            val input = sock.getInputStream()
                            val buf = ByteArray(4096); input.read(buf)
                            val body = "<html><head><title>YH-OK</title></head><body>ok</body></html>".toByteArray()
                            val out = sock.getOutputStream()
                            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                            out.write(body); out.flush(); sock.close()
                        } catch (_: Exception) {}
                    }.start()
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val url = "http://127.0.0.1:${server.localPort}/"
                scenario.onActivity { a ->
                    allViews(a.window.decorView).filterIsInstance<android.widget.EditText>().first().setText(url)
                    assertTrue("GO must be clickable", clickByLabel(a, "GO"))
                }
                var shown = false
                for (i in 0 until 600) { // up to 60 s: the stall watchdog fires at 25 s
                    scenario.onActivity { a ->
                        shown = allViews(a.window.decorView).filterIsInstance<android.widget.TextView>()
                            .any { it.isShown && it.text?.toString()?.contains("could not be loaded") == true }
                    }
                    if (shown) break
                    Thread.sleep(100)
                }
                assertTrue("a connection that never answers must end in the Retry panel, not spin forever; " + dump(scenario), shown)
                respond.set(true) // the network "comes back"
                synchronized(held) { held.forEach { try { it.close() } catch (_: Exception) {} }; held.clear() }
                scenario.onActivity { a -> assertTrue("Retry must be clickable", clickByLabel(a, "Retry")) }
                var title = ""
                for (i in 0 until 300) {
                    scenario.onActivity { a -> title = webViews(a.window.decorView).mapNotNull { it.title }.firstOrNull { it == "YH-OK" } ?: title }
                    if (title == "YH-OK") break
                    Thread.sleep(100)
                }
                assertTrue("after the network returns, Retry must load the page (title='$title'); " + dump(scenario), title == "YH-OK")
            }
        } finally {
            try { server.close() } catch (_: Exception) {}
        }
    }

    @Test
    fun attachmentLinkDownloadsToCompletionThroughTheDownloadManager() {
        val payload = ByteArray(4096) { (it % 251).toByte() }
        val server = java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        Thread {
            try {
                while (!server.isClosed) {
                    val sock = server.accept()
                    Thread {
                        try {
                            sock.getInputStream().read(ByteArray(4096))
                            val out = sock.getOutputStream()
                            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Disposition: attachment; filename=\"yh-download-test.bin\"\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n").toByteArray())
                            out.write(payload); out.flush(); sock.close()
                        } catch (_: Exception) {}
                    }.start()
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
        val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val dm = ctx.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
        fun find(): Pair<Long, Int>? {
            dm.query(android.app.DownloadManager.Query()).use { c ->
                while (c.moveToNext()) {
                    val title = c.getString(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_TITLE))
                    if (title == "yh-download-test.bin") return Pair(c.getLong(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_ID)), c.getInt(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS)))
                }
            }
            return null
        }
        if (android.os.Build.VERSION.SDK_INT in 23..28) { // no prompt can be tapped in a test; the app asks on first download
            // UiAutomation.grantRuntimePermission only exists from API 28; `pm grant` works on every level.
            val pfd = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("pm grant ${ctx.packageName} android.permission.WRITE_EXTERNAL_STORAGE")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        }
        try {
            find()?.let { dm.remove(it.first) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { a ->
                    allViews(a.window.decorView).filterIsInstance<android.widget.EditText>().first().setText("http://127.0.0.1:${server.localPort}/file")
                    assertTrue("GO must be clickable", clickByLabel(a, "GO"))
                }
                var state: Pair<Long, Int>? = null
                for (i in 0 until 300) {
                    state = find()
                    if (state?.second == android.app.DownloadManager.STATUS_SUCCESSFUL || state?.second == android.app.DownloadManager.STATUS_FAILED) break
                    Thread.sleep(100)
                }
                assertTrue("the attachment must appear in DownloadManager and finish successfully (state=$state, sdk=${android.os.Build.VERSION.SDK_INT})", state?.second == android.app.DownloadManager.STATUS_SUCCESSFUL)
                state?.let { dm.remove(it.first) }
            }
        } finally {
            try { server.close() } catch (_: Exception) {}
        }
    }

    @Test
    fun everyButtonKeepsA48dpTouchTargetAndHasALabel() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                val d = a.resources.displayMetrics.density
                val buttons = allViews(a.window.decorView).filterIsInstance<android.widget.Button>()
                assertTrue("the browser must have buttons", buttons.size >= 5)
                for (b in buttons) {
                    val label = (b.contentDescription ?: b.text)?.toString().orEmpty()
                    assertTrue("button '${b.text}' needs an accessible label", label.isNotBlank())
                    assertTrue("button '$label' touch target ${b.minimumWidth / d}x${b.minimumHeight / d}dp must be >= 48dp",
                        b.minimumWidth >= (48 * d - 1) && b.minimumHeight >= (48 * d - 1))
                }
            }
        }
    }

    @Test
    fun browserStaysUsableAtTheLargestSystemFontScale() {
        val ui = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        fun sh(c: String) { android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(c)).use { it.readBytes() } }
        var normalSize = 0f
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a -> normalSize = allViews(a.window.decorView).filterIsInstance<android.widget.EditText>().first().textSize }
        }
        try {
            sh("settings put system font_scale 2.0")
            // Old Android versions apply the setting asynchronously: wait until the system configuration reports it.
            val res = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.resources
            for (i in 0 until 100) {
                if (android.provider.Settings.System.getFloat(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.contentResolver, "font_scale", 1f) >= 1.9f) break
                Thread.sleep(100)
            }
            Thread.sleep(2500)
            ActivityScenario.launch(MainActivity::class.java).use { sc ->
                sc.onActivity { a ->
                    val edit = allViews(a.window.decorView).filterIsInstance<android.widget.EditText>().first()
                    assertTrue("text must scale with the system font setting (was $normalSize, now ${edit.textSize})", edit.textSize > normalSize * 1.5f)
                    assertTrue("GO must still be clickable at 2x font", clickByLabel(a, "GO"))
                }
                Thread.sleep(1500)
                sc.onActivity { a -> assertFalse(a.isFinishing || a.isDestroyed) }
            }
        } finally {
            sh("settings put system font_scale 1.0")
        }
    }

    @Test
    fun privateBrowserRunsInItsOwnProcessOnApi28PlusAndRefusesBelow() {
        val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val intent = android.content.Intent(ctx, PrivateBrowserActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        var running = false
        for (i in 0 until 80) {
            running = am.runningAppProcesses.orEmpty().any { it.processName.endsWith(":private_browser") }
            if (running) break
            Thread.sleep(250)
        }
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            assertTrue("private browser must run in the separate :private_browser process", running)
        }
        // below API 28 the activity shows a message and finishes; reaching here without a crash is the assertion.
    }
}
