package app.yehaw.browser.tools

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import app.yehaw.browser.R

/** Offline unit converter with live results. The last category and units are remembered. */
class UnitConverterActivity : ToolActivity() {
    private lateinit var category: Spinner
    private lateinit var fromUnit: Spinner
    private lateinit var toUnit: Spinner
    private lateinit var input: android.widget.EditText
    private lateinit var result: TextView
    private lateinit var status: TextView
    private var current = UnitConverter.Category.LENGTH
    private var binding = false

    private val categoryNames = mapOf(
        UnitConverter.Category.LENGTH to R.string.unit_cat_length,
        UnitConverter.Category.MASS to R.string.unit_cat_mass,
        UnitConverter.Category.TEMPERATURE to R.string.unit_cat_temperature,
        UnitConverter.Category.VOLUME to R.string.unit_cat_volume,
        UnitConverter.Category.AREA to R.string.unit_cat_area,
        UnitConverter.Category.SPEED to R.string.unit_cat_speed,
        UnitConverter.Category.TIME to R.string.unit_cat_time,
        UnitConverter.Category.DATA to R.string.unit_cat_data,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = scaffold(R.string.tool_converter)
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(col)
        body.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val cats = UnitConverter.Category.values().toList()
        category = Spinner(this).apply {
            adapter = adapter(cats.map { getString(categoryNames.getValue(it)) })
            contentDescription = getString(R.string.unit_category)
            minimumHeight = dp(48)
        }
        col.addView(text(getString(R.string.unit_category), 14f, MUTED))
        col.addView(category, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        col.addView(gap())

        input = field(getString(R.string.unit_value_hint)).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }
        fromUnit = Spinner(this).apply { contentDescription = getString(R.string.unit_from) }
        toUnit = Spinner(this).apply { contentDescription = getString(R.string.unit_to) }
        col.addView(text(getString(R.string.unit_from), 14f, MUTED))
        col.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(fromUnit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        col.addView(gap(8))
        col.addView(button(getString(R.string.unit_swap), getString(R.string.unit_swap)) { swap() })
        col.addView(gap(8))
        col.addView(text(getString(R.string.unit_to), 14f, MUTED))
        result = text("", 28f, TEXT, bold = true).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setTextIsSelectable(true)
            minimumHeight = dp(48)
            setBackgroundColor(PANEL)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            contentDescription = null
        }
        col.addView(result, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(toUnit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        status = text("", 14f, DANGER)
        col.addView(status)
        col.addView(gap(8))
        col.addView(button(getString(R.string.btn_copy_result), null) {
            val r = result.text.toString()
            if (r.isNotBlank()) copyToClipboard(r)
        })

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        current = try { UnitConverter.Category.valueOf(prefs.getString("category", "LENGTH") ?: "LENGTH") } catch (_: Exception) { UnitConverter.Category.LENGTH }
        category.setSelection(cats.indexOf(current))
        bindUnits(prefs.getInt("from_" + current.name, 0), prefs.getInt("to_" + current.name, 1))

        category.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val c = cats[pos]
                if (c != current) { current = c; bindUnits(prefs.getInt("from_" + c.name, 0), prefs.getInt("to_" + c.name, 1)) }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        val again = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (!binding) {
                    prefs.edit().putString("category", current.name).putInt("from_" + current.name, fromUnit.selectedItemPosition)
                        .putInt("to_" + current.name, toUnit.selectedItemPosition).apply()
                    update()
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        fromUnit.onItemSelectedListener = again
        toUnit.onItemSelectedListener = again
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { update() }
        })
        update()
    }

    private fun adapter(items: List<String>) = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)

    private fun bindUnits(from: Int, to: Int) {
        binding = true
        val symbols = UnitConverter.units.getValue(current).map { it.symbol }
        fromUnit.adapter = adapter(symbols); toUnit.adapter = adapter(symbols)
        fromUnit.setSelection(from.coerceIn(0, symbols.lastIndex)); toUnit.setSelection(to.coerceIn(0, symbols.lastIndex))
        binding = false
        update()
    }

    private fun swap() {
        binding = true
        val a = fromUnit.selectedItemPosition; val b = toUnit.selectedItemPosition
        fromUnit.setSelection(b); toUnit.setSelection(a)
        binding = false
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("from_" + current.name, b).putInt("to_" + current.name, a).apply()
        update()
    }

    private fun update() {
        val raw = input.text.toString()
        if (raw.isBlank()) { result.text = ""; status.text = ""; return }
        val value = NumberInput.parse(raw)
        if (value == null) { result.text = ""; status.text = getString(R.string.unit_enter_number); return }
        status.text = ""
        val out = UnitConverter.convert(current, value, fromUnit.selectedItemPosition, toUnit.selectedItemPosition)
        result.text = UnitConverter.format(out)
        result.contentDescription = result.text.toString() + " " + (toUnit.selectedItem?.toString() ?: "")
    }

    companion object { private const val PREFS = "yeehaw_tools_converter" }
}
