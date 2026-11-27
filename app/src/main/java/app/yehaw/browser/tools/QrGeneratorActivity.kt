package app.yehaw.browser.tools

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.yehaw.browser.R

/** QR Code Generator: any text or link, shown big, saved as PNG, or shared. Works fully offline. */
class QrGeneratorActivity : ToolActivity() {
    private lateinit var input: android.widget.EditText
    private lateinit var image: ImageView
    private lateinit var status: TextView
    private lateinit var save: android.widget.Button
    private lateinit var share: android.widget.Button
    private var bitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = scaffold(R.string.tool_qr_generator)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(ScrollView(this).apply { addView(col) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        input = field(getString(R.string.qr_gen_hint), multiline = true).apply { minLines = 3; maxLines = 6 }
        col.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val url = intent?.getStringExtra(QuickTools.EXTRA_CURRENT_URL)
        if (!url.isNullOrBlank()) {
            col.addView(gap(8))
            col.addView(button(getString(R.string.qr_gen_use_page), null) { input.setText(url); generate() })
        }
        col.addView(gap(8))
        col.addView(button(getString(R.string.qr_gen_create), null, primary = true) { generate() })
        status = text("", 14f, DANGER).apply { setPadding(0, dp(8), 0, dp(8)) }
        col.addView(status)

        image = ImageView(this).apply {
            adjustViewBounds = true
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = getString(R.string.qr_gen_image_description)
            visibility = android.view.View.GONE
        }
        col.addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)))
        col.addView(gap(8))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        save = button(getString(R.string.label_save), null) { saveImage() }
        share = button(getString(R.string.label_share), null) { shareImage() }
        actions.addView(save, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        actions.addView(share, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(actions)
        setActionsEnabled(false)

        // Typing again invalidates the old picture so a stale code can never be saved or shared by mistake.
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { if (bitmap != null) clearImage() }
        })
        if (!url.isNullOrBlank() && savedInstanceState == null) { input.setText(url); generate() }
    }

    private fun setActionsEnabled(on: Boolean) { save.isEnabled = on; share.isEnabled = on }

    private fun clearImage() { image.setImageDrawable(null); image.visibility = android.view.View.GONE; bitmap = null; setActionsEnabled(false) }

    private fun generate() {
        val text = input.text.toString().trim()
        status.text = ""
        if (text.isEmpty()) { clearImage(); status.text = getString(R.string.qr_gen_empty); return }
        if (!QrImages.fits(text)) { clearImage(); status.text = getString(R.string.qr_gen_too_long, QrImages.MAX_BYTES); return }
        try {
            val bmp = QrImages.render(text)
            bitmap = bmp
            image.setImageBitmap(bmp)
            image.visibility = android.view.View.VISIBLE
            setActionsEnabled(true)
        } catch (_: OutOfMemoryError) {
            clearImage(); status.text = getString(R.string.qr_gen_failed)
        } catch (_: Exception) {
            clearImage(); status.text = getString(R.string.qr_gen_failed)
        }
    }

    private fun saveImage() {
        val bmp = bitmap ?: return
        val saved = QrImages.savePng(this, bmp)
        if (saved != null) toast(R.string.msg_screenshot_saved, saved.where) else toast(R.string.qr_gen_save_failed)
    }

    private fun shareImage() {
        val bmp = bitmap ?: return
        val uri = QrImages.shareUri(this, bmp)
        if (uri == null) { toast(R.string.qr_gen_save_failed); return }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(Intent.createChooser(send, getString(R.string.qr_gen_share_chooser))) } catch (_: Exception) { toast(R.string.qr_gen_save_failed) }
    }
}
