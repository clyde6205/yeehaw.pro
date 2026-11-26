package app.yehaw.browser

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Language support: every shipped language launches the real browser, applies the right layout direction (RTL for
 * Arabic and Urdu; Hebrew and Persian are RTL with English fallback because no translation ships for them yet),
 * keeps every control at a >= 48dp touch target with a label, and the choice survives recreation and a fresh launch.
 */
@RunWith(AndroidJUnit4::class)
class LocalizationTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val rtlTags = setOf("ar", "ur", "he", "fa")
    private val english = "This language is a machine translation and has not yet been checked by a native speaker. Some text may be wrong."

    @Before fun before() { LocaleHelper.save(ctx, LocaleHelper.SYSTEM); BrowserStore(ctx).clearSession() }
    @After fun after() { LocaleHelper.save(ctx, LocaleHelper.SYSTEM); BrowserStore(ctx).clearSession() }

    // Android still reports some languages by their pre-1989 ISO codes.
    private fun legacy(l: String) = when (l) { "he" -> "iw"; "id" -> "in"; "yi" -> "ji"; else -> l }

    private fun allViews(root: View): List<View> {
        val out = mutableListOf<View>()
        fun walk(v: View) { out.add(v); if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
        walk(root); return out
    }

    private fun checkLanguage(tag: String, translated: Boolean) {
        LocaleHelper.save(ctx, tag)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                val lang = tag.substringBefore('-')
                val expectRtl = tag in rtlTags
                assertEquals("$tag layout direction", expectRtl, LocaleHelper.isRtl(a))
                val notice = a.getString(R.string.toast_machine_translation_notice)
                if (translated) assertNotEquals("$tag must not fall back to English", english, notice)
                else assertEquals("$tag has no translation yet and must fall back to English", english, notice)
                val d = a.resources.displayMetrics.density
                val buttons = allViews(a.window.decorView).filterIsInstance<Button>()
                assertTrue("$tag: browser must show its buttons", buttons.size >= 5)
                val home = a.getString(R.string.label_home)
                assertTrue("$tag: translated Home button '$home' present", buttons.any { it.text.toString() == home })
                for (b in buttons) {
                    val label = (b.contentDescription ?: b.text)?.toString().orEmpty()
                    assertTrue("$tag: button '${b.text}' needs a label", label.isNotBlank())
                    assertTrue("$tag: '$label' touch target must be >= 48dp",
                        b.minimumWidth >= (48 * d - 1) && b.minimumHeight >= (48 * d - 1))
                }
                @Suppress("DEPRECATION")
                val applied = if (android.os.Build.VERSION.SDK_INT >= 24) a.resources.configuration.locales[0].language else a.resources.configuration.locale.language
                assertTrue("$lang locale applied (was $applied)", legacy(applied) == legacy(lang))
            }
        }
    }

    @Test fun everyShippedLanguageLaunchesWithCorrectDirectionAndTouchTargets() {
        for ((tag, _) in LocaleHelper.SUPPORTED) checkLanguage(tag, translated = tag != "en")
    }

    @Test fun hebrewAndPersianAreRtlWithEnglishFallbackAndDoNotBreakTheUi() {
        checkLanguage("he", translated = false)
        checkLanguage("fa", translated = false)
    }

    @Test fun languageChoicePersistsAcrossRecreateAndAFreshLaunch() {
        LocaleHelper.save(ctx, "es")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.recreate()
            scenario.onActivity { a -> assertEquals("Inicio", a.getString(R.string.label_home)) }
        }
        assertEquals("es", LocaleHelper.saved(ctx))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a -> assertEquals("Inicio", a.getString(R.string.label_home)) }
        }
    }

    @Test fun anUnknownSavedLanguageFallsBackToEnglishWithoutCrashing() {
        LocaleHelper.save(ctx, "xx-ZZ")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a -> assertEquals("Home", a.getString(R.string.label_home)) }
        }
    }

    @Test fun everyLanguageStaysUsableAtLargeSystemFont() {
        org.junit.Assume.assumeTrue("system font_scale reaches running apps on API 24+", android.os.Build.VERSION.SDK_INT >= 24)
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun sh(c: String) { android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(c)).use { it.readBytes() } }
        try {
            sh("settings put system font_scale 1.5")
            for (i in 0 until 100) {
                if (android.provider.Settings.System.getFloat(ctx.contentResolver, "font_scale", 1f) >= 1.45f) break
                Thread.sleep(100)
            }
            Thread.sleep(2000)
            for (tag in listOf("en", "hi", "zh-CN", "es", "ar", "bn", "pt", "ru", "ur", "id", "fil", "he", "fa")) {
                LocaleHelper.save(ctx, tag)
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { a ->
                        val edit = allViews(a.window.decorView).filterIsInstance<android.widget.EditText>().first()
                        assertTrue("$tag: address bar must have a real size at 1.5x font", edit.width > 0 && edit.height > 0)
                        val go = allViews(a.window.decorView).first { it is android.widget.TextView && it.text?.toString() == a.getString(R.string.label_go) }
                        assertTrue("$tag: GO must be clickable at 1.5x font", go.isShown && go.performClick())
                    }
                    Thread.sleep(500)
                    scenario.onActivity { a -> assertTrue("$tag: activity must survive", !(a.isFinishing || a.isDestroyed)) }
                }
            }
        } finally { sh("settings put system font_scale 1.0") }
    }
}
