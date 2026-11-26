package app.yehaw.browser.tools

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.yehaw.browser.R
import java.io.File
import java.util.concurrent.Executors

/**
 * PDF reader on Android's own [PdfRenderer] (no PDF library is bundled). The file is chosen with the system picker, so no
 * storage permission is needed and nothing leaves the phone. One page is rendered at a time, off the main thread, at a size
 * capped to keep a 1 GB phone safe. Text search uses the platform's search where the phone has it (Android 15+ with the
 * PDF extension) and says plainly when it does not.
 */
class PdfReaderActivity : ToolActivity() {
    private val worker get() = WORKER   // PdfRenderer is not thread-safe: every call, from every reader instance, goes through one thread
    private val ui = Handler(Looper.getMainLooper())

    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var tempCopy: File? = null
    private var pageCount = 0
    private var page = 0
    private var zoom = 1f
    private var uri: Uri? = null
    private var matches: List<RectF> = emptyList()      // in PDF points for the current page
    private var pagePointsW = 1f
    private var pagePointsH = 1f
    private var renderToken = 0

    private lateinit var image: ImageView
    private lateinit var status: TextView
    private lateinit var pageInfo: TextView
    private lateinit var searchBox: EditText
    private lateinit var viewer: LinearLayout
    private lateinit var scroller: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = scaffold(R.string.tool_pdf_reader)
        status = text(getString(R.string.pdf_choose_prompt), 16f, MUTED)
        body.addView(button(getString(R.string.pdf_open), primary = true) { pick() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        viewer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = android.view.View.GONE }
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = android.view.View.LAYOUT_DIRECTION_LTR }
        fun nb(label: String, desc: String, a: () -> Unit) = nav.addView(button(label, desc) { a() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        nb("◀", getString(R.string.pdf_prev)) { goTo(page - 1) }
        pageInfo = text("", 15f, TEXT).apply { gravity = Gravity.CENTER; isFocusable = true }
        nav.addView(pageInfo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f))
        nb("▶", getString(R.string.pdf_next)) { goTo(page + 1) }
        nb("−", getString(R.string.pdf_zoom_out)) { setZoom(zoom / 1.5f) }
        nb("+", getString(R.string.pdf_zoom_in)) { setZoom(zoom * 1.5f) }
        viewer.addView(nav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val goRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val jump = field(getString(R.string.pdf_page_number)).apply { inputType = InputType.TYPE_CLASS_NUMBER; layoutDirection = android.view.View.LAYOUT_DIRECTION_LTR }
        goRow.addView(jump, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        goRow.addView(button(getString(R.string.pdf_go_to_page)) { jump.text.toString().toIntOrNull()?.let { goTo(it - 1) } },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        viewer.addView(goRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })

        searchBox = field(getString(R.string.pdf_search_hint))
        val searchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        searchRow.addView(searchBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        searchRow.addView(button(getString(R.string.pdf_find_next)) { search() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        viewer.addView(searchRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })

        image = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_START }
        val h = HorizontalScrollView(this).apply { addView(image) }
        scroller = ScrollView(this).apply { addView(h) }
        viewer.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(6) })
        body.addView(viewer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // An explicit (non-exported) launch may hand over a document directly, which is how the tests open a generated PDF.
        if (savedInstanceState == null) intent?.data?.let { open(it, keepPage = false) }
        savedInstanceState?.getString(STATE_URI)?.let { s ->
            page = savedInstanceState.getInt(STATE_PAGE, 0); zoom = savedInstanceState.getFloat(STATE_ZOOM, 1f)
            open(Uri.parse(s), keepPage = true)
        }
    }

    private fun pick() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf"), REQ_PICK)
        } catch (e: Exception) { showError(R.string.pdf_no_picker) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) data?.data?.let { page = 0; zoom = 1f; open(it, keepPage = false) }
    }

    private fun showError(res: Int) {
        closeDocument()
        viewer.visibility = android.view.View.GONE
        status.text = getString(res); status.setTextColor(DANGER)
    }

    private fun open(u: Uri, keepPage: Boolean) {
        closeDocument()
        status.setTextColor(MUTED); status.text = getString(R.string.pdf_opening)
        worker.execute {
            var error = 0
            try {
                val fd = try { contentResolver.openFileDescriptor(u, "r") } catch (e: Exception) { null }
                var r: PdfRenderer? = null
                if (fd != null) r = try { PdfRenderer(fd) } catch (e: java.io.IOException) { null } catch (e: SecurityException) { error = R.string.pdf_password; null }
                if (r == null && error == 0) {
                    // Some providers hand out a pipe, which PdfRenderer cannot seek in: copy to a private temp file (capped) and retry.
                    try { fd?.close() } catch (_: Exception) {}
                    val f = File(cacheDir, "pdf_open.tmp")
                    var total = 0L
                    contentResolver.openInputStream(u)?.use { input -> f.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) { val n = input.read(buf); if (n < 0) break; total += n; if (total > MAX_COPY_BYTES) throw java.io.IOException("too large"); out.write(buf, 0, n) }
                    } } ?: throw java.io.IOException("unreadable")
                    val fd2 = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                    try { r = PdfRenderer(fd2); pfd = fd2; tempCopy = f } catch (e: SecurityException) { error = R.string.pdf_password; fd2.close() }
                } else if (r != null) pfd = fd
                if (r != null) {
                    renderer = r; pageCount = r.pageCount
                    if (pageCount <= 0) { closeDocumentOnWorker(); error = R.string.pdf_empty }
                } else if (error == 0) error = R.string.pdf_corrupt
            } catch (e: OutOfMemoryError) { error = R.string.pdf_too_big
            } catch (e: Exception) { if (error == 0) error = R.string.pdf_corrupt }
            ui.post {
                if (isFinishing || isDestroyed) return@post
                if (error != 0) { showError(error); return@post }
                uri = u
                page = if (keepPage) page.coerceIn(0, pageCount - 1) else 0
                status.text = getString(R.string.pdf_loaded_pages, pageCount)
                viewer.visibility = android.view.View.VISIBLE
                render()
            }
        }
    }

    private fun goTo(p: Int) {
        if (renderer == null) return
        if (p < 0 || p >= pageCount) { toast(R.string.pdf_page_out_of_range, pageCount); return }
        page = p; matches = emptyList(); render()
    }

    private fun setZoom(z: Float) { zoom = z.coerceIn(1f, 4f); render() }

    private fun render() {
        val token = ++renderToken
        val widthPx = (resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(200)
        pageInfo.text = getString(R.string.pdf_page_of, page + 1, pageCount)
        pageInfo.contentDescription = pageInfo.text
        worker.execute {
            val r = renderer ?: return@execute
            var bmp: Bitmap? = null
            var failed = false
            try {
                val p = synchronized(this) { r.openPage(page) }
                try {
                    pagePointsW = p.width.toFloat(); pagePointsH = p.height.toFloat()
                    // Cap the bitmap at ~6M pixels (24 MB): zoom stops growing the image beyond what a small phone can hold.
                    var w = (widthPx * zoom).toInt()
                    var h = (w * p.height.toFloat() / p.width).toInt()
                    val maxPixels = 6_000_000L
                    if (w.toLong() * h > maxPixels) { val s = Math.sqrt(maxPixels.toDouble() / (w.toLong() * h)); w = (w * s).toInt(); h = (h * s).toInt() }
                    bmp = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    drawMatches(bmp)
                } finally { p.close() }
            } catch (e: OutOfMemoryError) { failed = true; bmp = null
            } catch (e: Exception) { failed = true; bmp = null }
            ui.post {
                if (token != renderToken || isFinishing || isDestroyed) return@post
                if (failed || bmp == null) { toast(R.string.pdf_page_failed) } else {
                    image.setImageBitmap(bmp)
                    image.contentDescription = getString(R.string.pdf_page_of, page + 1, pageCount)
                    scroller.scrollTo(0, 0)
                }
            }
        }
    }

    private fun drawMatches(bmp: Bitmap) {
        if (matches.isEmpty()) return
        val c = Canvas(bmp)
        val paint = Paint().apply { color = 0x66FFEB3B; style = Paint.Style.FILL }
        val sx = bmp.width / pagePointsW; val sy = bmp.height / pagePointsH
        for (m in matches) c.drawRect(m.left * sx, m.top * sy, m.right * sx, m.bottom * sy, paint)
    }

    /** Find the next page (from the current one) that contains the query. Honest about phones that cannot search. */
    private fun search() {
        val q = searchBox.text.toString().trim()
        if (renderer == null || q.isEmpty()) return
        // Say so up front when this phone cannot search; do not touch the renderer at all in that case.
        if (!PdfSearch.isSupported()) { status.text = getString(R.string.pdf_search_unsupported); status.setTextColor(DANGER); return }
        status.setTextColor(MUTED); status.text = getString(R.string.pdf_searching)
        val start = page
        worker.execute {
            val r = renderer ?: return@execute
            var foundPage = -1; var found: List<RectF> = emptyList(); var unsupported = false
            try {
                for (i in 1..pageCount) {
                    val idx = (start + i) % pageCount          // starts after the current page and wraps around, ending on it
                    val p = synchronized(this) { r.openPage(idx) }
                    try {
                        val res = PdfSearch.search(p, q)
                        if (res == null) { unsupported = true; break }
                        if (res.isNotEmpty()) { foundPage = idx; found = res; pagePointsW = p.width.toFloat(); pagePointsH = p.height.toFloat(); break }
                    } finally { p.close() }
                }
            } catch (e: Exception) { unsupported = true }
            ui.post {
                if (isFinishing || isDestroyed) return@post
                when {
                    unsupported -> { status.text = getString(R.string.pdf_search_unsupported); status.setTextColor(DANGER) }
                    foundPage < 0 -> status.text = getString(R.string.pdf_search_not_found, q)
                    else -> { page = foundPage; matches = found; status.text = getString(R.string.pdf_search_found, foundPage + 1); render() }
                }
            }
        }
    }

    private fun closeDocumentOnWorker() { try { renderer?.close() } catch (_: Exception) {}; try { pfd?.close() } catch (_: Exception) {}; renderer = null; pfd = null; tempCopy?.delete(); tempCopy = null }

    private fun closeDocument() {
        renderToken++
        worker.execute { closeDocumentOnWorker() }
        matches = emptyList(); pageCount = 0
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        uri?.let { outState.putString(STATE_URI, it.toString()); outState.putInt(STATE_PAGE, page); outState.putFloat(STATE_ZOOM, zoom) }
    }

    override fun onDestroy() {
        renderToken++
        worker.execute { closeDocumentOnWorker() }
        super.onDestroy()
    }

    companion object {
        /** One process-wide thread: the platform PDF engine on older Android crashes when two instances open or close at once. */
        private val WORKER = Executors.newSingleThreadExecutor { r -> Thread(r, "yeehaw-pdf").apply { isDaemon = true } }
        private const val REQ_PICK = 4101
        private const val STATE_URI = "pdf_uri"; private const val STATE_PAGE = "pdf_page"; private const val STATE_ZOOM = "pdf_zoom"
        private const val MAX_COPY_BYTES = 150L * 1024 * 1024
    }
}

/**
 * Text search through the platform's PdfRenderer.Page.searchText (Android 15+ with the PDF extension). Called by reflection so
 * the app compiles and runs on every supported version; returns null when this phone cannot search, never a fake "no match".
 */
internal object PdfSearch {
    /** True only on a phone whose PdfRenderer.Page really has searchText (Android 15+ with the PDF extension). */
    fun isSupported(): Boolean = android.os.Build.VERSION.SDK_INT >= 35 && try {
        PdfRenderer.Page::class.java.getMethod("searchText", String::class.java); true
    } catch (e: NoSuchMethodException) { false } catch (e: Exception) { false }

    fun search(page: PdfRenderer.Page, query: String): List<RectF>? {
        if (android.os.Build.VERSION.SDK_INT < 35) return null
        return try {
            val m = page.javaClass.getMethod("searchText", String::class.java)
            val list = m.invoke(page, query) as? List<*> ?: return null
            val out = ArrayList<RectF>()
            for (match in list) {
                val bounds = match?.javaClass?.getMethod("getBounds")?.invoke(match) as? List<*> ?: continue
                for (b in bounds) if (b is RectF) out.add(b)
            }
            out
        } catch (e: NoSuchMethodException) { null
        } catch (e: UnsupportedOperationException) { null
        } catch (e: java.lang.reflect.InvocationTargetException) { null
        } catch (e: IllegalAccessException) { null }
    }
}
