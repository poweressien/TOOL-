package com.toolkit.privacy

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/** Tiny static HTTP server bound to 127.0.0.1 so pages run on a real http://localhost origin. */
object LocalServer {
    @Volatile var root: File? = null
    @Volatile var port: Int = 0
    private var ss: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    @Synchronized
    fun start(dir: File): Int {
        root = dir
        val cur = ss
        if (cur != null && !cur.isClosed) return port
        var p = 8080
        var s: ServerSocket? = null
        while (s == null) {
            try {
                s = ServerSocket(p, 50, InetAddress.getByName("127.0.0.1"))
            } catch (e: Exception) {
                p++
                if (p > 8120) throw e
            }
        }
        ss = s
        port = p
        Thread {
            while (!s.isClosed) {
                try {
                    val c = s.accept()
                    pool.execute { handle(c) }
                } catch (e: Exception) {
                    break
                }
            }
        }.start()
        return p
    }

    private val mimes = mapOf(
        "html" to "text/html; charset=utf-8", "htm" to "text/html; charset=utf-8",
        "js" to "application/javascript; charset=utf-8", "mjs" to "application/javascript; charset=utf-8",
        "css" to "text/css; charset=utf-8", "json" to "application/json; charset=utf-8",
        "txt" to "text/plain; charset=utf-8", "svg" to "image/svg+xml", "png" to "image/png",
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif", "webp" to "image/webp",
        "ico" to "image/x-icon", "mp3" to "audio/mpeg", "wav" to "audio/wav", "ogg" to "audio/ogg",
        "mp4" to "video/mp4", "webm" to "video/webm", "wasm" to "application/wasm",
        "woff" to "font/woff", "woff2" to "font/woff2", "ttf" to "font/ttf", "xml" to "application/xml"
    )

    private fun handle(c: Socket) {
        c.use { sock ->
            sock.soTimeout = 10000
            val reader = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val reqLine = reader.readLine() ?: return
            while (true) {
                val l = reader.readLine() ?: break
                if (l.isEmpty()) break
            }
            val parts = reqLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            var path = try {
                URLDecoder.decode(parts[1].substringBefore('?').substringBefore('#').replace("+", "%2B"), "UTF-8")
            } catch (e: Exception) { "/" }
            if (path.endsWith("/")) path += "index.html"
            val base = root ?: return
            val out = sock.getOutputStream()
            val f = try { File(base, path.trimStart('/')).canonicalFile } catch (e: Exception) { null }
            if (f == null || !f.path.startsWith(base.canonicalPath + File.separator) || !f.isFile) {
                val body = "404 Not Found".toByteArray()
                out.write(("HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                if (method != "HEAD") out.write(body)
            } else {
                val type = mimes[f.extension.lowercase()] ?: "application/octet-stream"
                out.write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${f.length()}\r\n" +
                        "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray()
                )
                if (method != "HEAD") f.inputStream().use { it.copyTo(out) }
            }
            out.flush()
        }
    }
}
