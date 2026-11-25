package app.yehaw.browser.core

import android.app.Activity
import android.app.Dialog
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/* ---------------------------------------------------------------- Storage Protection */

/**
 * Storage Protection never deletes user data. It reads free space, refuses to start writes
 * that could fill a nearly-full phone, and reports sizes so the person can decide.
 */
object StorageGuard {
    const val LOW_BYTES = 300L * 1024 * 1024
    const val CRITICAL_BYTES = 100L * 1024 * 1024

    enum class Level { OK, LOW, CRITICAL }

    fun freeBytes(context: Context): Long = try {
        StatFs(context.filesDir.absolutePath).availableBytes
    } catch (_: Exception) {
        Long.MAX_VALUE
    }

    fun totalBytes(context: Context): Long = try {
        StatFs(context.filesDir.absolutePath).totalBytes
    } catch (_: Exception) {
        0L
    }

    fun level(context: Context): Level {
        val free = freeBytes(context)
        return when {
            free < CRITICAL_BYTES -> Level.CRITICAL
            free < LOW_BYTES -> Level.LOW
            else -> Level.OK
        }
    }

    /** True when it is safe to write [bytesNeeded] and still leave the phone a safety margin. */
    fun canWrite(context: Context, bytesNeeded: Long): Boolean = freeBytes(context) - bytesNeeded > CRITICAL_BYTES

    fun dirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        var total = 0L
        dir.listFiles()?.forEach { total += dirSize(it) }
        return total
    }

    fun appCacheBytes(context: Context): Long = dirSize(context.cacheDir)

    fun appDataBytes(context: Context): Long = dirSize(context.filesDir) + dirSize(context.getDatabasePath("x").parentFile)

    fun format(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / 1048576.0)
        else -> String.format("%.2f GB", bytes / 1073741824.0)
    }
}

/* ---------------------------------------------------------------- Adaptive Data Saver */

/** Decides when heavy assets (images, video, fonts) are skipped beyond the always-on Turbo mode. */
object DataSaverPolicy {
    /** True when Android's own Data Saver is on for this app. */
    fun systemDataSaverOn(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        } catch (_: Exception) {
            false
        }
    }

    fun shouldBlockHeavy(adaptiveOn: Boolean, signal: SignalLevel, systemSaver: Boolean): Boolean =
        adaptiveOn && (signal.textFirst || systemSaver)
}

/* ---------------------------------------------------------------- Quick Paste */

/** A small on-device list of snippets (phone number, address, greeting). Never leaves the phone. */
class QuickPasteStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("quick_paste", Context.MODE_PRIVATE)

    fun list(): List<String> {
        val raw = prefs.getString("items", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun add(text: String): Boolean {
        val clean = text.trim().take(MAX_LEN)
        if (clean.isEmpty()) return false
        val items = list().toMutableList()
        if (items.contains(clean)) return true
        if (items.size >= MAX_ITEMS) return false
        items.add(0, clean)
        save(items)
        return true
    }

    fun remove(index: Int) {
        val items = list().toMutableList()
        if (index in items.indices) {
            items.removeAt(index)
            save(items)
        }
    }

    private fun save(items: List<String>) {
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    companion object {
        const val MAX_ITEMS = 20
        const val MAX_LEN = 500

        /** JS that types [text] into the focused field. Resolves to true when it found one. */
        fun insertScript(text: String): String =
            "(function(t){var e=document.activeElement;if(!e)return false;" +
                "if(e.isContentEditable){document.execCommand('insertText',false,t);return true;}" +
                "if(typeof e.value==='string'){var s=e.selectionStart||0,n=e.selectionEnd||0;" +
                "e.value=e.value.slice(0,s)+t+e.value.slice(n);e.selectionStart=e.selectionEnd=s+t.length;" +
                "e.dispatchEvent(new Event('input',{bubbles:true}));return true;}return false;})(" +
                JSONObject.quote(text) + ")"
    }
}

/* ---------------------------------------------------------------- Clipboard Convenience */

/**
 * Opt-in. When the app comes to the foreground, offers to open a copied link. It reads the
 * clipboard only while this app has focus, only when the person turned it on, never sends it
 * anywhere, and offers each distinct copied link once.
 */
class ClipboardWatcher(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("clipboard_convenience", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) {
            prefs.edit().putBoolean("enabled", value).apply()
        }

    /** The copied link to offer, or null (not enabled, no link, or already offered). */
    fun pendingLink(): String? {
        if (!enabled) return null
        val text = try {
            val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.trim()
        } catch (_: Exception) {
            null
        } ?: return null
        if (!isLink(text)) return null
        val hash = text.hashCode().toString()
        if (prefs.getString("last_offered", null) == hash) return null
        prefs.edit().putString("last_offered", hash).apply()
        return text
    }

    companion object {
        fun isLink(text: String): Boolean =
            text.length in 8..2000 && !text.contains(' ') && !text.contains('\n') &&
                (text.startsWith("https://") || text.startsWith("http://"))
    }
}

/* ---------------------------------------------------------------- Screenshot & Markup */

object ScreenshotTool {
    /** Captures the visible part of the page at 16-bit colour: half the memory of the default. */
    fun capture(webView: WebView): Bitmap? {
        val w = webView.width
        val h = webView.height
        if (w <= 0 || h <= 0) return null
        return try {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            webView.draw(Canvas(bmp))
            bmp
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
    }

    class Saved(val uri: Uri?, val where: String)

    fun save(context: Context, bitmap: Bitmap): Saved? {
        val name = "YeeHaw_" + System.currentTimeMillis() + ".jpg"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/YeeHaw")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
                context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                Saved(uri, "Pictures/YeeHaw")
            } else {
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
                val file = File(dir, name)
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                Saved(null, file.absolutePath)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun share(activity: Activity, uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(send, "Share screenshot"))
    }
}

/** Finger-drawing surface over a bitmap. Strokes are stored in bitmap coordinates. */
class MarkupView(context: Context, private val base: Bitmap) : View(context) {
    private class Stroke(val path: Path, val color: Int, val width: Float)

    private val strokes = mutableListOf<Stroke>()
    private var current: Stroke? = null
    private var color = Color.RED
    private var scale = 1f
    private var offX = 0f
    private var offY = 0f
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun setPenColor(value: Int) {
        color = value
    }

    fun undo() {
        if (strokes.isNotEmpty()) {
            strokes.removeAt(strokes.size - 1)
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        scale = minOf(w.toFloat() / base.width, h.toFloat() / base.height)
        offX = (w - base.width * scale) / 2f
        offY = (h - base.height * scale) / 2f
    }

    private fun drawStrokes(canvas: Canvas) {
        (strokes + listOfNotNull(current)).forEach {
            strokePaint.color = it.color
            strokePaint.strokeWidth = it.width
            canvas.drawPath(it.path, strokePaint)
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        canvas.save()
        canvas.translate(offX, offY)
        canvas.scale(scale, scale)
        canvas.drawBitmap(base, 0f, 0f, bitmapPaint)
        drawStrokes(canvas)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = (event.x - offX) / scale
        val y = (event.y - offY) / scale
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val p = Path().apply { moveTo(x, y) }
                current = Stroke(p, color, 6f / scale + 2f)
            }
            MotionEvent.ACTION_MOVE -> current?.path?.lineTo(x, y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                current?.let { strokes.add(it) }
                current = null
            }
        }
        invalidate()
        return true
    }

    /** The screenshot with the drawing burned in. */
    fun render(): Bitmap? = try {
        val out = base.copy(Bitmap.Config.RGB_565, true)
        val c = Canvas(out)
        drawStrokes(c)
        out
    } catch (_: OutOfMemoryError) {
        null
    }
}

object MarkupScreen {
    fun show(activity: Activity, shot: Bitmap, toast: (String) -> Unit) {
        val dialog = Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen)
        val root = FrameLayout(activity)
        val view = MarkupView(activity, shot)
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#CC080D18"))
            gravity = Gravity.CENTER
        }
        fun button(label: String, action: () -> Unit) {
            bar.addView(Button(activity).apply {
                text = label
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        button("Red") { view.setPenColor(Color.RED) }
        button("Yellow") { view.setPenColor(Color.YELLOW) }
        button("Black") { view.setPenColor(Color.BLACK) }
        button("Undo") { view.undo() }
        button("Save") {
            val bmp = view.render()
            val saved = bmp?.let { ScreenshotTool.save(activity, it) }
            toast(if (saved != null) "Saved to ${saved.where}" else "Could not save the screenshot.")
        }
        button("Share") {
            val bmp = view.render()
            val saved = bmp?.let { ScreenshotTool.save(activity, it) }
            if (saved?.uri != null) ScreenshotTool.share(activity, saved.uri)
            else toast(if (saved != null) "Saved to ${saved.where}. Sharing needs Android 10 or newer." else "Could not save the screenshot.")
        }
        button("Close") { dialog.dismiss() }
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        dialog.setContentView(root)
        dialog.show()
    }
}
