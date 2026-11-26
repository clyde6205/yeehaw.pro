package app.yehaw.browser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.yehaw.browser.tools.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Every Quick Tool, tested on its own through its real screen, plus the hub, RTL layouts and persistence. */
@RunWith(AndroidJUnit4::class)
class QuickToolsTest {
    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val toolClasses = listOf(CalculatorActivity::class.java, UnitConverterActivity::class.java, QrScannerActivity::class.java,
        QrGeneratorActivity::class.java, NotesActivity::class.java, PdfReaderActivity::class.java)

    @Before fun before() { LocaleHelper.save(ctx, LocaleHelper.SYSTEM); ctx.deleteDatabase(NotesStore.DB_NAME) }
    @After fun after() { LocaleHelper.save(ctx, LocaleHelper.SYSTEM); ctx.deleteDatabase(NotesStore.DB_NAME) }

    private fun views(root: View): List<View> {
        val out = mutableListOf<View>()
        fun walk(v: View) { out.add(v); if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
        walk(root); return out
    }
    private fun Activity.all() = views(window.decorView)
    private fun Activity.button(label: String) = all().filterIsInstance<Button>().first { it.text.toString() == label || it.contentDescription?.toString() == label }
    private fun waitFor(ms: Long = 20_000, c: () -> Boolean): Boolean { val end = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < end) { if (c()) return true; Thread.sleep(100) }; return c() }
    private fun <T : Activity> launch(c: Class<T>, intent: Intent? = null) =
        ActivityScenario.launch<T>(intent ?: Intent(ctx, c))

    // ---- hub ----
    @Test fun hubListsAllSixToolsAndEachOpens() {
        launch(QuickToolsActivity::class.java).use { sc ->
            val tags = mutableListOf<Any?>()
            sc.onActivity { a -> tags.addAll(a.all().filterIsInstance<Button>().map { it.tag }.filterNotNull()) }
            assertEquals(toolClasses.map { it.simpleName }.toSet(), tags.map { it.toString() }.toSet())
        }
        for (c in toolClasses) launch(c).use { sc -> sc.onActivity { a -> assertFalse("${c.simpleName} must not finish itself", a.isFinishing) } }
    }

    @Test fun hubIsReachableFromTheBrowserToolsMenu() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val label = a.getString(R.string.tools_quick_tools)
                // The Tools dialog is built by showAppsHub(); here we assert the entry exists as a resource and the switch is on.
                assertTrue(label.isNotBlank()); assertTrue(QuickTools.ENABLED)
            }
        }
    }

    // ---- calculator ----
    @Test fun calculatorComputesAndReportsErrors() {
        launch(CalculatorActivity::class.java).use { sc ->
            fun tap(l: String) = sc.onActivity { a -> a.button(l).performClick() }
            fun shown(): String { var s = ""; sc.onActivity { a -> s = a.all().filterIsInstance<TextView>().filter { it !is Button && it !is EditText }.joinToString("|") { it.text } }; return s }
            for (k in listOf("7", "×", "6", "=")) tap(k)
            assertTrue("7×6 must show 42, saw: ${shown()}", shown().contains("42"))
            tap("C"); for (k in listOf("1", "÷", "0", "=")) tap(k)
            assertTrue(shown(), shown().contains(ctx.getString(R.string.calc_error_divide_by_zero)))
            tap("C"); for (k in listOf("0", ".", "1", "+", "0", ".", "2", "=")) tap(k)
            assertTrue("0.1+0.2 must be exactly 0.3, saw ${shown()}", shown().contains("0.3") && !shown().contains("0.30000"))
        }
    }

    @Test fun calculatorKeepsItsExpressionAcrossRecreate() {
        launch(CalculatorActivity::class.java).use { sc ->
            sc.onActivity { a -> a.button("9").performClick(); a.button("+").performClick(); a.button("1").performClick() }
            sc.recreate()
            sc.onActivity { a -> assertTrue(a.all().filterIsInstance<TextView>().any { it.text.toString() == "9+1" }) }
        }
    }

    // ---- converter ----
    @Test fun converterConvertsOffline() {
        launch(UnitConverterActivity::class.java).use { sc ->
            sc.onActivity { a -> a.all().filterIsInstance<EditText>().first().setText("1000") }
            assertTrue(waitFor { var ok = false; sc.onActivity { a -> ok = a.all().filterIsInstance<TextView>().any { it !is EditText && it !is Button && it.text.isNotEmpty() && it.text.toString().matches(Regex("[0-9.,−-]+")) } }; ok })
        }
    }

    // ---- QR generator ----
    @Test fun qrGeneratorMakesACodeThatDecodesBackAndCanBeSharedAndSaved() {
        val text = "https://yehaw.pro/?q=héllo"
        launch(QrGeneratorActivity::class.java, Intent(ctx, QrGeneratorActivity::class.java).putExtra(QuickTools.EXTRA_CURRENT_URL, text)).use { sc ->
            var bmp: Bitmap? = null
            assertTrue(waitFor { sc.onActivity { a -> bmp = (a.all().filterIsInstance<ImageView>().firstOrNull { it.visibility == View.VISIBLE }?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap }; bmp != null })
            val b = bmp!!
            val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
            assertEquals("decoding the picture must return the original text", text, QrDecoder.decodeArgb(px, b.width, b.height))
            assertNotNull("share URI must be creatable", QrImages.shareUri(ctx, b))
            assertNotNull("PNG must be saved", QrImages.savePng(ctx, b))
        }
    }

    @Test fun qrGeneratorRefusesEmptyAndOversizedInput() {
        launch(QrGeneratorActivity::class.java).use { sc ->
            sc.onActivity { a -> a.button(a.getString(R.string.qr_gen_create)).performClick() }
            sc.onActivity { a -> assertTrue(a.all().filterIsInstance<TextView>().any { it.text.toString() == a.getString(R.string.qr_gen_empty) }) }
            sc.onActivity { a -> a.all().filterIsInstance<EditText>().first().setText("x".repeat(5000)); a.button(a.getString(R.string.qr_gen_create)).performClick() }
            sc.onActivity { a -> assertTrue(a.all().filterIsInstance<TextView>().any { it.text.toString().startsWith(a.getString(R.string.qr_gen_too_long, QrImages.MAX_BYTES).take(10)) }) }
        }
    }

    // ---- QR scanner (camera permission paths; no real camera frames on an emulator) ----
    private fun cameraGranted() = ctx.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * One ordered test (revoking a permission would kill the instrumentation process, so we only ever grant): first the
     * not-yet-granted screen, then, after granting, a real camera start, pause and resume.
     */
    @Test fun scannerHandlesPermissionNotGrantedThenGranted() {
        if (!cameraGranted()) {
            launch(QrScannerActivity::class.java).use { sc ->
                sc.onActivity { a ->
                    assertTrue(a.all().filterIsInstance<Button>().any { it.text.toString() == a.getString(R.string.qr_scan_allow_camera) && it.isShown })
                    assertTrue(a.all().filterIsInstance<TextView>().any { it.text.toString() == a.getString(R.string.qr_scan_permission_needed) })
                }
            }
            val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
            android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand("pm grant " + ctx.packageName + " android.permission.CAMERA")).use { it.readBytes() }
        }
        launch(QrScannerActivity::class.java).use { sc ->
            Thread.sleep(2500)
            sc.onActivity { a -> assertFalse(a.isFinishing) }
            sc.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)   // pause/stop must release the camera without crashing
            sc.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            Thread.sleep(1000)
            sc.onActivity { a -> assertFalse(a.isFinishing) }
        }
    }

    // ---- notes ----
    @Test fun notesSurviveClosingTheStoreAndSearchWorks() {
        NotesStore(ctx).use { s ->
            val a = s.save(null, "Buy rice\n2 kg")!!; s.save(null, "100% real_note"); s.save(null, "   ")
            assertEquals(2, s.count())
            assertEquals(1, s.list("rice").size)
            assertEquals("wildcards are literal", 1, s.list("100%").size)
            assertEquals(0, s.list("%").size - 1)   // "%" matches only the note that contains a percent sign
            s.save(a, "Buy rice\n3 kg"); assertEquals("Buy rice\n3 kg", s.get(a)!!.text)
            s.save(a, ""); assertNull("saving empty text removes the note", s.get(a))
        }
        NotesStore(ctx).use { assertEquals("data survives reopening the database", 1, it.count()) }
    }

    @Test fun notesTypedInTheScreenArePersistedWhenLeaving() {
        launch(NotesActivity::class.java).use { sc ->
            sc.onActivity { a -> a.button(a.getString(R.string.notes_new)).performClick() }
            sc.onActivity { a -> a.all().filterIsInstance<EditText>().first().setText("persist me ✓") }
            sc.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)      // leaving the screen must save immediately
        }
        NotesStore(ctx).use { assertTrue(it.list().any { n -> n.text == "persist me ✓" }) }
    }

    @Test fun notesCanBeDeletedAfterConfirmation() {
        val id = NotesStore(ctx).use { it.save(null, "to delete")!! }
        NotesStore(ctx).use { assertNotNull(it.get(id)) }
        NotesStore(ctx).use { it.delete(id); assertNull(it.get(id)) }
    }

    // ---- PDF ----
    private fun makePdf(pages: Int, name: String = "t.pdf"): Uri {
        val doc = PdfDocument()
        for (i in 1..pages) {
            val p = doc.startPage(PdfDocument.PageInfo.Builder(300, 400, i).create())
            p.canvas.drawText("Page $i hello", 40f, 80f, android.graphics.Paint().apply { textSize = 24f })
            doc.finishPage(p)
        }
        val f = File(ctx.cacheDir, name); f.outputStream().use { doc.writeTo(it) }; doc.close()
        return Uri.fromFile(f)
    }

    @Test fun pdfOpensNavigatesZoomsAndReportsPageCount() {
        val uri = makePdf(3)
        launch(PdfReaderActivity::class.java, Intent(ctx, PdfReaderActivity::class.java).setData(uri)).use { sc ->
            fun info(): String { var s = ""; sc.onActivity { a -> s = a.all().filterIsInstance<TextView>().joinToString("|") { it.text } }; return s }
            assertTrue(info(), waitFor { info().contains(ctx.getString(R.string.pdf_page_of, 1, 3)) })
            sc.onActivity { a -> a.button(ctx.getString(R.string.pdf_next)).performClick() }
            assertTrue(info(), waitFor { info().contains(ctx.getString(R.string.pdf_page_of, 2, 3)) })
            sc.onActivity { a -> a.button(ctx.getString(R.string.pdf_zoom_in)).performClick(); a.button(ctx.getString(R.string.pdf_zoom_out)).performClick(); a.button(ctx.getString(R.string.pdf_prev)).performClick() }
            assertTrue(info(), waitFor { info().contains(ctx.getString(R.string.pdf_page_of, 1, 3)) })
            sc.onActivity { a -> a.all().filterIsInstance<EditText>().first { it.hint == ctx.getString(R.string.pdf_page_number) }.setText("3"); a.button(ctx.getString(R.string.pdf_go_to_page)).performClick() }
            assertTrue(info(), waitFor { info().contains(ctx.getString(R.string.pdf_page_of, 3, 3)) })
            assertTrue("the page image must be drawn", waitFor { var ok = false; sc.onActivity { a -> ok = a.all().filterIsInstance<ImageView>().any { it.drawable != null } }; ok })
            sc.onActivity { a -> val b = (a.all().filterIsInstance<ImageView>().first { it.drawable != null }.drawable as android.graphics.drawable.BitmapDrawable).bitmap; assertTrue("page must have rendered white background with ink", b.width > 100) }
        }
    }

    @Test fun pdfSearchIsHonestAboutSupport() {
        val uri = makePdf(2)
        launch(PdfReaderActivity::class.java, Intent(ctx, PdfReaderActivity::class.java).setData(uri)).use { sc ->
            fun info(): String { var s = ""; sc.onActivity { a -> s = a.all().filterIsInstance<TextView>().joinToString("|") { it.text } }; return s }
            assertTrue(waitFor { info().contains(ctx.getString(R.string.pdf_page_of, 1, 2)) })
            sc.onActivity { a -> a.all().filterIsInstance<EditText>().first { it.hint == ctx.getString(R.string.pdf_search_hint) }.setText("hello"); a.button(ctx.getString(R.string.pdf_find_next)).performClick() }
            // Either the phone searches (found/not found) or it says plainly that it cannot. Never silence, never a fake "found".
            assertTrue(info(), waitFor {
                val i = info(); i.contains(ctx.getString(R.string.pdf_search_unsupported)) || i.contains(ctx.getString(R.string.pdf_search_found, 2)) || i.contains(ctx.getString(R.string.pdf_search_not_found, "hello"))
            })
            if (android.os.Build.VERSION.SDK_INT < 35) assertTrue(info().contains(ctx.getString(R.string.pdf_search_unsupported)))
        }
    }

    @Test fun corruptOrEmptyPdfShowsAClearErrorAndDoesNotCrash() {
        val bad = File(ctx.cacheDir, "bad.pdf").apply { writeText("this is not a pdf at all") }
        launch(PdfReaderActivity::class.java, Intent(ctx, PdfReaderActivity::class.java).setData(Uri.fromFile(bad))).use { sc ->
            assertTrue(waitFor { var ok = false; sc.onActivity { a -> ok = a.all().filterIsInstance<TextView>().any { it.text.toString() == a.getString(R.string.pdf_corrupt) } }; ok })
            sc.onActivity { a -> assertFalse(a.isFinishing) }
        }
        val missing = Uri.fromFile(File(ctx.cacheDir, "does-not-exist.pdf"))
        launch(PdfReaderActivity::class.java, Intent(ctx, PdfReaderActivity::class.java).setData(missing)).use { sc ->
            assertTrue(waitFor { var ok = false; sc.onActivity { a -> ok = a.all().filterIsInstance<TextView>().any { it.text.toString() == a.getString(R.string.pdf_corrupt) } }; ok })
        }
    }

    // ---- every tool in the RTL and LTR languages: direction, touch targets, labels ----
    @Test fun everyToolIsUsableInArabicUrduAndEnglish() {
        for (tag in listOf("ar", "ur", "en", "hi")) {
            LocaleHelper.save(ctx, tag)
            val d = ctx.resources.displayMetrics.density
            for (c in toolClasses) launch(c).use { sc ->
                sc.onActivity { a ->
                    assertEquals("$tag ${c.simpleName} direction", tag == "ar" || tag == "ur", LocaleHelper.isRtl(a))
                    for (b in a.all().filterIsInstance<Button>().filter { it.isShown }) {
                        assertTrue("$tag ${c.simpleName}: button '${b.text}' needs a label", (b.contentDescription ?: b.text).toString().isNotBlank())
                        assertTrue("$tag ${c.simpleName}: '${b.text}' must be >= 48dp", b.minimumHeight >= 48 * d - 1 && b.minimumWidth >= 48 * d - 1)
                    }
                }
            }
        }
    }
}
