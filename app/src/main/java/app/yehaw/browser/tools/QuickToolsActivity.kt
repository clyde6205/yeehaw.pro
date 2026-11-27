package app.yehaw.browser.tools

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import app.yehaw.browser.R

/** The labelled Quick Tools section: six tools, each opened on demand. */
class QuickToolsActivity : ToolActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = scaffold(R.string.title_quick_tools)
        val currentUrl = intent?.getStringExtra(QuickTools.EXTRA_CURRENT_URL)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fun row(title: Int, subtitle: Int, target: Class<*>, withUrl: Boolean = false) {
            val label = getString(title)
            val b = button(label + "\n" + getString(subtitle), label) {
                startActivity(Intent(this, target).apply { if (withUrl && !currentUrl.isNullOrBlank()) putExtra(QuickTools.EXTRA_CURRENT_URL, currentUrl) })
            }
            b.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            b.tag = target.simpleName
            list.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }
        row(R.string.tool_calculator, R.string.tool_calculator_desc, CalculatorActivity::class.java)
        row(R.string.tool_converter, R.string.tool_converter_desc, UnitConverterActivity::class.java)
        row(R.string.tool_qr_scanner, R.string.tool_qr_scanner_desc, QrScannerActivity::class.java)
        row(R.string.tool_qr_generator, R.string.tool_qr_generator_desc, QrGeneratorActivity::class.java, withUrl = true)
        row(R.string.tool_notes, R.string.tool_notes_desc, NotesActivity::class.java)
        row(R.string.tool_pdf_reader, R.string.tool_pdf_reader_desc, PdfReaderActivity::class.java)
    }
}
