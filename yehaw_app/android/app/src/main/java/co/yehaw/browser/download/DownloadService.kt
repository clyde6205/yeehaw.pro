package co.yehaw.browser.download

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import co.yehaw.browser.MainActivity
import co.yehaw.browser.R
import co.yehaw.browser.YeeHawApp
import co.yehaw.browser.data.DownloadDao
import co.yehaw.browser.data.DownloadEntity
import co.yehaw.browser.data.DownloadState
import co.yehaw.browser.util.NetworkUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.brotli.BrotliInterceptor
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Foreground download service. Resumable via HTTP Range (+ If-Range validators), survives cellular drops by
 * parking downloads as PAUSED_NETWORK and auto-resuming when a network returns. At most [MAX_CONCURRENT]
 * transfers run at once to stay kind to 2 GB devices and prepaid data plans.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val progress = ConcurrentHashMap<Long, LongArray>() // id -> [received, total]
    private val permits = Semaphore(MAX_CONCURRENT)
    private lateinit var dao: DownloadDao
    private lateinit var client: OkHttpClient
    private lateinit var cm: ConnectivityManager
    private var idleStop: Job? = null
    private var lastNotify = 0L
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch { schedule() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        dao = (application as YeeHawApp).graph.db.downloadDao()
        client = OkHttpClient.Builder()
            .addInterceptor(BrotliInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        cm = getSystemService(ConnectivityManager::class.java)
        ServiceCompat.startForeground(this, FG_ID, buildForegroundNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        runCatching { cm.registerDefaultNetworkCallback(netCallback) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra(EXTRA_ID, -1L) ?: -1L
        scope.launch {
            when (intent?.action) {
                ACTION_PAUSE -> if (id >= 0) { dao.updateState(id, DownloadState.PAUSED_USER); jobs.remove(id)?.cancel(); progress.remove(id) }
                ACTION_PAUSE_ALL -> pauseAll()
                ACTION_RESUME -> if (id >= 0) dao.updateState(id, DownloadState.QUEUED)
                ACTION_CANCEL -> if (id >= 0) { jobs.remove(id)?.cancel(); progress.remove(id); File(partDir(), "$id.part").delete(); dao.delete(id) }
                else -> if (jobs.isEmpty()) dao.requeueStaleRunning()
            }
            schedule()
        }
        return START_NOT_STICKY
    }

    /** Android 15 caps dataSync foreground services; park everything and let the app resume on next open. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        scope.launch {
            jobs.values.forEach { it.cancel() }
            jobs.keys.forEach { dao.updateState(it, DownloadState.PAUSED_NETWORK) }
            jobs.clear()
            stopSelf()
        }
    }

    override fun onDestroy() {
        isRunning = false
        runCatching { cm.unregisterNetworkCallback(netCallback) }
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ scheduling

    private suspend fun schedule() {
        val online = NetworkUtil.isOnline(this)
        val wanted = buildList {
            add(DownloadState.QUEUED)
            if (online) add(DownloadState.PAUSED_NETWORK)
        }
        for (d in dao.getByStates(wanted)) {
            if (jobs.containsKey(d.id)) continue
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    permits.withPermit { runDownload(d.id) }
                } finally {
                    jobs.remove(d.id)
                    progress.remove(d.id)
                    afterJob()
                }
            }
            jobs[d.id] = job
            job.start()
        }
        afterJob()
    }

    private suspend fun afterJob() {
        refreshForeground(force = true)
        val waiting = dao.getByStates(listOf(DownloadState.QUEUED, DownloadState.PAUSED_NETWORK)).isNotEmpty()
        if (jobs.isEmpty() && !waiting) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else if (jobs.isEmpty()) {
            // Only network-parked work left: stay up briefly so we can auto-resume, then release the service.
            idleStop?.cancel()
            idleStop = scope.launch {
                delay(IDLE_STOP_MS)
                if (jobs.isEmpty()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            }
        }
    }

    private suspend fun pauseAll() {
        val active = jobs.keys.toList()
        active.forEach { dao.updateState(it, DownloadState.PAUSED_USER); jobs.remove(it)?.cancel() }
        dao.getByStates(listOf(DownloadState.QUEUED)).forEach { dao.updateState(it.id, DownloadState.PAUSED_USER) }
    }

    // ------------------------------------------------------------------ transfer

    private suspend fun runDownload(id: Long) {
        var attempt = 0
        while (true) {
            val row = dao.get(id) ?: return
            if (row.state != DownloadState.QUEUED && row.state != DownloadState.PAUSED_NETWORK && row.state != DownloadState.RUNNING) return
            dao.updateState(id, DownloadState.RUNNING)
            try {
                when (transfer(row)) {
                    Outcome.DONE, Outcome.FAILED -> return
                    Outcome.RESTART -> { attempt = 0; continue }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (!NetworkUtil.isOnline(this)) { dao.updateState(id, DownloadState.PAUSED_NETWORK, "Waiting for network"); return }
                attempt++
                dao.updateRetry(id, attempt)
                if (attempt > MAX_RETRIES) { dao.updateState(id, DownloadState.PAUSED_NETWORK, e.message?.take(120)); return }
                delay(minOf(30_000L, 1000L shl attempt))
            } catch (e: Exception) {
                fail(id, e.message ?: "Error")
                return
            }
        }
    }

    private enum class Outcome { DONE, FAILED, RESTART }

    private suspend fun transfer(row: DownloadEntity): Outcome {
        val part = File(partDir(), "${row.id}.part")
        var offset = if (part.exists()) part.length() else 0L

        val req = Request.Builder().url(row.url).apply {
            row.userAgent?.let { header("User-Agent", it) }
            row.referer?.let { header("Referer", it) }
            row.cookie?.let { header("Cookie", it) }
            if (offset > 0) {
                header("Range", "bytes=$offset-")
                (row.etag ?: row.lastModified)?.let { header("If-Range", it) }
            }
        }.build()

        val call = client.newCall(req)
        val completion = currentCoroutineContext()[Job]!!.invokeOnCompletion { if (it != null) call.cancel() }
        return try {
            call.execute().use { resp ->
                val body = resp.body
                when {
                    resp.code == 416 -> {
                        if (row.totalBytes > 0 && offset == row.totalBytes) {
                            finish(row, part); return@use Outcome.DONE
                        }
                        part.delete(); dao.resetForRestart(row.id); return@use Outcome.RESTART
                    }
                    resp.code != 200 && resp.code != 206 -> { fail(row.id, "HTTP ${resp.code}"); return@use Outcome.FAILED }
                }
                if (resp.code == 200) { offset = 0 } // server ignored Range (or validators changed): start over
                val total = when {
                    resp.code == 206 -> resp.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                        ?: body.contentLength().let { if (it > 0) it + offset else 0L }
                    else -> body.contentLength().coerceAtLeast(0L)
                }
                dao.updateValidators(row.id, resp.header("ETag"), resp.header("Last-Modified"), resp.header("Content-Type")?.substringBefore(';'))
                if (total > 0 && getExternalFilesDir(null)?.usableSpace?.let { it < total - offset + SPACE_MARGIN } == true) {
                    fail(row.id, "Not enough storage"); return@use Outcome.FAILED
                }

                var received = offset
                RandomAccessFile(part, "rw").use { raf ->
                    if (resp.code == 200) raf.setLength(0)
                    raf.seek(offset)
                    val buf = ByteArray(32 * 1024)
                    var lastDb = 0L
                    body.byteStream().use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            raf.write(buf, 0, n)
                            received += n
                            progress[row.id] = longArrayOf(received, total)
                            val now = System.currentTimeMillis()
                            if (now - lastDb >= 700) {
                                dao.updateProgress(row.id, received, total)
                                refreshForeground(force = false)
                                lastDb = now
                            }
                        }
                    }
                    raf.fd.sync()
                }
                if (total > 0 && received != total) throw IOException("Incomplete: $received of $total")
                dao.updateProgress(row.id, received, if (total > 0) total else received)
                finish(row, part)
                return@use Outcome.DONE
            }
        } finally {
            completion.dispose()
        }
    }

    private suspend fun finish(row: DownloadEntity, part: File) {
        val name = row.fileName
        val mime = row.mimeType?.takeIf { it != "application/octet-stream" }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/YeeHaw")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)?.use { out -> part.inputStream().use { it.copyTo(out, 64 * 1024) } }
                ?: throw IOException("Cannot open output")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        part.delete()
        dao.markCompleted(row.id, uri.toString())
        notifyDone(row.id, name, uri, mime)
    }

    private suspend fun fail(id: Long, message: String) {
        File(partDir(), "$id.part").delete()
        dao.wipeCookie(id)
        dao.updateState(id, DownloadState.FAILED, message)
    }

    // ------------------------------------------------------------------ notifications

    private fun partDir(): File = File(getExternalFilesDir(null) ?: filesDir, "downloads").apply { mkdirs() }

    private fun refreshForeground(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotify < 1000) return
        lastNotify = now
        runCatching { getSystemService(android.app.NotificationManager::class.java).notify(FG_ID, buildForegroundNotification()) }
    }

    private fun buildForegroundNotification(): Notification {
        var received = 0L
        var total = 0L
        progress.values.forEach { received += it[0]; total += it[1] }
        val pauseAll = PendingIntent.getService(
            this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_PAUSE_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_SHOW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, YeeHawApp.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_downloading))
            .setContentText(if (total > 0) "${received / 1024 / 1024} / ${total / 1024 / 1024} MB" else null)
            .setProgress(if (total > 0) 100 else 0, if (total > 0) (received * 100 / total).toInt() else 0, total <= 0 && jobs.isNotEmpty())
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .addAction(0, getString(R.string.notif_pause_all), pauseAll)
            .build()
    }

    private fun notifyDone(id: Long, name: String, uri: Uri, mime: String) {
        val view = PendingIntent.getActivity(
            this, id.toInt(),
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, YeeHawApp.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_download_done))
            .setContentText(name).setAutoCancel(true).setContentIntent(view).build()
        runCatching { NotificationManagerCompat.from(this).notify(DONE_ID_BASE + id.toInt(), n) }
    }

    companion object {
        const val ACTION_PROCESS = "co.yehaw.browser.download.PROCESS"
        const val ACTION_PAUSE = "co.yehaw.browser.download.PAUSE"
        const val ACTION_RESUME = "co.yehaw.browser.download.RESUME"
        const val ACTION_CANCEL = "co.yehaw.browser.download.CANCEL"
        const val ACTION_PAUSE_ALL = "co.yehaw.browser.download.PAUSE_ALL"
        const val EXTRA_ID = "download_id"
        private const val FG_ID = 4001
        private const val DONE_ID_BASE = 5000
        private const val MAX_CONCURRENT = 2
        private const val MAX_RETRIES = 5
        private const val SPACE_MARGIN = 20L * 1024 * 1024
        private const val IDLE_STOP_MS = 10 * 60 * 1000L

        @Volatile var isRunning = false

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, DownloadService::class.java).setAction(ACTION_PROCESS)
            )
        }
    }
}
