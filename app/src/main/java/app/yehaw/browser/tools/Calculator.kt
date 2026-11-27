package app.yehaw.browser.tools

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Calculator engine: recursive-descent parser over BigDecimal, so 0.1 + 0.2 is exactly 0.3.
 * Grammar: expr = term (('+'|'-') term)* ; term = unary (('×'|'÷'|'*'|'/') unary)* ; unary = '-' unary | postfix ;
 * postfix = primary '%'* ; primary = number | '(' expr ')'.
 * Percent: "n%" is n/100; after + or -, "a + b%" means a + (a * b/100) like a phone calculator ("50 + 10%" = 55).
 */
object Calculator {
    class CalcException(val kind: Kind) : Exception(kind.name) {
        enum class Kind { SYNTAX, DIVIDE_BY_ZERO, TOO_LARGE }
    }

    private val MC = MathContext(34, RoundingMode.HALF_EVEN)
    private val HUNDRED = BigDecimal(100)
    private val LIMIT = BigDecimal("1E+60")

    private class Value(val number: BigDecimal, val percent: Boolean)

    fun evaluate(expression: String): BigDecimal = Parser(expression).parse()

    /** Plain, human-readable form: at most 12 significant digits, no exponent, no trailing zeros. */
    fun format(value: BigDecimal): String {
        val rounded = value.round(MathContext(12, RoundingMode.HALF_EVEN))
        val stripped = if (rounded.signum() == 0) BigDecimal.ZERO else rounded.stripTrailingZeros()
        return stripped.toPlainString()
    }

    /** Evaluates and formats; throws [CalcException] for syntax errors, division by zero and absurd magnitudes. */
    fun calculate(expression: String): String = format(evaluate(expression))

    private class Parser(source: String) {
        private val s = source.replace(" ", "").replace('−', '-').replace('–', '-')
        private var i = 0

        fun parse(): BigDecimal {
            if (s.isEmpty()) throw CalcException(CalcException.Kind.SYNTAX)
            val v = expr()
            if (i != s.length) throw CalcException(CalcException.Kind.SYNTAX)
            return finish(plain(v))
        }

        /** A trailing percent with nothing to be relative to is just n/100. */
        private fun plain(v: Value): BigDecimal = if (v.percent) v.number.divide(HUNDRED, MC) else v.number

        private fun finish(n: BigDecimal): BigDecimal {
            if (n.abs() > LIMIT) throw CalcException(CalcException.Kind.TOO_LARGE)
            return n
        }

        private fun peek(): Char? = if (i < s.length) s[i] else null

        private fun expr(): Value {
            var left = term()
            while (true) {
                val c = peek()
                if (c != '+' && c != '-') break
                i++
                val right = term()
                val r = if (right.percent) left.number.multiply(right.number, MC).divide(HUNDRED, MC) else right.number
                left = Value(finish(if (c == '+') left.number.add(r, MC) else left.number.subtract(r, MC)), false)
            }
            return left
        }

        private fun term(): Value {
            var left = unary()
            while (true) {
                val c = peek()
                if (c != '×' && c != '÷' && c != '*' && c != '/') break
                i++
                val right = unary()
                val l = if (left.percent) left.number.divide(HUNDRED, MC) else left.number
                val r = if (right.percent) right.number.divide(HUNDRED, MC) else right.number
                left = if (c == '×' || c == '*') {
                    Value(finish(l.multiply(r, MC)), false)
                } else {
                    if (r.signum() == 0) throw CalcException(CalcException.Kind.DIVIDE_BY_ZERO)
                    Value(finish(l.divide(r, MC)), false)
                }
            }
            return left
        }

        private fun unary(): Value {
            if (peek() == '-') { i++; val v = unary(); return Value(v.number.negate(), v.percent) }
            if (peek() == '+') { i++; return unary() }
            return postfix()
        }

        private fun postfix(): Value {
            var v = primary()
            while (peek() == '%') { i++; v = if (v.percent) Value(v.number.divide(HUNDRED, MC), true) else Value(v.number, true) }
            return v
        }

        private fun primary(): Value {
            val c = peek() ?: throw CalcException(CalcException.Kind.SYNTAX)
            if (c == '(') {
                i++
                val inner = expr()
                if (peek() != ')') throw CalcException(CalcException.Kind.SYNTAX)
                i++
                return Value(plain(inner), false)
            }
            if (c.isDigit() || c == '.') return Value(number(), false)
            throw CalcException(CalcException.Kind.SYNTAX)
        }

        private fun number(): BigDecimal {
            val start = i
            var dots = 0
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) { if (s[i] == '.') dots++; i++ }
            val text = s.substring(start, i)
            if (dots > 1 || text == ".") throw CalcException(CalcException.Kind.SYNTAX)
            if (text.length > 40) throw CalcException(CalcException.Kind.TOO_LARGE)
            return BigDecimal(text)
        }
    }
}
