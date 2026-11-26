package app.yehaw.browser.tools

/**
 * Offline unit converter. Linear units convert through a base unit per category (factor = base units per 1 of the unit);
 * temperature is converted through Celsius. Data sizes are decimal (1 KB = 1000 B), as phone storage is sold.
 * Unit symbols are international and intentionally not translated; category names are (string resources).
 */
object UnitConverter {
    enum class Category { LENGTH, MASS, TEMPERATURE, VOLUME, AREA, SPEED, TIME, DATA }

    class Unit(val symbol: String, val toBase: Double)

    val units: Map<Category, List<Unit>> = mapOf(
        Category.LENGTH to listOf(
            Unit("mm", 0.001), Unit("cm", 0.01), Unit("m", 1.0), Unit("km", 1000.0),
            Unit("in", 0.0254), Unit("ft", 0.3048), Unit("yd", 0.9144), Unit("mi", 1609.344),
        ),
        Category.MASS to listOf(
            Unit("mg", 0.000001), Unit("g", 0.001), Unit("kg", 1.0), Unit("t", 1000.0),
            Unit("oz", 0.028349523125), Unit("lb", 0.45359237),
        ),
        Category.TEMPERATURE to listOf(Unit("°C", 1.0), Unit("°F", 1.0), Unit("K", 1.0)),
        Category.VOLUME to listOf(
            Unit("mL", 0.001), Unit("L", 1.0), Unit("m³", 1000.0),
            Unit("tsp", 0.00492892159375), Unit("tbsp", 0.01478676478125), Unit("fl oz", 0.0295735295625),
            Unit("cup", 0.2365882365), Unit("gal", 3.785411784),
        ),
        Category.AREA to listOf(
            Unit("cm²", 0.0001), Unit("m²", 1.0), Unit("ha", 10000.0), Unit("km²", 1_000_000.0),
            Unit("ft²", 0.09290304), Unit("acre", 4046.8564224),
        ),
        Category.SPEED to listOf(
            Unit("m/s", 1.0), Unit("km/h", 1.0 / 3.6), Unit("mph", 0.44704), Unit("kn", 1852.0 / 3600.0),
        ),
        Category.TIME to listOf(
            Unit("s", 1.0), Unit("min", 60.0), Unit("h", 3600.0), Unit("day", 86400.0), Unit("week", 604800.0),
        ),
        Category.DATA to listOf(
            Unit("B", 1.0), Unit("KB", 1e3), Unit("MB", 1e6), Unit("GB", 1e9), Unit("TB", 1e12),
        ),
    )

    fun convert(category: Category, value: Double, from: Int, to: Int): Double {
        val list = units.getValue(category)
        require(from in list.indices && to in list.indices)
        if (category == Category.TEMPERATURE) return fromCelsius(toCelsius(value, list[from].symbol), list[to].symbol)
        return value * list[from].toBase / list[to].toBase
    }

    private fun toCelsius(v: Double, symbol: String): Double = when (symbol) {
        "°F" -> (v - 32.0) * 5.0 / 9.0
        "K" -> v - 273.15
        else -> v
    }

    private fun fromCelsius(c: Double, symbol: String): Double = when (symbol) {
        "°F" -> c * 9.0 / 5.0 + 32.0
        "K" -> c + 273.15
        else -> c
    }

    /** At most 10 significant digits, plain notation unless the number is huge or tiny. */
    fun format(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "—"
        if (value == 0.0) return "0"
        val abs = Math.abs(value)
        val bd = java.math.BigDecimal(value).round(java.math.MathContext(10, java.math.RoundingMode.HALF_EVEN))
        return if (abs >= 1e15 || abs < 1e-9) {
            String.format(java.util.Locale.ROOT, "%.6e", value)
        } else {
            val s = bd.stripTrailingZeros().toPlainString()
            if (s == "-0") "0" else s
        }
    }
}

/** Parses what people actually type, including Arabic-Indic and Persian digits and comma or Arabic decimal separators. */
object NumberInput {
    fun parse(text: String): Double? {
        val sb = StringBuilder()
        for (ch in text.trim()) {
            when {
                ch in '0'..'9' || ch == '-' || ch == '+' || ch == '.' || ch == 'e' || ch == 'E' -> sb.append(ch)
                ch in '٠'..'٩' -> sb.append('0' + (ch - '٠'))
                ch in '۰'..'۹' -> sb.append('0' + (ch - '۰'))
                ch == ',' || ch == '٫' || ch == '٬' -> sb.append(if (ch == '٬') "" else ".")
                ch == '−' -> sb.append('-')
                ch == ' ' || ch == ' ' -> {}
                else -> return null
            }
        }
        val s = sb.toString()
        if (s.isEmpty() || s == "-" || s == "." || s == "+") return null
        if (s.count { it == '.' } > 1) return null
        return s.toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }
    }
}
