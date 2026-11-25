package co.yehaw.browser.archive

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import co.yehaw.browser.browser.Tab
import co.yehaw.browser.browser.TabKind
import co.yehaw.browser.data.ArchiveDao
import co.yehaw.browser.data.ArchiveEntity
import co.yehaw.browser.util.UrlUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Saves the current page as one self-contained .mhtml file for offline reading. */
class PageArchiver(
    private val context: Context,
    private val dao: ArchiveDao,
    private val scope: CoroutineScope,
) {
    enum class Result { SAVED, NO_SPACE, FAILED, UNSUPPORTED }

    val dir: File = File(context.filesDir, "archives").apply { mkdirs() }

    /** Main thread (WebView.saveWebArchive requirement). [onDone] is invoked on the main thread. */
    fun save(tab: Tab, onDone: (Result) -> Unit) {
        val wv = tab.webView
        if (wv == null || tab.kind != TabKind.WEB || !UrlUtils.isHttp(wv.url)) return onDone(Result.UNSUPPORTED)
        if (dir.usableSpace < MIN_FREE_BYTES) return onDone(Result.NO_SPACE)
        val url = wv.url ?: tab.url
        val title = (wv.title ?: tab.title).ifBlank { UrlUtils.displayHost(url) }
        val file = File(dir, "${System.currentTimeMillis()}.mhtml")
        wv.saveWebArchive(file.absolutePath, false) { saved ->
            if (saved == null) {
                file.delete()
                onDone(Result.FAILED)
            } else {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        dao.insert(ArchiveEntity(title = title, url = url, filePath = saved, sizeBytes = File(saved).length(), savedAt = System.currentTimeMillis()))
                        prune()
                    }
                    onDone(Result.SAVED)
                }
            }
        }
    }

    suspend fun list(): List<ArchiveEntity> = withContext(Dispatchers.IO) { dao.getAll() }
    suspend fun latest(n: Int): List<ArchiveEntity> = withContext(Dispatchers.IO) { dao.latest(n) }

    suspend fun delete(entity: ArchiveEntity) = withContext(Dispatchers.IO) {
        File(entity.filePath).delete()
        dao.delete(entity.id)
    }

    fun fileUrl(entity: ArchiveEntity): String = Uri.fromFile(File(entity.filePath)).toString()

    fun shareUri(entity: ArchiveEntity): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", File(entity.filePath))

    /** Keep the archive folder under [MAX_TOTAL_BYTES], oldest first. */
    private suspend fun prune() {
        var total = 0L
        val all = dao.getAll() // newest first
        val keep = all.filter { e ->
            val f = File(e.filePath)
            if (!f.exists()) { dao.delete(e.id); false } else { total += f.length(); total <= MAX_TOTAL_BYTES }
        }
        all.filter { it !in keep }.forEach { e -> File(e.filePath).delete(); dao.delete(e.id) }
    }

    companion object {
        private const val MIN_FREE_BYTES = 50L * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 200L * 1024 * 1024
    }
}
