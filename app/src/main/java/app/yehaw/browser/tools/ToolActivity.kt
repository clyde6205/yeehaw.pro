package app.yehaw.browser.tools

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import app.yehaw.browser.LocaleHelper
import app.yehaw.browser.R

/**
 * Base for every Quick Tool screen. Each tool is its own Activity, so nothing here (and no tool code or library) is loaded
 * until the person opens that tool; the browser's startup path never touches this package.
 */
open class ToolActivity : Activity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    protected fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    /** Root column with a labelled Back button and the tool title; returns the body column to fill. */
    protected fun scaffold(titleRes: Int): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            fitsSystemWindows = true
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(12), dp(6))
        }
        bar.addView(button(getString(R.string.cd_back)) { finish() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        bar.addView(text(getString(titleRes), 20f, TEXT, bold = true).apply {
            setPadding(dp(12), 0, 0, 0)
            isFocusable = true
            accessibilityHeading()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        return body
    }

    private fun TextView.accessibilityHeading() {
        if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }

    protected fun text(value: CharSequence, size: Float = 16f, color: Int = TEXT, bold: Boolean = false): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    protected fun button(label: String, description: String? = null, primary: Boolean = false, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        contentDescription = description
        isAllCaps = false
        textSize = 16f
        minWidth = dp(48)
        minHeight = dp(48)
        minimumWidth = dp(48)
        minimumHeight = dp(48)
        setPadding(dp(14), 0, dp(14), 0)
        backgroundTintList = android.content.res.ColorStateList.valueOf(if (primary) ACCENT else PANEL)
        setTextColor(if (primary) 0xFF04101F.toInt() else TEXT)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    protected fun field(hintText: String, multiline: Boolean = false): EditText = EditText(this).apply {
        hint = hintText
        contentDescription = hintText
        setTextColor(TEXT)
        setHintTextColor(MUTED)
        textSize = 16f
        minHeight = dp(48)
        setBackgroundColor(PANEL)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        if (multiline) {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            gravity = Gravity.TOP or Gravity.START
        } else {
            setSingleLine(true)
        }
    }

    protected fun gap(heightDp: Int = 12): View = View(this).also { it.minimumHeight = dp(heightDp) }

    protected fun copyToClipboard(value: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("YeeHaw", value))
        android.widget.Toast.makeText(this, getString(R.string.toast_copied), android.widget.Toast.LENGTH_SHORT).show()
    }

    protected fun toast(resId: Int, vararg args: Any) {
        android.widget.Toast.makeText(this, getString(resId, *args), android.widget.Toast.LENGTH_LONG).show()
    }

    companion object {
        const val BG = 0xFF080D18.toInt()
        const val PANEL = 0xFF17263B.toInt()
        const val TEXT = 0xFFF3F7FF.toInt()
        const val MUTED = 0xFFA5B4CA.toInt()
        const val ACCENT = 0xFF6CB6FF.toInt()
        const val DANGER = 0xFFFFB4AB.toInt()
    }
}
