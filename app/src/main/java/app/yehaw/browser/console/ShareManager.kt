package app.yehaw.browser.console

import app.yehaw.browser.R
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

/**
 * Growth engine: every user can hand the app to the next person in seconds.
 *  1. QR code on screen (works with no data at all; the other phone just scans it).
 *  2. One-tap share to the channels people already use: WhatsApp, Messenger, Telegram, Viber, SMS, or any app.
 *  3. Share the installed APK itself over Bluetooth / Nearby Share for people with no data (direct-download installs only).
 * Every link carries a random referral code and channel so the founder console can see what spreads.
 */
object ShareManager {
    private const val PREFS = "yeehaw_console"
    private const val KEY_REF = "share_ref"

    enum class Channel(val id: String, val pkg: String?) {
        WHATSAPP("whatsapp", "com.whatsapp"),
        MESSENGER("messenger", "com.facebook.orca"),
        TELEGRAM("telegram", "org.telegram.messenger"),
        VIBER("viber", "com.viber.voip"),
        SMS("sms", null),
        SYSTEM("system", null),
    }

    fun referralCode(context: Context): String {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString(KEY_REF, null)?.takeIf { ShareCore.isValidReferralCode(it) }?.let { return it }
        val code = ShareCore.newReferralCode()
        p.edit().putString(KEY_REF, code).apply()
        return code
    }

    fun link(context: Context, channel: String): String =
        ShareCore.link(BackendConfig.SHARE_BASE_URL, referralCode(context), channel)

    private fun text(context: Context, channel: String): String =
        ShareCore.message(Locale.getDefault().language, link(context, channel))

    /** Shares the link through [channel]; falls back to the system chooser if that app is not installed. */
    fun shareVia(activity: Activity, channel: Channel) {
        val body = text(activity, channel.id)
        val intent = if (channel == Channel.SMS) {
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).putExtra("sms_body", body)
        } else {
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body).also { i ->
                channel.pkg?.let { pkg -> if (isInstalled(activity, pkg)) i.setPackage(pkg) }
            }
        }
        try {
            activity.startActivity(if (intent.`package` == null && channel != Channel.SMS) Intent.createChooser(intent, null) else intent)
            TelemetryManager.sendShareEvent("shared", channel.id, referralCode(activity))
        } catch (_: ActivityNotFoundException) {
            // Nothing can handle it: ignore quietly.
        }
    }

    private fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: Throwable) {
        false
    }

    /** Full-screen-bright QR dialog: scan, or tap Share / WhatsApp. The bitmap is freed on dismiss. */
    fun showQrDialog(activity: Activity) {
        val url = link(activity, "qr")
        val qr = QrCode.encodeText(url)
        val density = activity.resources.displayMetrics.density
        val targetPx = (260 * density).toInt().coerceIn(240, 720)
        val scale = (targetPx / (qr.size + 8)).coerceIn(3, 14)
        val side = qr.pixelSide(scale)
        val bmp = Bitmap.createBitmap(qr.toArgbPixels(scale), side, side, Bitmap.Config.ARGB_8888)

        val pad = (16 * density).toInt()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, 0)
        }
        box.addView(TextView(activity).apply {
            text = activity.getString(R.string.qr_scan_caption)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
        })
        box.addView(ImageView(activity).apply {
            setImageBitmap(bmp)
            setPadding(0, pad, 0, pad)
            contentDescription = url
        })
        box.addView(TextView(activity).apply {
            this.text = url
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
        })

        val dialog = AlertDialog.Builder(activity)
            .setView(box)
            .setPositiveButton(activity.getString(R.string.label_share)) { _, _ -> shareVia(activity, Channel.SYSTEM) }
            .setNeutralButton(activity.getString(R.string.share_whatsapp)) { _, _ -> shareVia(activity, Channel.WHATSAPP) }
            .setNegativeButton(activity.getString(R.string.btn_close), null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.let { w ->
                val lp = w.attributes
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL // easier for the other phone to scan
                w.attributes = lp
            }
        }
        dialog.setOnDismissListener { bmp.recycle() }
        dialog.show()
        TelemetryManager.sendShareEvent("qr_shown", "qr", referralCode(activity))
    }

    /** True only for direct-download (single-APK) installs; Play App Bundle installs are split and cannot be re-shared. */
    fun canShareApk(context: Context): Boolean {
        val info = context.applicationInfo
        return info.splitSourceDirs.isNullOrEmpty() && File(info.sourceDir).exists()
    }

    /** Shares this app's own APK file (Bluetooth, Nearby Share, file-share apps): installs with no mobile data at all. */
    fun shareApk(activity: Activity) {
        if (!canShareApk(activity)) return
        try {
            val dir = File(activity.cacheDir, "share").apply { mkdirs() }
            val src = File(activity.applicationInfo.sourceDir)
            val dst = File(dir, "YeeHaw.apk")
            if (!dst.exists() || dst.length() != src.length()) src.copyTo(dst, overwrite = true)
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.shareprovider", dst)
            val send = Intent(Intent.ACTION_SEND)
                .setType("application/vnd.android.package-archive")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            activity.startActivity(Intent.createChooser(send, null))
            TelemetryManager.sendShareEvent("apk_shared", "bluetooth", referralCode(activity))
        } catch (_: Throwable) {
            // Out of storage or no handler: the QR/link flow still works.
        }
    }
}
