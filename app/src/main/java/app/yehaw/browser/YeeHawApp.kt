package app.yehaw.browser

import android.app.Application
import app.yehaw.browser.core.Guardian

/** Installs crash recovery in every process (main and isolated private browser). */
class YeeHawApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Guardian.install(this)
        if (android.os.Build.VERSION.SDK_INT < 28 || Application.getProcessName() == packageName) Guardian.startWatchdog(this)
    }
}
