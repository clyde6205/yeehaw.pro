package app.yehaw.browser

import app.yehaw.browser.console.DeviceStatusManager
import app.yehaw.browser.console.ProEntitlement
import app.yehaw.browser.core.ClipboardWatcher
import app.yehaw.browser.core.DataSaverPolicy
import app.yehaw.browser.core.QuickPasteStore
import app.yehaw.browser.core.SignalLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    private val day = 24L * 60 * 60 * 1000

    @Test fun proCappedAtSevenDayGrace() {
        assertEquals(1000 + 7 * day, ProEntitlement.validUntil(1000, 1000 + 30 * day))
    }

    @Test fun proUsesEarlierExpiry() {
        assertEquals(1000 + 2 * day, ProEntitlement.validUntil(1000, 1000 + 2 * day))
    }

    @Test fun proExpiredFallsBackToGraceNotForever() {
        assertEquals(5000 + 7 * day, ProEntitlement.validUntil(5000, 100))
        assertEquals(5000 + 7 * day, ProEntitlement.validUntil(5000, null))
    }

    @Test fun updateUrlAllowList() {
        assertEquals("https://yeehaw.pro/get/", DeviceStatusManager.safeUpdateUrl("https://yeehaw.pro/get/"))
        assertNull(DeviceStatusManager.safeUpdateUrl("http://yeehaw.pro/get/"))
        assertNull(DeviceStatusManager.safeUpdateUrl("https://evil.example/x"))
        assertNull(DeviceStatusManager.safeUpdateUrl("https://yeehaw.pro.evil.example/x"))
        assertNull(DeviceStatusManager.safeUpdateUrl("javascript:alert(1)"))
        assertNull(DeviceStatusManager.safeUpdateUrl("not a url"))
    }

    @Test fun clipboardLinkDetection() {
        assertTrue(ClipboardWatcher.isLink("https://example.com/a"))
        assertFalse(ClipboardWatcher.isLink("hello world"))
        assertFalse(ClipboardWatcher.isLink("https://a b.com"))
        assertFalse(ClipboardWatcher.isLink("ftp://example.com/file"))
        assertFalse(ClipboardWatcher.isLink("http://"))
    }

    @Test fun quickPasteScriptEscapesInput() {
        val s = QuickPasteStore.insertScript("a\"b</script>\n'x")
        assertTrue(s.contains("\\\""))
        assertFalse(s.contains("\n'x"))
    }

    @Test fun dataSaverRules() {
        assertFalse(DataSaverPolicy.shouldBlockHeavy(false, SignalLevel.OFFLINE, true))
        assertTrue(DataSaverPolicy.shouldBlockHeavy(true, SignalLevel.POOR, false))
        assertTrue(DataSaverPolicy.shouldBlockHeavy(true, SignalLevel.GOOD, true))
        assertFalse(DataSaverPolicy.shouldBlockHeavy(true, SignalLevel.GOOD, false))
    }
}

class PerformancePolicyTest {
    @Test fun liveTabsShrinkOnLowMemory() {
        assertEquals(2, BrowserPerformancePolicy.maxLiveTabs(true, 256))
        assertEquals(2, BrowserPerformancePolicy.maxLiveTabs(false, 128))
        assertEquals(3, BrowserPerformancePolicy.maxLiveTabs(false, 192))
        assertEquals(4, BrowserPerformancePolicy.maxLiveTabs(false, 256))
    }

    @Test fun totalTabsExceedLiveTabs() {
        assertTrue(BrowserPerformancePolicy.MAX_TOTAL_TABS > BrowserPerformancePolicy.maxLiveTabs(false, 512))
    }

    @Test fun lowMemoryDevicesDefaultToMemorySaver() {
        assertTrue(BrowserPerformancePolicy.defaultMemorySaver(true, 512))
        assertTrue(BrowserPerformancePolicy.defaultMemorySaver(false, 128))
        assertFalse(BrowserPerformancePolicy.defaultMemorySaver(false, 256))
    }
}
