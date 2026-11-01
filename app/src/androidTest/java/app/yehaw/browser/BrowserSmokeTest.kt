package app.yehaw.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserSmokeTest {
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
