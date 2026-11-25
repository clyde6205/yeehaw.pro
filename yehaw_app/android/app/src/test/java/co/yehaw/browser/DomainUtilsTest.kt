package co.yehaw.browser

import co.yehaw.browser.util.DomainUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainUtilsTest {
    @Test fun registrableDomainHandlesPhSuffixes() {
        assertEquals("gcash.com", DomainUtils.registrableDomain("m.gcash.com"))
        assertEquals("pagasa.gov.ph", DomainUtils.registrableDomain("bagong.pagasa.gov.ph"))
        assertEquals("lazada.com.ph", DomainUtils.registrableDomain("www.lazada.com.ph"))
    }
    @Test fun thirdPartyDetection() {
        assertTrue(DomainUtils.isThirdParty("ads.tracker.net", "shopee.ph"))
        assertFalse(DomainUtils.isThirdParty("cf.shopee.ph", "shopee.ph"))
    }
    @Test fun protectedHosts() {
        assertTrue(DomainUtils.isProtectedHost("www.gcash.com"))
        assertTrue(DomainUtils.isProtectedHost("online.bdo.com.ph"))
        assertFalse(DomainUtils.isProtectedHost("notgcash.com"))
        assertFalse(DomainUtils.isProtectedHost(null))
    }
}
