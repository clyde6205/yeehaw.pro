package co.yehaw.browser

import co.yehaw.browser.net.BlocklistIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer

class BlocklistIndexTest {
    private fun build(vararg domains: String): BlocklistIndex {
        val hashes = domains.map { BlocklistIndex.fnv1a64(it) }.sorted()
        val buf = ByteBuffer.allocate(8 + hashes.size * 8)
        buf.putInt(BlocklistIndex.MAGIC).putInt(hashes.size)
        hashes.forEach { buf.putLong(it) }
        return BlocklistIndex.load(ByteArrayInputStream(buf.array()))
    }

    @Test fun fnvMatchesReferenceVectors() {
        // Reference FNV-1a 64: "" -> 0xcbf29ce484222325, "a" -> 0xaf63dc4c8601ec8c
        assertEquals(0xcbf29ce484222325uL.toLong(), BlocklistIndex.fnv1a64(""))
        assertEquals(0xaf63dc4c8601ec8cuL.toLong(), BlocklistIndex.fnv1a64("a"))
    }
    @Test fun matchesExactAndSubdomains() {
        val idx = build("doubleclick.net", "ads.example.com")
        assertTrue(idx.matchesHostOrParent("doubleclick.net"))
        assertTrue(idx.matchesHostOrParent("stats.g.DoubleClick.net"))
        assertTrue(idx.matchesHostOrParent("ads.example.com"))
        assertFalse(idx.matchesHostOrParent("example.com"))
        assertFalse(idx.matchesHostOrParent("notdoubleclick.net"))
        assertFalse(idx.matchesHostOrParent("net"))
    }
    @Test fun emptyIndexBlocksNothing() = assertFalse(BlocklistIndex.EMPTY.matchesHostOrParent("a.b.com"))
    @Test(expected = IllegalArgumentException::class) fun rejectsBadMagic() {
        BlocklistIndex.load(ByteArrayInputStream(ByteArray(16)))
    }
}
