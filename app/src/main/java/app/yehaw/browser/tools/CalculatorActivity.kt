package app.yehaw.browser.tools

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import app.yehaw.browser.R

/** Calculator. Digits and operators stay left-to-right in every language (numbers are LTR even inside Arabic or Urdu). */
class CalculatorActivity : ToolActivity() {
    private val expr = StringBuilder()
    private lateinit var expression: TextView
    private lateinit var result: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        expr.append(savedInstanceState?.getString(STATE_EXPR).orEmpty())
        val body = scaffold(R.string.tool_calculator)

        val display = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setBackgroundColor(PANEL)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        expression = text("", 28f, TEXT).apply { gravity = Gravity.END; maxLines = 2; isFocusable = true; contentDescription = null }
        result = text("", 22f, MUTED).apply { gravity = Gravity.END; maxLines = 1 }
        display.addView(expression, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        display.addView(result, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body.addView(display, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body.addView(gap(8))

        val pad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        fun row(vararg keys: Triple<String, Int?, () -> Unit>) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((label, desc, action) in keys) {
                val b = button(label, desc?.let { getString(it) }, primary = label == "=") { action() }
                b.textSize = 20f
                b.minimumHeight = dp(56)
                r.addView(b, LinearLayout.LayoutParams(0, dp(56), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            }
            pad.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        fun key(label: String, desc: Int? = null, insert: String = label) = Triple(label, desc, { append(insert) })
        row(Triple("C", R.string.calc_clear, { expr.setLength(0); refresh() }), key("(", R.string.calc_open_paren), key(")", R.string.calc_close_paren),
            Triple("⌫", R.string.calc_backspace, { if (expr.isNotEmpty()) expr.setLength(expr.length - 1); refresh() }))
        row(key("7"), key("8"), key("9"), key("÷", R.string.calc_divide))
        row(key("4"), key("5"), key("6"), key("×", R.string.calc_multiply))
        row(key("1"), key("2"), key("3"), key("−", R.string.calc_minus, insert = "-"))
        row(key("0"), key(".", R.string.calc_decimal_point), key("%", R.string.calc_percent), key("+", R.string.calc_plus))
        row(Triple("=", R.string.calc_equals, { equals() }))
        body.addView(pad, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        refresh()
    }

    private fun append(token: String) {
        if (expr.length >= MAX_LEN) return
        val isOp = token in listOf("÷", "×", "-", "+")
        // Typing an operator right after another one replaces it (except a leading minus or one after an open bracket).
        if (isOp && expr.isNotEmpty() && expr.last().toString() in listOf("÷", "×", "-", "+")) expr.setLength(expr.length - 1)
        if (isOp && token != "-" && token != "+" && (expr.isEmpty() || expr.last() == '(')) return
        expr.append(token)
        refresh()
    }

    private fun equals() {
        if (expr.isEmpty()) return
        try {
            val value = Calculator.calculate(expr.toString())
            expr.setLength(0); expr.append(value)
            result.text = ""
            result.contentDescription = null
        } catch (e: Calculator.CalcException) {
            result.text = getString(if (e.kind == Calculator.CalcException.Kind.DIVIDE_BY_ZERO) R.string.calc_error_divide_by_zero
                else if (e.kind == Calculator.CalcException.Kind.TOO_LARGE) R.string.calc_error_too_large else R.string.calc_error_syntax)
            result.setTextColor(DANGER)
        }
        expression.text = display(expr.toString())
    }

    private fun refresh() {
        expression.text = display(expr.toString())
        result.setTextColor(MUTED)
        // Live preview only when the expression is already valid and contains an operation.
        result.text = try {
            if (expr.any { it in "+-×÷%" } && expr.isNotEmpty()) "= " + Calculator.calculate(expr.toString()) else ""
        } catch (_: Calculator.CalcException) { "" }
    }

    private fun display(s: String) = s.replace('-', '−')

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_EXPR, expr.toString())
    }

    companion object {
        private const val STATE_EXPR = "expr"
        private const val MAX_LEN = 120
    }
}
