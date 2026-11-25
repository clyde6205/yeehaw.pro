package co.yehaw.browser

import co.yehaw.browser.util.UrlUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlUtilsTest {
    @Test fun addsHttpsToBareHost() = assertEquals("https://shopee.ph", UrlUtils.resolveInput("shopee.ph"))
    @Test fun keepsFullUrlUntouched() = assertEquals("http://example.com/a?b=1", UrlUtils.resolveInput("http://example.com/a?b=1"))
    @Test fun localhostAndIpUseHttp() {
        assertEquals("http://192.168.1.1:8080", UrlUtils.resolveInput("192.168.1.1:8080"))
        assertEquals("http://localhost:3000", UrlUtils.resolveInput("localhost:3000"))
    }
    @Test fun wordsBecomeSearch() =
        assertEquals(UrlUtils.DEFAULT_SEARCH + "bagyo+ngayon", UrlUtils.resolveInput("bagyo ngayon"))
    @Test fun singleWordBecomesSearch() = assertTrue(UrlUtils.resolveInput("pagasa").startsWith(UrlUtils.DEFAULT_SEARCH))
    @Test fun dangerousSchemesBecomeSearch() {
        assertTrue(UrlUtils.resolveInput("javascript:alert(1)").startsWith(UrlUtils.DEFAULT_SEARCH))
        assertTrue(UrlUtils.resolveInput("content://x/y").startsWith(UrlUtils.DEFAULT_SEARCH))
    }
    @Test fun blankIsEmpty() = assertEquals("", UrlUtils.resolveInput("   "))
    @Test fun displayHostStripsWww() = assertEquals("inquirer.net", UrlUtils.displayHost("https://www.inquirer.net/x"))
    @Test fun httpsDetection() {
        assertTrue(UrlUtils.isHttps("HTTPS://a.b"))
        assertFalse(UrlUtils.isHttps("http://a.b"))
        assertNull(UrlUtils.host("not a url"))
    }
}
