package co.yehaw.browser

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import co.yehaw.browser.browser.ContainerManager
import co.yehaw.browser.browser.TabManager
import co.yehaw.browser.data.AppDatabase
import co.yehaw.browser.data.SettingsStore
import co.yehaw.browser.download.DownloadRepository
import co.yehaw.browser.archive.PageArchiver
import co.yehaw.browser.memory.MemoryLifecycleManager
import co.yehaw.browser.memory.TabSuspensionManager
import co.yehaw.browser.net.AdBlockInterceptor
import co.yehaw.browser.net.DataSaverClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Hand-rolled service locator: tiny, no DI framework in the APK. */
class AppGraph(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val db: AppDatabase by lazy { AppDatabase.create(app) }
    val settings = SettingsStore(app)
    val adBlock = AdBlockInterceptor(app, settings)
    val dataSaver = DataSaverClient(app, settings)
    val containers by lazy { ContainerManager(app, db.containerDao()) }
    val suspension by lazy { TabSuspensionManager(app, db.tabStateDao(), scope) }
    val tabs by lazy { TabManager(app, settings, containers, adBlock, dataSaver, suspension, scope) }
    val memory by lazy { MemoryLifecycleManager(app, tabs, settings, scope) }
    val archiver by lazy { PageArchiver(app, db.archiveDao(), scope) }
    val downloads by lazy { DownloadRepository(app, db.downloadDao(), scope) }
}

class YeeHawApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        createNotificationChannels()
        graph.adBlock.loadAsync(graph.scope)
        graph.memory.start()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DOWNLOADS, getString(R.string.notif_channel_downloads), NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    companion object {
        const val CHANNEL_DOWNLOADS = "downloads"
    }
}
