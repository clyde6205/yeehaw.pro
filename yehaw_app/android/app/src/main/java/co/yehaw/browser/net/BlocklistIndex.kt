package co.yehaw.browser.net

import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Compact domain blocklist: sorted LongArray of FNV-1a-64 hashes (see tools/compile_blocklist.py).
 * ~8 bytes per domain, zero String objects, O(log n) lookups incl. parent-domain walk.
 */
class BlocklistIndex(private val hashes: LongArray) {
    val size: Int get() = hashes.size

    /** True if [host] or any parent domain (a.b.c -> b.c -> c.tld) is listed. */
    fun matchesHostOrParent(host: String): Boolean {
        if (hashes.isEmpty()) return false
        var h = host.lowercase().trimEnd('.')
        while (true) {
            if (h.indexOf('.') < 0) return false          // never match bare TLDs
            if (java.util.Arrays.binarySearch(hashes, fnv1a64(h)) >= 0) return true
            val dot = h.indexOf('.')
            h = h.substring(dot + 1)
        }
    }

    companion object {
        const val MAGIC = 0x59484231
        val EMPTY = BlocklistIndex(LongArray(0))

        fun fnv1a64(s: String): Long {
            var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
            for (b in s.toByteArray(Charsets.UTF_8)) {
                h = h xor (b.toLong() and 0xff)
                h *= 0x100000001b3L
            }
            return h
        }

        fun load(input: InputStream): BlocklistIndex {
            DataInputStream(input.buffered()).use { din ->
                require(din.readInt() == MAGIC) { "Bad blocklist magic" }
                val count = din.readInt()
                require(count in 0..5_000_000) { "Bad blocklist size" }
                val bytes = ByteArray(count * 8)
                din.readFully(bytes)
                val arr = LongArray(count)
                ByteBuffer.wrap(bytes).asLongBuffer().get(arr)
                return BlocklistIndex(arr)
            }
        }
    }
}
