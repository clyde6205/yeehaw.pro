package co.yehaw.browser.download

import android.content.Context
import android.content.Intent
import android.webkit.URLUtil
import androidx.core.content.ContextCompat
import co.yehaw.browser.data.DownloadDao
import co.yehaw.browser.data.DownloadEntity
import co.yehaw.browser.data.DownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DownloadRepository(
    private val context: Context,
    private val dao: DownloadDao,
    private val scope: CoroutineScope,
) {
    fun observeAll(): Flow<List<DownloadEntity>> = dao.observeAll()

    /** Returns the file name that will be used. Must be called from a user-initiated flow (foreground). */
    fun enqueue(url: String, userAgent: String?, referer: String?, cookie: String?, contentDisposition: String?, mime: String?): String {
        val name = sanitize(URLUtil.guessFileName(url, contentDisposition, mime))
        scope.launch(Dispatchers.IO) {
            dao.insert(
                DownloadEntity(
                    url = url, userAgent = userAgent, referer = referer, cookie = cookie, fileName = name,
                    mimeType = mime?.takeIf { it.isNotBlank() }, totalBytes = 0, receivedBytes = 0,
                    state = DownloadState.QUEUED, etag = null, lastModified = null,
                    createdAt = System.currentTimeMillis(), savedUri = null, error = null,
                )
            )
            withContext(Dispatchers.Main) { DownloadService.start(context) }
        }
        return name
    }

    fun pause(id: Long) = control(DownloadService.ACTION_PAUSE, id) { dao.updateState(id, DownloadState.PAUSED_USER) }
    fun resume(id: Long) = control(DownloadService.ACTION_RESUME, id) { dao.updateState(id, DownloadState.QUEUED) }

    fun remove(id: Long, partFileDir: File?) = control(DownloadService.ACTION_CANCEL, id) {
        partFileDir?.let { File(it, "$id.part").delete() }
        dao.delete(id)
    }

    fun clearFinished() {
        scope.launch(Dispatchers.IO) { dao.deleteFinished() }
    }

    /** Called when the UI comes to the foreground: restart the service if there is unfinished work. */
    fun resumePending() {
        scope.launch(Dispatchers.IO) {
            val pending = dao.getByStates(listOf(DownloadState.QUEUED, DownloadState.PAUSED_NETWORK, DownloadState.RUNNING))
            if (pending.isNotEmpty()) withContext(Dispatchers.Main) { DownloadService.start(context) }
        }
    }

    /** Routes through the service when it is alive (it owns the running jobs); otherwise updates the DB directly. */
    private fun control(action: String, id: Long, offline: suspend () -> Unit) {
        if (DownloadService.isRunning) {
            ContextCompat.startForegroundService(
                context, Intent(context, DownloadService::class.java).setAction(action).putExtra(DownloadService.EXTRA_ID, id)
            )
        } else {
            scope.launch(Dispatchers.IO) {
                offline()
                if (action == DownloadService.ACTION_RESUME) withContext(Dispatchers.Main) { DownloadService.start(context) }
            }
        }
    }

    companion object {
        fun sanitize(raw: String): String {
            val cleaned = raw.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trim('.').take(120)
            return cleaned.ifBlank { "download" }
        }
    }
}
