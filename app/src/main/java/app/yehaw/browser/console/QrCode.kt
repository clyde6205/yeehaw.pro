package app.yehaw.browser.console

/**
 * Small, dependency-free QR Code generator (ISO/IEC 18004) for share links.
 * Byte mode, error correction level M (15% damage tolerant), versions 1-10 (up to 213 bytes).
 * Works fully offline and allocates only a few KB, so it is safe on 1-2 GB phones.
 */
class QrCode private constructor(val version: Int, private val modules: BooleanArray) {
    val size: Int = 17 + 4 * version

    fun isDark(x: Int, y: Int): Boolean = x in 0 until size && y in 0 until size && modules[y * size + x]

    /** ARGB pixels (dark = black, light = white) with a [border]-module quiet zone; each module is [scale] pixels. */
    fun toArgbPixels(scale: Int, border: Int = 4): IntArray {
        require(scale >= 1 && border >= 0)
        val side = (size + border * 2) * scale
        val out = IntArray(side * side)
        for (py in 0 until side) {
            val my = py / scale - border
            for (px in 0 until side) {
                val mx = px / scale - border
                out[py * side + px] = if (isDark(mx, my)) -0x1000000 else -0x1
            }
        }
        return out
    }

    fun pixelSide(scale: Int, border: Int = 4): Int = (size + border * 2) * scale

    companion object {
        // Error-correction level M: [ecCodewordsPerBlock, blocksGroup1, dataPerBlockGroup1, blocksGroup2, dataPerBlockGroup2]
        private val TABLE_M = arrayOf(
            intArrayOf(10, 1, 16, 0, 0), intArrayOf(16, 1, 28, 0, 0), intArrayOf(26, 1, 44, 0, 0), intArrayOf(18, 2, 32, 0, 0),
            intArrayOf(24, 2, 43, 0, 0), intArrayOf(16, 4, 27, 0, 0), intArrayOf(18, 4, 31, 0, 0), intArrayOf(22, 2, 38, 2, 39),
            intArrayOf(22, 3, 36, 2, 37), intArrayOf(26, 4, 43, 1, 44),
        )
        private val ALIGN = arrayOf(
            intArrayOf(), intArrayOf(6, 18), intArrayOf(6, 22), intArrayOf(6, 26), intArrayOf(6, 30), intArrayOf(6, 34),
            intArrayOf(6, 22, 38), intArrayOf(6, 24, 42), intArrayOf(6, 26, 46), intArrayOf(6, 28, 50),
        )
        const val MAX_VERSION = 10

        private fun dataCodewords(v: Int): Int { val t = TABLE_M[v - 1]; return t[1] * t[2] + t[3] * t[4] }

        /** Largest payload in bytes that fits version [v]. */
        fun capacityBytes(v: Int): Int {
            val ccBits = if (v <= 9) 8 else 16
            return (dataCodewords(v) * 8 - 4 - ccBits) / 8
        }

        fun encodeText(text: String): QrCode = encodeBytes(text.toByteArray(Charsets.UTF_8))

        fun encodeBytes(data: ByteArray): QrCode {
            var version = 1
            while (version <= MAX_VERSION && capacityBytes(version) < data.size) version++
            require(version <= MAX_VERSION) { "Text too long for a share QR (max ${capacityBytes(MAX_VERSION)} bytes)" }

            // 1) bit stream: mode (0100), length, bytes, terminator, padding
            val bits = ArrayList<Int>()
            fun put(value: Int, len: Int) { for (i in len - 1 downTo 0) bits.add((value ushr i) and 1) }
            put(0b0100, 4)
            put(data.size, if (version <= 9) 8 else 16)
            for (b in data) put(b.toInt() and 0xFF, 8)
            val capBits = dataCodewords(version) * 8
            put(0, minOf(4, capBits - bits.size))
            while (bits.size % 8 != 0) bits.add(0)
            var pad = 0xEC
            while (bits.size < capBits) { put(pad, 8); pad = pad xor 0xEC xor 0x11 }
            val dataCw = IntArray(bits.size / 8) { i -> var v = 0; for (k in 0 until 8) v = (v shl 1) or bits[i * 8 + k]; v }

            // 2) split into blocks, Reed-Solomon, interleave
            val t = TABLE_M[version - 1]
            val ecLen = t[0]
            val blocks = ArrayList<IntArray>()
            var pos = 0
            repeat(t[1]) { blocks.add(dataCw.copyOfRange(pos, pos + t[2])); pos += t[2] }
            repeat(t[3]) { blocks.add(dataCw.copyOfRange(pos, pos + t[4])); pos += t[4] }
            val gen = rsGenerator(ecLen)
            val ecBlocks = blocks.map { rsRemainder(it, gen) }
            val all = ArrayList<Int>()
            val maxData = blocks.maxOf { it.size }
            for (i in 0 until maxData) for (b in blocks) if (i < b.size) all.add(b[i])
            for (i in 0 until ecLen) for (e in ecBlocks) all.add(e[i])
            val codewords = all.toIntArray()

            // 3) matrix
            val m = Matrix(version)
            m.drawFunctionPatterns()
            m.drawCodewords(codewords)
            var best = 0
            var bestPenalty = Int.MAX_VALUE
            for (mask in 0 until 8) {
                m.applyMask(mask)
                m.drawFormatBits(mask)
                val p = m.penalty()
                if (p < bestPenalty) { bestPenalty = p; best = mask }
                m.applyMask(mask) // undo
            }
            m.applyMask(best)
            m.drawFormatBits(best)
            return QrCode(version, m.modules)
        }

        // ---- Reed-Solomon over GF(256), polynomial 0x11D ----
        private fun mul(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z
        }

        private fun rsGenerator(degree: Int): IntArray {
            val result = IntArray(degree)
            result[degree - 1] = 1
            var root = 1
            repeat(degree) {
                for (j in result.indices) {
                    result[j] = mul(result[j], root)
                    if (j + 1 < result.size) result[j] = result[j] xor result[j + 1]
                }
                root = mul(root, 0x02)
            }
            return result
        }

        private fun rsRemainder(data: IntArray, gen: IntArray): IntArray {
            val result = IntArray(gen.size)
            for (b in data) {
                val factor = b xor result[0]
                System.arraycopy(result, 1, result, 0, result.size - 1)
                result[result.size - 1] = 0
                for (i in result.indices) result[i] = result[i] xor mul(gen[i], factor)
            }
            return result
        }
    }

    private class Matrix(val version: Int) {
        val size = 17 + 4 * version
        val modules = BooleanArray(size * size)
        private val isFunction = BooleanArray(size * size)

        private fun setFn(x: Int, y: Int, dark: Boolean) { modules[y * size + x] = dark; isFunction[y * size + x] = true }
        private fun bit(v: Int, i: Int) = ((v ushr i) and 1) != 0

        fun drawFunctionPatterns() {
            for (i in 0 until size) { setFn(6, i, i % 2 == 0); setFn(i, 6, i % 2 == 0) }
            finder(3, 3); finder(size - 4, 3); finder(3, size - 4)
            val a = ALIGN[version - 1]
            for (i in a.indices) for (j in a.indices) {
                if ((i == 0 && j == 0) || (i == 0 && j == a.size - 1) || (i == a.size - 1 && j == 0)) continue
                alignment(a[i], a[j])
            }
            drawFormatBits(0) // reserve the area
            drawVersion()
        }

        private fun finder(cx: Int, cy: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val d = maxOf(Math.abs(dx), Math.abs(dy))
                val x = cx + dx; val y = cy + dy
                if (x in 0 until size && y in 0 until size) setFn(x, y, d != 2 && d != 4)
            }
        }

        private fun alignment(cx: Int, cy: Int) {
            for (dy in -2..2) for (dx in -2..2) setFn(cx + dx, cy + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
        }

        fun drawFormatBits(mask: Int) {
            val data = mask // error-correction level M has format bits 00
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            val bits = ((data shl 10) or rem) xor 0x5412
            for (i in 0..5) setFn(8, i, bit(bits, i))
            setFn(8, 7, bit(bits, 6)); setFn(8, 8, bit(bits, 7)); setFn(7, 8, bit(bits, 8))
            for (i in 9..14) setFn(14 - i, 8, bit(bits, i))
            for (i in 0..7) setFn(size - 1 - i, 8, bit(bits, i))
            for (i in 8..14) setFn(8, size - 15 + i, bit(bits, i))
            setFn(8, size - 8, true)
        }

        private fun drawVersion() {
            if (version < 7) return
            var rem = version
            repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
            val bits = (version shl 12) or rem
            for (i in 0 until 18) {
                val b = bit(bits, i)
                val a = size - 11 + i % 3
                val c = i / 3
                setFn(a, c, b); setFn(c, a, b)
            }
        }

        fun drawCodewords(data: IntArray) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) {
                    for (j in 0..1) {
                        val x = right - j
                        val upward = ((right + 1) and 2) == 0
                        val y = if (upward) size - 1 - vert else vert
                        if (!isFunction[y * size + x] && i < data.size * 8) {
                            modules[y * size + x] = bit(data[i ushr 3], 7 - (i and 7))
                            i++
                        }
                    }
                }
                right -= 2
            }
        }

        fun applyMask(mask: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (!isFunction[y * size + x] && invert) modules[y * size + x] = !modules[y * size + x]
            }
        }

        /** Standard four penalty rules; only influences which mask is chosen, never validity. */
        fun penalty(): Int {
            var result = 0
            fun g(x: Int, y: Int) = modules[y * size + x]
            for (vertical in 0..1) {
                for (a in 0 until size) {
                    var run = 1
                    val line = BooleanArray(size) { b -> if (vertical == 0) g(b, a) else g(a, b) }
                    for (b in 1 until size) {
                        if (line[b] == line[b - 1]) { run++; if (run == 5) result += 3 else if (run > 5) result++ } else run = 1
                    }
                    val s = StringBuilder(size)
                    line.forEach { s.append(if (it) '1' else '0') }
                    val str = s.toString()
                    for (p in arrayOf("10111010000", "00001011101")) {
                        var from = 0
                        while (true) { val k = str.indexOf(p, from); if (k < 0) break; result += 40; from = k + 1 }
                    }
                }
            }
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = g(x, y)
                if (c == g(x + 1, y) && c == g(x, y + 1) && c == g(x + 1, y + 1)) result += 3
            }
            var dark = 0
            for (v in modules) if (v) dark++
            val total = size * size
            result += ((Math.abs(dark * 20 - total * 10) + total - 1) / total - 1) * 10
            return result
        }
    }
}
