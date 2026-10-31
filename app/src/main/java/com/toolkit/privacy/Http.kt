package com.toolkit.privacy

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Tiny HTTP helper used by the newer tools. No backend: every call goes straight from the phone to a public API. */
object Http {
    const val UA = "PrivacyToolkit/3.0 (Android)"

    private class Hit(val at: Long, val code: Int, val body: String)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Hit>()
    private const val TTL_MS = 20_000L

    /** GET with a 20 s cache for successful answers (stops double-taps and screen re-entries re-downloading) and a size cap. */
    fun get(url: String, timeout: Int = 8000, headers: Map<String, String> = emptyMap(), maxBytes: Int = 3_000_000, fresh: Boolean = false): Pair<Int, String>? {
        val key = url + "|" + headers.entries.joinToString(",") { it.key + "=" + it.value }
        val now = System.currentTimeMillis()
        if (!fresh) cache[key]?.let { if (now - it.at < TTL_MS) return it.code to it.body }
        val c = try { URL(url).openConnection() as HttpURLConnection } catch (e: Exception) { return null }
        return try {
            c.connectTimeout = timeout; c.readTimeout = timeout
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "application/json, text/html;q=0.9, */*;q=0.5")
            c.setRequestProperty("Accept-Encoding", "gzip")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            val code = c.responseCode
            val raw = if (code >= 400) c.errorStream else c.inputStream
            val stream = if (raw != null && "gzip".equals(c.contentEncoding, true)) java.util.zip.GZIPInputStream(raw) else raw
            val out = ByteArrayOutputStream()
            stream?.use { s ->
                val buf = ByteArray(8192)
                while (out.size() < maxBytes) { val n = s.read(buf); if (n < 0) break; out.write(buf, 0, n) }
            }
            val body = out.toString("UTF-8")
            if (code in 200..299) {
                if (cache.size > 64) cache.entries.removeIf { now - it.value.at > TTL_MS }
                if (cache.size > 96) cache.clear()
                cache[key] = Hit(now, code, body)
            }
            code to body
        } catch (e: Exception) { null } finally { try { c.disconnect() } catch (e: Exception) { } }
    }

    fun json(url: String, timeout: Int = 8000, headers: Map<String, String> = emptyMap()): JSONObject? {
        val r = get(url, timeout, headers) ?: return null
        if (r.first !in 200..299) return null
        return try { JSONObject(r.second) } catch (e: Exception) { null }
    }

    fun jsonArray(url: String, timeout: Int = 8000, headers: Map<String, String> = emptyMap()): JSONArray? {
        val r = get(url, timeout, headers) ?: return null
        if (r.first !in 200..299) return null
        return try { JSONArray(r.second) } catch (e: Exception) { null }
    }

    /** Status line + response headers (HEAD, falls back to GET). */
    fun headers(url: String, timeout: Int = 7000): Pair<Int, Map<String, List<String>>>? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeout; c.readTimeout = timeout
        c.instanceFollowRedirects = false
        c.requestMethod = "GET"
        c.setRequestProperty("User-Agent", UA)
        val code = c.responseCode
        val h = c.headerFields.filterKeys { it != null }.mapKeys { it.key!! }
        c.disconnect()
        code to h
    } catch (e: Exception) { null }
}
