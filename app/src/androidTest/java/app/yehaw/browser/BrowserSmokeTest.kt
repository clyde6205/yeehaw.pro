package app.yehaw.browser

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
    fun browserStoreReplacesDuplicateBookmarksAndCapsHistory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = BrowserStore(context)
        val bookmarkUrl = "https://example.com/yehaw-bookmark-" + System.currentTimeMillis()
        try {
            store.addBookmark(bookmarkUrl, "Original title")
            store.addBookmark(bookmarkUrl, "Updated title")
            val matchingBookmarks = store.bookmarks().filter { it.url == bookmarkUrl }
            assertTrue(matchingBookmarks.size == 1)
            assertTrue(matchingBookmarks.single().title == "Updated title")

            repeat(1005) { index ->
                store.addHistory("https://example.com/yehaw-history/$index", "History $index")
            }
            val historyCount = store.readableDatabase.rawQuery("SELECT COUNT(*) FROM history", null).use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
            assertTrue("History must stay within the 1000-entry retention limit", historyCount <= 1000)
        } finally {
            store.clearBookmarks()
            store.clearHistory()
            store.close()
        }
    }

    @Test
    fun browserStorePersistsSessionTabsAndOfflineReadingCopies() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = BrowserStore(context)
        val firstUrl = "https://example.com/yehaw-session-" + System.currentTimeMillis()
        val secondUrl = "https://example.org/yehaw-session-" + System.currentTimeMillis()
        val offlineUrl = "https://example.net/yehaw-offline-" + System.currentTimeMillis()
        try {
            store.saveSession(
                listOf(
                    BrowserStore.SessionTab(firstUrl, "First tab", 480),
                    BrowserStore.SessionTab(secondUrl, "Second tab", 920)
                ),
                1
            )
            val session = store.loadSession()
            assertTrue(session != null)
            assertTrue(session!!.tabs.size == 2)
            assertTrue(session.activeIndex == 1)
            assertTrue(session.tabs[0].scrollY == 480)
            assertTrue(session.tabs[1].url == secondUrl)

            val offlineId = store.saveOfflinePage(offlineUrl, "Offline article", "Saved readable article text")
            val offline = store.offlinePage(offlineId)
            assertTrue(offline != null)
            assertTrue(offline!!.title == "Offline article")
            assertTrue(offline.content == "Saved readable article text")
            store.deleteOfflinePage(offlineId)
            assertTrue(store.offlinePage(offlineId) == null)
        } finally {
            store.clearSession()
            store.clearOfflinePages()
            store.close()
        }
    }

    @Test
    fun offlineReadingCopiesAreBoundedAndCanAllBeCleared() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = BrowserStore(context)
        val prefix = "https://example.com/yehaw-offline-limit-" + System.currentTimeMillis()
        try {
            repeat(105) { index ->
                store.saveOfflinePage("$prefix/$index", "Offline $index", "Readable text $index")
            }
            assertTrue(
                "Offline reading storage must stay within the 100-page retention limit",
                store.offlinePages().size <= BrowserStore.MAX_OFFLINE_PAGES
            )
            store.clearOfflinePages()
            assertTrue("Clear browsing data must remove saved offline pages", store.offlinePages().isEmpty())
        } finally {
            store.clearOfflinePages()
            store.close()
        }
    }

    @Test
    fun browserStoreRejectsWebUrlsWithoutHosts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = BrowserStore(context)
        try {
            store.addHistory("https://", "Malformed history")
            store.addBookmark("http://", "Malformed bookmark")
            assertTrue("Malformed URLs must not enter history", store.history().none { it.url == "https://" })
            assertTrue("Malformed URLs must not enter bookmarks", store.bookmarks().none { it.url == "http://" })

            var rejectedOfflineUrl = false
            try {
                store.saveOfflinePage("https://", "Malformed offline page", "Readable text")
            } catch (_: IllegalArgumentException) {
                rejectedOfflineUrl = true
            }
            assertTrue("Offline reading must reject web URLs without a host", rejectedOfflineUrl)
        } finally {
            store.clearHistory()
            store.clearBookmarks()
            store.clearOfflinePages()
            store.close()
        }
    }

    @Test
    fun browserLaunchesWithCoreControlsInViewHierarchy() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                assertTrue("Browser activity decor must be attached", decor.isAttachedToWindow)
                assertTrue("Browser content must be visible", decor.visibility == View.VISIBLE)
                val labels = mutableSetOf<String>()
                fun collect(view: View) {
                    if (view is TextView) labels.add(view.text?.toString().orEmpty())
                    if (view is ViewGroup) {
                        for (index in 0 until view.childCount) collect(view.getChildAt(index))
                    }
                }
                collect(decor)
                listOf("GO", "Bookmarks", "History", "More").forEach { label ->
                    assertTrue("Expected browser control '$label' in the launched view hierarchy", label in labels)
                }
            }
        }
    }

    @Test
    fun sessionStorageRejectsNonWebUrlsAndCapsRestoredTabs() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = BrowserStore(context)
        val prefix = "https://example.com/yehaw-session-cap-" + System.currentTimeMillis()
        try {
            store.saveSession(
                listOf(
                    BrowserStore.SessionTab("$prefix/0", "Tab 0", 0),
                    BrowserStore.SessionTab("file:///private/local", "Must reject", 0),
                    BrowserStore.SessionTab("$prefix/2", "Tab 2", 20),
                    BrowserStore.SessionTab("$prefix/3", "Tab 3", 30),
                    BrowserStore.SessionTab("$prefix/4", "Tab 4", 40),
                    BrowserStore.SessionTab("$prefix/5", "Tab 5", 50)
                ),
                2
            )
            val restored = store.loadSession()
            assertTrue("Four valid tabs should be retained even when an invalid URL appears before them", restored!!.tabs.size == 4)
            assertTrue("The active tab index must be remapped after invalid URLs are filtered", restored.activeIndex == 1)
            assertTrue("Local file URLs must never enter browser session storage", restored.tabs.none { it.url.startsWith("file:") })
        } finally {
            store.clearSession()
            store.close()
        }
    }

    @Test
    fun offlineReadingTextIsClippedToTheDocumentedUtf8ByteMaximum() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = BrowserStore(context)
        val url = "https://example.com/yehaw-offline-size-" + System.currentTimeMillis()
        try {
            val content = "🌎".repeat(BrowserStore.MAX_OFFLINE_TEXT_BYTES_PER_PAGE)
            val id = store.saveOfflinePage(url, "Large article", content)
            val saved = store.offlinePage(id)
            assertTrue(saved != null)
            assertTrue(
                "A saved offline page must never exceed the per-page UTF-8 byte budget",
                BrowserStore.utf8ByteCount(saved!!.content) <= BrowserStore.MAX_OFFLINE_TEXT_BYTES_PER_PAGE
            )
            assertTrue("UTF-8 truncation must not split surrogate pairs", !saved.content.contains("\uFFFD"))
        } finally {
            store.clearOfflinePages()
            store.close()
        }
    }

    @Test
    fun offlineLibraryEnforcesAnEightMiBTotalStorageBudget() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = BrowserStore(context)
        val prefix = "https://example.com/yeehaw-offline-budget-" + System.currentTimeMillis()
        try {
            repeat(40) { index ->
                store.saveOfflinePage(
                    "$prefix/$index",
                    "Offline $index",
                    "x".repeat(BrowserStore.MAX_OFFLINE_TEXT_BYTES_PER_PAGE)
                )
            }
            assertTrue(
                "Offline text storage must stay within the 8 MiB library budget",
                store.offlineStorageBytes() <= BrowserStore.MAX_OFFLINE_TOTAL_BYTES
            )
            assertTrue(
                "The newest offline reading copy must be retained",
                store.offlinePages().any { it.url == "$prefix/39" }
            )
            assertTrue(
                "Oldest pages must be evicted when the storage budget is full",
                store.offlinePages().none { it.url == "$prefix/0" }
            )
        } finally {
            store.clearOfflinePages()
            store.close()
        }
    }

    @Test
    fun offlineReadingRejectsNonWebUrlsAndBlankText() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = BrowserStore(context)
        try {
            var rejectedFileUrl = false
            try {
                store.saveOfflinePage("file:///sdcard/private.html", "Local file", "text")
            } catch (_: IllegalArgumentException) {
                rejectedFileUrl = true
            }
            assertTrue("Offline library must accept only HTTP and HTTPS URLs", rejectedFileUrl)

            var rejectedBlankText = false
            try {
                store.saveOfflinePage("https://example.com/blank", "Blank", "   ")
            } catch (_: IllegalArgumentException) {
                rejectedBlankText = true
            }
            assertTrue("Offline library must reject empty reading copies", rejectedBlankText)
        } finally {
            store.clearOfflinePages()
            store.close()
        }
    }

    @Test
    fun offlineSavingsCounterUsesExactUtf8ByteCounts() {
        assertTrue("ASCII text must use its exact UTF-8 byte count", BrowserStore.utf8ByteCount("YeeHaw") == 6L)
        assertTrue("Multibyte text must count encoded bytes, not Kotlin characters", BrowserStore.utf8ByteCount("café") == 5L)
        assertTrue("Emoji must count their full UTF-8 encoding", BrowserStore.utf8ByteCount("🌎") == 4L)
    }


    @Test
    fun lowMemoryPolicyUsesConservativeDefaultsWithoutOverridingNormalDevices() {
        assertTrue(BrowserPerformancePolicy.defaultMemorySaver(true, 256))
        assertTrue(BrowserPerformancePolicy.defaultMemorySaver(false, 128))
        assertTrue(!BrowserPerformancePolicy.defaultMemorySaver(false, 256))
        assertTrue(BrowserPerformancePolicy.maxLiveTabs(true, 256) == 2)
        assertTrue(BrowserPerformancePolicy.maxLiveTabs(false, 128) == 2)
        assertTrue(BrowserPerformancePolicy.maxLiveTabs(false, 160) == 3)
        assertTrue(BrowserPerformancePolicy.maxLiveTabs(false, 256) == 4)
    }


}
