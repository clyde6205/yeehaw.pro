package app.yehaw.browser.tools

import android.app.Activity
import android.content.Intent

/**
 * Single switch and entry point for the optional Quick Tools. Setting [ENABLED] to false hides the entry in the browser and
 * leaves browsing untouched; the tool Activities are separate classes that nothing in the browser references otherwise.
 */
object QuickTools {
    const val ENABLED = true
    const val EXTRA_CURRENT_URL = "current_url"

    fun open(from: Activity, currentUrl: String?) {
        if (!ENABLED) return
        from.startActivity(Intent(from, QuickToolsActivity::class.java).putExtra(EXTRA_CURRENT_URL, currentUrl))
    }
}
