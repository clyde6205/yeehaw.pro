package co.yehaw.browser.net

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import co.yehaw.browser.data.SettingsStore
import co.yehaw.browser.util.DomainUtils
import co.yehaw.browser.util.UrlUtils
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.brotli.BrotliInterceptor
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Optional image compression through a user/operator-configured edge proxy.
 *
 * Contract with the proxy (see docs/DATA_SAVER_PROXY.md): GET <template with {url},{q}>, sends
 * `Via: YeeHaw-Compressor`, `Save-Data: on`, `Accept: image/avif,image/webp,*` and Brotli/Gzip
 * encoding; the proxy answers with a re-encoded image/*. Only subresource images are routed.
 * Documents, scripts, XHR, cookies and Referer never go through the proxy, and financial hosts are excluded.
 * Any failure falls through to a normal direct load.
 */
class DataSaverClient(context: Context, private val settings: SettingsStore) {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cache(Cache(File(context.cacheDir, "datasaver"), 20L * 1024 * 1024))
            .addInterceptor(BrotliInterceptor)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    fun tryServe(request: WebResourceRequest, pageUrl: String?): WebResourceResponse? {
        if (!settings.dataSaverEnabled) return null
        val template = settings.imageProxyTemplate
        if (!template.startsWith("https://") || !template.contains("{url}")) return null
        if (request.isForMainFrame || !request.method.equals("GET", true)) return null
        val url = request.url.toString()
        if (!UrlUtils.isHttp(url) || url.length > 2000) return null
        val accept = request.requestHeaders["Accept"] ?: return null
        if (!accept.contains("image/")) return null
        if (DomainUtils.isProtectedHost(UrlUtils.host(url)) || DomainUtils.isProtectedHost(UrlUtils.host(pageUrl))) return null
        val proxyHost = UrlUtils.host(template.replace("{url}", "x").replace("{q}", "0"))
        if (proxyHost != null && proxyHost == UrlUtils.host(url)) return null

        val target = template
            .replace("{url}", URLEncoder.encode(url, "UTF-8"))
            .replace("{q}", QUALITY.toString())
        return try {
            val req = Request.Builder().url(target)
                .header("Via", "1.1 YeeHaw-Compressor")
                .header("Save-Data", "on")
                .header("Accept", "image/avif,image/webp,image/*;q=0.8")
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body
            val type = resp.header("Content-Type")?.substringBefore(';')?.trim()
            if (!resp.isSuccessful || body == null || type == null || !type.startsWith("image/")) {
                resp.close(); return null
            }
            val headers = mutableMapOf("Cache-Control" to (resp.header("Cache-Control") ?: "max-age=86400"))
            WebResourceResponse(type, null, resp.code, resp.message.ifBlank { "OK" }, headers, body.byteStream())
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val QUALITY = 55
    }
}
