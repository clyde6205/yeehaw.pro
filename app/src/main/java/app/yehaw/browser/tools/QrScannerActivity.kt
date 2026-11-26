package app.yehaw.browser.tools

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.Camera
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.yehaw.browser.MainActivity
import app.yehaw.browser.R

/**
 * QR Code Scanner on the platform camera API (no camera library, no Google services). The camera runs only while this screen
 * is visible, frames are decoded about four times a second on a background thread (cheap on a one-core phone), and nothing
 * scanned is ever opened automatically: the person sees the real address first and taps to open it.
 */
@Suppress("DEPRECATION")
class QrScannerActivity : ToolActivity(), SurfaceHolder.Callback, Camera.PreviewCallback {
    private var camera: Camera? = null
    private var holderReady = false
    private lateinit var surface: SurfaceView
    private lateinit var holderBox: FrameLayout
    private lateinit var message: TextView
    private lateinit var allow: Button
    private lateinit var settings: Button
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var busy = false
    @Volatile private var done = false
    private var lastDecodeAt = 0L
    private var previewW = 0
    private var previewH = 0
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = scaffold(R.string.tool_qr_scanner)
        message = text(getString(R.string.qr_scan_point), 16f, TEXT).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        body.addView(message, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        allow = button(getString(R.string.qr_scan_allow_camera), null, primary = true) { requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA) }
        settings = button(getString(R.string.qr_scan_open_settings), null) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
        body.addView(allow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body.addView(settings, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        holderBox = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        surface = SurfaceView(this).apply { contentDescription = getString(R.string.qr_scan_preview_description) }
        holderBox.addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        body.addView(holderBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        surface.holder.addCallback(this)
        showPermissionState(hasPermission(), false)
    }

    private fun hasPermission() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun showPermissionState(granted: Boolean, denied: Boolean) {
        allow.visibility = if (granted) android.view.View.GONE else android.view.View.VISIBLE
        val permanent = denied && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        settings.visibility = if (permanent) android.view.View.VISIBLE else android.view.View.GONE
        holderBox.visibility = if (granted) android.view.View.VISIBLE else android.view.View.GONE
        message.text = when {
            granted -> getString(R.string.qr_scan_point)
            denied -> getString(R.string.qr_scan_permission_denied)
            else -> getString(R.string.qr_scan_permission_needed)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CAMERA) return
        val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        showPermissionState(ok, !ok)
        if (ok) startCameraIfReady()
    }

    override fun onResume() {
        super.onResume()
        if (hasPermission()) { showPermissionState(true, false); startCameraIfReady() }
    }

    override fun onPause() {
        dialog?.dismiss(); dialog = null
        releaseCamera()
        super.onPause()
    }

    override fun surfaceCreated(h: SurfaceHolder) { holderReady = true; startCameraIfReady() }
    override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hh: Int) {}
    override fun surfaceDestroyed(h: SurfaceHolder) { holderReady = false; releaseCamera() }

    private fun startCameraIfReady() {
        if (camera != null || !holderReady || !hasPermission() || dialog != null) return
        try {
            val id = (0 until Camera.getNumberOfCameras()).firstOrNull {
                val info = Camera.CameraInfo(); Camera.getCameraInfo(it, info); info.facing == Camera.CameraInfo.CAMERA_FACING_BACK
            } ?: 0
            if (Camera.getNumberOfCameras() == 0) { message.text = getString(R.string.qr_scan_camera_unavailable); return }
            val cam = Camera.open(id)
            val params = cam.parameters
            // A small preview is plenty for a QR code and keeps decoding light on slow CPUs.
            val best = params.supportedPreviewSizes.filter { it.width <= 960 && it.height <= 720 }
                .minByOrNull { Math.abs(it.width * it.height - 640 * 480) } ?: params.supportedPreviewSizes.minByOrNull { it.width * it.height }
            if (best != null) params.setPreviewSize(best.width, best.height)
            if (params.supportedFocusModes?.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) == true) params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            params.previewFormat = ImageFormat.NV21
            cam.parameters = params
            previewW = cam.parameters.previewSize.width
            previewH = cam.parameters.previewSize.height
            val orientation = displayOrientation(id)
            cam.setDisplayOrientation(orientation)
            fitSurface(orientation)
            cam.setPreviewDisplay(surface.holder)
            val bytes = previewW * previewH * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8
            repeat(2) { cam.addCallbackBuffer(ByteArray(bytes)) }
            cam.setPreviewCallbackWithBuffer(this)
            val t = HandlerThread("yeehaw-qr").also { it.start() }
            worker = t; handler = Handler(t.looper)
            done = false; busy = false
            cam.startPreview()
            camera = cam
        } catch (_: Exception) {
            releaseCamera()
            message.text = getString(R.string.qr_scan_camera_unavailable)
        }
    }

    private fun displayOrientation(cameraId: Int): Int {
        val info = Camera.CameraInfo(); Camera.getCameraInfo(cameraId, info)
        val rotation = windowManager.defaultDisplay.rotation
        val degrees = when (rotation) { android.view.Surface.ROTATION_90 -> 90; android.view.Surface.ROTATION_180 -> 180; android.view.Surface.ROTATION_270 -> 270; else -> 0 }
        return if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) (360 - (info.orientation + degrees) % 360) % 360
        else (info.orientation - degrees + 360) % 360
    }

    /** Keep the preview's real proportions instead of stretching it to the screen. */
    private fun fitSurface(orientation: Int) {
        holderBox.post {
            val portrait = orientation % 180 == 90
            val pw = if (portrait) previewH else previewW
            val ph = if (portrait) previewW else previewH
            val bw = holderBox.width; val bh = holderBox.height
            if (bw <= 0 || bh <= 0 || pw <= 0 || ph <= 0) return@post
            val scale = minOf(bw.toFloat() / pw, bh.toFloat() / ph)
            surface.layoutParams = FrameLayout.LayoutParams((pw * scale).toInt(), (ph * scale).toInt(), Gravity.CENTER)
        }
    }

    override fun onPreviewFrame(data: ByteArray?, cam: Camera?) {
        if (data == null || cam == null) return
        val now = SystemClock.elapsedRealtime()
        if (done || busy || now - lastDecodeAt < DECODE_INTERVAL_MS) { try { cam.addCallbackBuffer(data) } catch (_: Exception) {}; return }
        busy = true; lastDecodeAt = now
        val w = previewW; val h = previewH
        handler?.post {
            val found = try { QrDecoder.decodeYuv(data, w, h) } catch (_: Exception) { null }
            try { cam.addCallbackBuffer(data) } catch (_: Exception) {}
            if (found != null && !done) { done = true; runOnUiThread { onScanned(found) } }
            busy = false
        }
    }

    private fun releaseCamera() {
        val cam = camera; camera = null
        try { cam?.setPreviewCallbackWithBuffer(null); cam?.stopPreview() } catch (_: Exception) {}
        try { cam?.release() } catch (_: Exception) {}
        worker?.quitSafely(); worker = null; handler = null
    }

    private fun onScanned(raw: String) {
        if (isFinishing || isDestroyed) return
        val r = QrContent.classify(raw)
        val b = AlertDialog.Builder(this)
        when (r.kind) {
            QrContent.Kind.WEB_LINK -> {
                val sb = StringBuilder(r.text)
                sb.append("\n\n").append(getString(R.string.qr_result_site, r.host ?: ""))
                for (w in r.warnings) sb.append("\n• ").append(getString(warningText(w)))
                sb.append("\n\n").append(getString(R.string.qr_result_not_opened_yet))
                b.setTitle(R.string.qr_result_link).setMessage(sb.toString())
                    .setPositiveButton(R.string.qr_result_open) { _, _ -> openInBrowser(r.text) }
            }
            QrContent.Kind.UNSAFE_LINK -> b.setTitle(R.string.qr_result_unsafe)
                .setMessage(r.text + "\n\n" + getString(R.string.qr_result_unsafe_explain))
            QrContent.Kind.TEXT -> b.setTitle(R.string.qr_result_text).setMessage(r.text)
                .setPositiveButton(R.string.label_share) { _, _ -> shareText(r.text) }
        }
        b.setNeutralButton(R.string.btn_copy) { _, _ -> copyToClipboard(r.text) }
            .setNegativeButton(R.string.qr_result_scan_again) { _, _ -> scanAgain() }
            .setOnCancelListener { scanAgain() }
        dialog = b.show()
    }

    private fun warningText(w: QrContent.Warning) = when (w) {
        QrContent.Warning.NOT_ENCRYPTED -> R.string.qr_warn_not_encrypted
        QrContent.Warning.LOOKALIKE_HOST -> R.string.qr_warn_lookalike
        QrContent.Warning.ADDRESS_IS_NUMBERS -> R.string.qr_warn_numbers
        QrContent.Warning.HIDDEN_LOGIN_PART -> R.string.qr_warn_login_part
    }

    private fun scanAgain() {
        dialog = null
        done = false
        if (camera == null) startCameraIfReady() else try { camera?.startPreview() } catch (_: Exception) { releaseCamera(); startCameraIfReady() }
    }

    private fun openInBrowser(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setClass(this, MainActivity::class.java))
        finish()
    }

    private fun shareText(value: String) {
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, value) }
        try { startActivity(Intent.createChooser(send, getString(R.string.label_share))) } catch (_: Exception) {}
    }

    companion object {
        private const val REQ_CAMERA = 7201
        private const val DECODE_INTERVAL_MS = 250L
    }
}
