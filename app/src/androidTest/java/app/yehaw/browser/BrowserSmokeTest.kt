package app.yehaw.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserSmokeTest {
    @Test
    fun browserStorePersistsBookmarksAndOnlyWebHistory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = BrowserStore(context)
        val uniqueUrl = "https://example.com/yehaw-test-" + System.currentTimeMillis()
        try {
            store.addBookmark(uniqueUrl, "Yehaw test bookmark")
            store.addHistory("file:///private/test", "Must not be recorded")
            store.addHistory(uniqueUrl, "Yehaw test history")
            assertTrue(store.bookmarks().any { it.url == uniqueUrl })
            assertTrue(store.history().any { it.url == uniqueUrl })
            assertTrue(store.history().none { it.url.startsWith("file:") })
        } finally {
            store.clearBookmarks()
            store.clearHistory()
            store.close()
        }
    }

    @Test
    fun browserLaunchesWithCoreControlsVisible() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("GO")).check(matches(isDisplayed()))
            onView(withText("Bookmarks")).check(matches(isDisplayed()))
            onView(withText("History")).check(matches(isDisplayed()))
            onView(withText("More")).check(matches(isDisplayed()))
        }
    }
}
