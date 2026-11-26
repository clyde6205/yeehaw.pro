package app.yehaw.browser

import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.max
import kotlin.math.min

/**
 * Screen-reader readiness without needing TalkBack installed: for every visible control, the accessibility node the
 * framework hands to TalkBack must be clickable, focusable, and carry a spoken label; images need descriptions; text
 * contrast against its solid background is measured (hard floor 3:1, 4.5:1 is logged). This checks what TalkBack would
 * read, in English and in an RTL language. It is NOT a substitute for a human TalkBack session (see KNOWN_ISSUES).
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun before() { LocaleHelper.save(ctx, LocaleHelper.SYSTEM); BrowserStore(ctx).clearSession() }
    @After fun after() { LocaleHelper.save(ctx, LocaleHelper.SYSTEM); BrowserStore(ctx).clearSession() }

    private fun allViews(root: View): List<View> {
        val out = mutableListOf<View>()
        fun walk(v: View) { out.add(v); if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
        walk(root); return out
    }

    private fun lum(c: Int): Double {
        fun ch(x: Int): Double { val s = x / 255.0; return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch(android.graphics.Color.red(c)) + 0.7152 * ch(android.graphics.Color.green(c)) + 0.0722 * ch(android.graphics.Color.blue(c))
    }
    private fun contrast(a: Int, b: Int): Double { val l1 = lum(a); val l2 = lum(b); return (max(l1, l2) + 0.05) / (min(l1, l2) + 0.05) }
    private fun solidBackground(v: View): Int? {
        var p: View? = v
        while (p != null) { (p.background as? ColorDrawable)?.let { if (android.graphics.Color.alpha(it.color) == 255) return it.color }; p = p.parent as? View }
        return null
    }

    private fun audit(tag: String) {
        LocaleHelper.save(ctx, tag)
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val problems = mutableListOf<String>()
                for (v in allViews(a.window.decorView)) {
                    if (!v.isShown) continue
                    val info = v.createAccessibilityNodeInfo()
                    val spoken = (info.contentDescription ?: info.text)?.toString().orEmpty()
                    if (v.isClickable) {
                        if (!info.isClickable) problems += "$tag: node of ${v.javaClass.simpleName} not clickable"
                        if (spoken.isBlank()) problems += "$tag: clickable ${v.javaClass.simpleName} has no spoken label"
                        if (!info.isFocusable && !v.isFocusable) problems += "$tag: clickable '${spoken}' is not focusable (keyboard/switch access)"
                    }
                    if (v is ImageView && v.isClickable && v.contentDescription.isNullOrBlank()) problems += "$tag: image button without description"
                    if (v is EditText && spoken.isBlank() && v.hint.isNullOrBlank()) problems += "$tag: EditText without hint/label"
                    if (v is TextView && v !is EditText && !v.text.isNullOrBlank()) {
                        val bg = solidBackground(v)
                        if (bg != null) {
                            val ratio = contrast(v.currentTextColor, bg)
                            if (ratio < 4.5) Log.w("A11Y", "$tag contrast ${"%.2f".format(ratio)} for '${v.text.toString().take(30)}'")
                            if (ratio < 3.0) problems += "$tag: text '${v.text.toString().take(30)}' contrast ${"%.2f".format(ratio)} < 3:1"
                        }
                    }
                }
                assertTrue("accessibility problems:\n" + problems.distinct().joinToString("\n"), problems.isEmpty())
            }
        }
    }

    @Test fun controlsAreLabelledClickableAndFocusableInEnglish() = audit("en")
    @Test fun controlsAreLabelledClickableAndFocusableInArabicRtl() = audit("ar")
    @Test fun controlsAreLabelledClickableAndFocusableInHindi() = audit("hi")
}
