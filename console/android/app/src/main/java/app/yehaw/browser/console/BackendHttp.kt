package app.yehaw.browser.console

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Tiny HTTPS client on one low-priority daemon thread. No third-party dependency, never blocks the UI thread,
 * and never follows redirects or speaks cleartext.
 */
internal object BackendHttp {
    class Response(val code: Int, val body: ByteArray, private val headers: Map<String, String>) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "yeehaw-console").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    fun async(task: () -> Unit) {
        executor.execute { try { task() } catch (_: Throwable) { /* best effort */ } }
    }

    fun schedule(delayMs: Long, task: () -> Unit) {
        executor.schedule({ try { task() } catch (_: Throwable) { } }, delayMs, TimeUnit.MILLISECONDS)
    }

    private const val MAX_BODY = 512 * 1024

    private fun open(path: String): HttpURLConnection? {
        val url = URL(BackendConfig.API_BASE_URL + path)
        if (url.protocol != "https") return null
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 10_000
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "YeeHaw-Android")
        }
    }

    /** Blocking; call from [async]/[schedule]. Returns null on any network failure. */
    fun get(path: String, headers: Map<String, String> = emptyMap()): Response? {
        val c = open(path) ?: return null
        return try {
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val out = ByteArrayOutputStream()
            if (stream != null && code != 304) {
                stream.use { s ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = s.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > MAX_BODY) return null
                    }
                }
            }
            val h = HashMap<String, String>()
            c.headerFields.forEach { (k, v) -> if (k != null && v.isNotEmpty()) h[k.lowercase()] = v[0] }
            Response(code, out.toByteArray(), h)
        } catch (_: Throwable) {
            null
        } finally {
            c.disconnect()
        }
    }

    /** Blocking; returns the HTTP status or -1 on failure. */
    fun postJson(path: String, json: String): Int {
        val c = open(path) ?: return -1
        return try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val bytes = json.toByteArray(Charsets.UTF_8)
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            val code = c.responseCode
            (if (code in 200..299) c.inputStream else c.errorStream)?.close()
            code
        } catch (_: Throwable) {
            -1
        } finally {
            c.disconnect()
        }
    }
}
