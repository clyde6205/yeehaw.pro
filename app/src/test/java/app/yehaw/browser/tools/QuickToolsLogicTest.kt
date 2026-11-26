package app.yehaw.browser.tools

import app.yehaw.browser.tools.Calculator.CalcException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class QuickToolsLogicTest {
    private fun calc(e: String) = Calculator.calculate(e)
    private fun calcError(e: String): CalcException.Kind {
        try { Calculator.evaluate(e) } catch (x: CalcException) { return x.kind }
        fail("expected an error for '$e'"); throw IllegalStateException()
    }

    // ---- calculator
    @Test fun basicArithmeticAndPrecedence() {
        assertEquals("14", calc("2+3×4"))
        assertEquals("20", calc("(2+3)×4"))
        assertEquals("2.5", calc("10÷4"))
        assertEquals("-3", calc("-3"))
        assertEquals("5", calc("--5".replace("--", "+")))
        assertEquals("7", calc("10-3"))
        assertEquals("6", calc("2*3"))
        assertEquals("3", calc("9/3"))
    }

    @Test fun decimalsAreExactNotBinaryFloat() {
        assertEquals("0.3", calc("0.1+0.2"))
        assertEquals("0.7", calc("1-0.3"))
        assertEquals("1.1", calc("3.3÷3"))
    }

    @Test fun percentBehavesLikeAPhoneCalculator() {
        assertEquals("0.5", calc("50%"))
        assertEquals("55", calc("50+10%"))
        assertEquals("45", calc("50-10%"))
        assertEquals("5", calc("50×10%"))
        assertEquals("500", calc("50÷10%"))
        assertEquals("0.005", calc("50%%"))
        assertEquals("1", calc("(100%)"))
    }

    @Test fun divisionByZeroAndSyntaxErrorsAreReportedNotCrashed() {
        assertEquals(CalcException.Kind.DIVIDE_BY_ZERO, calcError("5÷0"))
        assertEquals(CalcException.Kind.DIVIDE_BY_ZERO, calcError("5÷(2-2)"))
        for (bad in listOf("", "+", "2+", "(2", "2)", "2..3", ".", "abc", "2×÷3", "()")) {
            assertEquals("'$bad'", CalcException.Kind.SYNTAX, calcError(bad))
        }
    }

    @Test fun hugeNumbersAreRefusedNotOverflowed() {
        assertEquals(CalcException.Kind.TOO_LARGE, calcError("9".repeat(41)))
        assertEquals(CalcException.Kind.TOO_LARGE, calcError("1" + "0".repeat(30) + "×" + "1" + "0".repeat(35)))
    }

    @Test fun resultsAreRoundedForDisplay() {
        assertEquals("0.333333333333", calc("1÷3"))
        assertEquals("1234567.89", calc("1234567.89"))
        assertEquals("0", calc("0.0"))
        assertEquals("0", calc("5-5"))
    }

    // ---- unit converter
    private fun conv(c: UnitConverter.Category, v: Double, from: String, to: String): Double {
        val l = UnitConverter.units.getValue(c)
        return UnitConverter.convert(c, v, l.indexOfFirst { it.symbol == from }, l.indexOfFirst { it.symbol == to })
    }

    @Test fun lengthMassVolumeAreaSpeedTimeData() {
        assertEquals(1.609344, conv(UnitConverter.Category.LENGTH, 1.0, "mi", "km"), 1e-9)
        assertEquals(30.48, conv(UnitConverter.Category.LENGTH, 1.0, "ft", "cm"), 1e-9)
        assertEquals(2.2046226218, conv(UnitConverter.Category.MASS, 1.0, "kg", "lb"), 1e-6)
        assertEquals(3.785411784, conv(UnitConverter.Category.VOLUME, 1.0, "gal", "L"), 1e-9)
        assertEquals(10000.0, conv(UnitConverter.Category.AREA, 1.0, "ha", "m²"), 1e-9)
        assertEquals(27.7777777778, conv(UnitConverter.Category.SPEED, 100.0, "km/h", "m/s"), 1e-6)
        assertEquals(120.0, conv(UnitConverter.Category.TIME, 2.0, "h", "min"), 1e-9)
        assertEquals(1500.0, conv(UnitConverter.Category.DATA, 1.5, "KB", "B"), 1e-9)
    }

    @Test fun temperatureUsesOffsetsNotJustFactors() {
        assertEquals(212.0, conv(UnitConverter.Category.TEMPERATURE, 100.0, "°C", "°F"), 1e-9)
        assertEquals(0.0, conv(UnitConverter.Category.TEMPERATURE, 32.0, "°F", "°C"), 1e-9)
        assertEquals(273.15, conv(UnitConverter.Category.TEMPERATURE, 0.0, "°C", "K"), 1e-9)
        assertEquals(-40.0, conv(UnitConverter.Category.TEMPERATURE, -40.0, "°F", "°C"), 1e-9)
    }

    @Test fun roundTripIsStableForEveryLinearUnit() {
        for ((cat, list) in UnitConverter.units) {
            if (cat == UnitConverter.Category.TEMPERATURE) continue
            for (a in list.indices) for (b in list.indices) {
                val there = UnitConverter.convert(cat, 123.456, a, b)
                val back = UnitConverter.convert(cat, there, b, a)
                assertEquals("$cat ${list[a].symbol}<->${list[b].symbol}", 123.456, back, 123.456 * 1e-9)
            }
        }
    }

    @Test fun formattingIsReadable() {
        assertEquals("0", UnitConverter.format(0.0))
        assertEquals("1.609344", UnitConverter.format(1.609344))
        assertEquals("0.3", UnitConverter.format(0.1 + 0.2))
        assertEquals("—", UnitConverter.format(Double.NaN))
        assertTrue(UnitConverter.format(1e20).contains("e"))
    }

    @Test fun numberInputAcceptsRealWorldTyping() {
        assertEquals(12.5, NumberInput.parse("12.5")!!, 0.0)
        assertEquals(12.5, NumberInput.parse("12,5")!!, 0.0)
        assertEquals(-3.0, NumberInput.parse("-3")!!, 0.0)
        assertEquals(1234.5, NumberInput.parse("١٢٣٤٫٥")!!, 0.0)   // Arabic-Indic digits and decimal separator
        assertEquals(42.0, NumberInput.parse("۴۲")!!, 0.0)          // Persian / Urdu digits
        assertNull(NumberInput.parse(""))
        assertNull(NumberInput.parse("-"))
        assertNull(NumberInput.parse("1.2.3"))
        assertNull(NumberInput.parse("abc"))
        assertNull(NumberInput.parse("1e999"))
    }

    // ---- QR content safety
    @Test fun webLinksAreOfferedOnlyAfterClassification() {
        val r = QrContent.classify("https://example.org/path?x=1")
        assertEquals(QrContent.Kind.WEB_LINK, r.kind); assertEquals("example.org", r.host); assertTrue(r.warnings.isEmpty())
        assertTrue(QrContent.classify("http://example.org").warnings.contains(QrContent.Warning.NOT_ENCRYPTED))
    }

    @Test fun dangerousSchemesAreNeverWebLinks() {
        for (t in listOf("javascript:alert(1)", "JAVASCRIPT:alert(1)", "data:text/html,<script>1</script>", "file:///sdcard/x",
            "content://x/y", "intent://x#Intent;scheme=http;end", "market://details?id=x", "vbscript:x", "ftp://x/y")) {
            assertEquals(t, QrContent.Kind.UNSAFE_LINK, QrContent.classify(t).kind)
        }
        assertEquals(QrContent.Kind.UNSAFE_LINK, QrContent.classify("https://exa mple.org").kind)
        assertEquals(QrContent.Kind.UNSAFE_LINK, QrContent.classify("https://").kind)
    }

    @Test fun trickyLinksCarryWarnings() {
        val login = QrContent.classify("https://paypal.com@evil.example/login")
        assertEquals("evil.example", login.host); assertTrue(login.warnings.contains(QrContent.Warning.HIDDEN_LOGIN_PART))
        assertTrue(QrContent.classify("https://xn--pypal-4ve.com/").warnings.contains(QrContent.Warning.LOOKALIKE_HOST))
        assertTrue(QrContent.classify("https://pаypal.com/").warnings.contains(QrContent.Warning.LOOKALIKE_HOST))   // Cyrillic 'а'
        assertTrue(QrContent.classify("http://192.168.0.1/admin").warnings.contains(QrContent.Warning.ADDRESS_IS_NUMBERS))
    }

    @Test fun otherContentIsPlainText() {
        for (t in listOf("hello world", "tel:+639171234567", "mailto:a@b.c", "WIFI:S:net;T:WPA;P:pw;;", "BEGIN:VCARD", "12345")) {
            assertEquals(t, QrContent.Kind.TEXT, QrContent.classify(t).kind)
        }
    }
}

class QrRoundTripTest {
    private fun roundTrip(text: String, scale: Int = 6): String? {
        val qr = app.yehaw.browser.console.QrCode.encodeText(text)
        val side = qr.pixelSide(scale)
        return QrDecoder.decodeArgb(qr.toArgbPixels(scale), side, side)
    }

    @org.junit.Test fun generatedCodesAreReadBackExactly() {
        for (t in listOf("hello", "https://yeehaw.pro/get/", "https://example.org/a?b=1&c=2#frag", "Ünïcödé — 你好 — مرحبا — नमस्ते",
            "WIFI:S:home;T:WPA;P:secret;;", "x".repeat(150))) {
            org.junit.Assert.assertEquals(t, t, roundTrip(t))
        }
    }

    @org.junit.Test fun blankImageIsNotAQrCode() {
        val px = IntArray(200 * 200) { -1 }
        org.junit.Assert.assertNull(QrDecoder.decodeArgb(px, 200, 200))
    }

    @org.junit.Test fun yuvFrameWithACodeInTheCentreIsRead() {
        val qr = app.yehaw.browser.console.QrCode.encodeText("https://yeehaw.pro/get/")
        val scale = 8
        val side = qr.pixelSide(scale)
        val w = 640; val h = 480
        val y = ByteArray(w * h * 3 / 2) { 0x80.toByte() }            // grey background, NV21 layout
        for (i in 0 until w * h) y[i] = 0xFF.toByte()                    // white luminance plane
        val px = qr.toArgbPixels(scale)
        val ox = (w - side) / 2; val oy = (h - side) / 2
        for (yy in 0 until side) for (xx in 0 until side) if (px[yy * side + xx] == -0x1000000) y[(oy + yy) * w + ox + xx] = 0
        org.junit.Assert.assertEquals("https://yeehaw.pro/get/", QrDecoder.decodeYuv(y, w, h))
    }
}
