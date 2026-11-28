package app.yehaw.browser

import app.yehaw.browser.core.TrackerPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerPolicyTest {
    @Test fun blocksAnalyticsAndAdHostsOnAnyPage() {
        assertTrue(TrackerPolicy.shouldBlock("news.example", "www.google-analytics.com", "/collect", false))
        assertTrue(TrackerPolicy.shouldBlock("www.facebook.com", "securepubads.g.doubleclick.net", "/gpt", false))
        assertTrue(TrackerPolicy.shouldBlock("www.facebook.com", "pixel.facebook.com", "/x", false))
    }
    @Test fun blocksMetaPixelAndSdkOnlyOffFacebook() {
        assertTrue(TrackerPolicy.shouldBlock("shop.example", "connect.facebook.net", "/en_US/fbevents.js", false))
        assertTrue(TrackerPolicy.shouldBlock("shop.example", "www.facebook.com", "/tr/", false))
        assertFalse(TrackerPolicy.shouldBlock("www.facebook.com", "connect.facebook.net", "/en_US/sdk.js", false))
        assertFalse(TrackerPolicy.shouldBlock("m.facebook.com", "www.facebook.com", "/tr", false))
    }
    @Test fun neverBlocksMainFrameOrTheSitesOwnContent() {
        assertFalse(TrackerPolicy.shouldBlock("x.example", "www.google-analytics.com", "/", true))
        assertFalse(TrackerPolicy.shouldBlock("m.facebook.com", "scontent.xx.fbcdn.net", "/v/t1/a.jpg", false))
        assertFalse(TrackerPolicy.shouldBlock("m.facebook.com", "static.xx.fbcdn.net", "/rsrc.php/a.js", false))
        assertFalse(TrackerPolicy.shouldBlock("www.google-analytics.com", "www.google-analytics.com", "/", false))
        assertFalse(TrackerPolicy.shouldBlock(null, null, null, false))
    }
    @Test fun feedSmoothingAppliesToFacebookFamilyOnly() {
        assertTrue(TrackerPolicy.isFeedSite("m.facebook.com"))
        assertTrue(TrackerPolicy.isFeedSite("www.instagram.com"))
        assertFalse(TrackerPolicy.isFeedSite("example.com"))
        assertFalse(TrackerPolicy.isFeedSite("connect.facebook.net"))
        assertFalse(TrackerPolicy.isFeedSite(null))
    }
}
