package app.yehaw.browser.tools

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import app.yehaw.browser.console.QrCode
import java.io.File
import java.io.FileOutputStream

/** Creating, saving and sharing QR images. Saving never needs a storage permission on Android 10+. */
object QrImages {
    const val MAX_BYTES = 213   // largest payload the bundled generator supports (QR version 10, error correction M)

    fun fits(text: String): Boolean = text.toByteArray(Charsets.UTF_8).size <= QrCode.capacityBytes(QrCode.MAX_VERSION)

    /** Renders [text] at about [targetPx] pixels per side. Throws IllegalArgumentException if the text is too long. */
    fun render(text: String, targetPx: Int = 720): Bitmap {
        val qr = QrCode.encodeText(text)
        val scale = maxOf(4, targetPx / qr.pixelSide(1))
        val side = qr.pixelSide(scale)
        return Bitmap.createBitmap(qr.toArgbPixels(scale), side, side, Bitmap.Config.ARGB_8888)
    }

    class Saved(val uri: Uri?, val where: String)

    fun savePng(context: Context, bitmap: Bitmap): Saved? {
        val name = "YeeHaw_QR_" + System.currentTimeMillis() + ".png"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/YeeHaw")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
                val ok = context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: false
                if (!ok) { context.contentResolver.delete(uri, null, null); return null }
                values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                Saved(uri, "Pictures/YeeHaw")
            } else {
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
                dir.mkdirs()
                val file = File(dir, name)
                val ok = FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                if (!ok) { file.delete(); return null }
                Saved(null, file.absolutePath)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** A content:// URI other apps can read for sharing (works on every Android version, no storage permission). */
    fun shareUri(context: Context, bitmap: Bitmap): Uri? = try {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.filter { it.name.startsWith("qr_") && System.currentTimeMillis() - it.lastModified() > 3_600_000L }?.forEach { it.delete() }
        val file = File(dir, "qr_" + System.currentTimeMillis() + ".png")
        val ok = FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (ok) FileProvider.getUriForFile(context, context.packageName + ".shareprovider", file) else null
    } catch (_: Exception) {
        null
    }
}
