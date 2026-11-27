package app.yehaw.browser

import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Tiny loopback HTTP server for instrumented tests: serves one small HTML page so the browser leaves its start page. */
object LocalPage {
    fun serve(title: String = "Local Test Page"): ServerSocket {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val body = "<html><head><title>$title</title></head><body><p>test page</p></body></html>".toByteArray()
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) {
                    try {
                        s.getInputStream().read(ByteArray(4096))
                        s.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(body); flush()
                        }
                    } catch (_: Exception) {
                    } finally { try { s.close() } catch (_: Exception) {} }
                }
            }
        }
        return server
    }
}
