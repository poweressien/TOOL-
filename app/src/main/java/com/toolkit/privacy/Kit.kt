package com.toolkit.privacy

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Tiny plain-HTTP helper (no backend, direct calls to public data sources). */
object Net {
    private class Hit(val at: Long, val body: String)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Hit>()
    private const val TTL_MS = 90_000L

    /** GET with a short in-memory cache (repeat lookups of the same URL inside 90 s cost nothing) and a 3 MB size cap. */
    fun get(url: String, timeout: Int = 9000, headers: Map<String, String> = emptyMap(), fresh: Boolean = false): String? {
        val key = url + "|" + headers.entries.joinToString(",") { it.key + "=" + it.value }
        val now = System.currentTimeMillis()
        if (!fresh) cache[key]?.let { if (now - it.at < TTL_MS) return it.body }
        val c = try { URL(url).openConnection() as HttpURLConnection } catch (e: Exception) { return null }
        return try {
            c.connectTimeout = timeout; c.readTimeout = timeout
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "PrivacyToolkit/3.0 (Android)")
            c.setRequestProperty("Accept", "application/json, text/plain, */*")
            c.setRequestProperty("Accept-Encoding", "gzip")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            val code = c.responseCode
            val raw = if (code in 200..299) c.inputStream else c.errorStream
            val stream = if (raw != null && "gzip".equals(c.contentEncoding, true)) java.util.zip.GZIPInputStream(raw) else raw
            val text = stream?.use { st ->
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
                while (out.size() < 3_000_000) { val n = st.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                out.toString("UTF-8")
            }
            if (code in 200..299 && text != null) {
                if (cache.size > 64) cache.entries.removeIf { now - it.value.at > TTL_MS }
                if (cache.size > 96) cache.clear()
                cache[key] = Hit(now, text)
                text
            } else null
        } catch (e: Exception) { null } finally { try { c.disconnect() } catch (e: Exception) { } }
    }

    fun json(url: String, timeout: Int = 9000, headers: Map<String, String> = emptyMap()): JSONObject? =
        try { get(url, timeout, headers)?.let { JSONObject(it) } } catch (e: Exception) { null }

    fun jsonArr(url: String, timeout: Int = 9000, headers: Map<String, String> = emptyMap()): JSONArray? =
        try { get(url, timeout, headers)?.let { JSONArray(it) } } catch (e: Exception) { null }

    /** Response headers + status of a HEAD/GET request. */
    fun headers(url: String, timeout: Int = 8000): Pair<Int, Map<String, String>>? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeout; c.readTimeout = timeout; c.instanceFollowRedirects = false
        c.setRequestProperty("User-Agent", "PrivacyToolkit/3.0 (Android)")
        val code = c.responseCode
        val m = LinkedHashMap<String, String>()
        c.headerFields.forEach { (k, v) -> if (k != null) m[k.lowercase(Locale.US)] = v.joinToString("; ") }
        c.disconnect()
        code to m
    } catch (e: Exception) { null }

    fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}

/** Small JSON-array persistence on SharedPreferences. Everything stays on the phone. */
object Store {
    fun sp(ctx: Context): SharedPreferences = ctx.applicationContext.getSharedPreferences("toolkit_suite", Context.MODE_PRIVATE)
    fun arr(ctx: Context, key: String): JSONArray = try { JSONArray(sp(ctx).getString(key, "[]")) } catch (e: Exception) { JSONArray() }
    fun putArr(ctx: Context, key: String, a: JSONArray) { sp(ctx).edit().putString(key, a.toString()).apply() }
    fun obj(ctx: Context, key: String): JSONObject = try { JSONObject(sp(ctx).getString(key, "{}")) } catch (e: Exception) { JSONObject() }
    fun putObj(ctx: Context, key: String, o: JSONObject) { sp(ctx).edit().putString(key, o.toString()).apply() }
    fun append(ctx: Context, key: String, o: JSONObject, max: Int = 2000) {
        val a = arr(ctx, key); a.put(o)
        if (a.length() > max) {
            val n = JSONArray(); for (i in a.length() - max until a.length()) n.put(a.get(i)); putArr(ctx, key, n)
        } else putArr(ctx, key, a)
    }
    fun list(a: JSONArray): List<JSONObject> = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
}

object Fmt {
    fun time(ms: Long, pattern: String = "dd MMM HH:mm"): String = SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ms))
    fun day(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))
    fun dur(sec: Long): String {
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        return if (h > 0) "${h}h ${m}m" else if (m > 0) "${m}m ${s}s" else "${s}s"
    }
    fun money(v: Double, digits: Int = 2): String = String.format(Locale.US, "%,.${digits}f", v)
    fun smart(v: Double): String = when {
        v == 0.0 -> "0"
        kotlin.math.abs(v) >= 1000 -> String.format(Locale.US, "%,.2f", v)
        kotlin.math.abs(v) >= 1 -> String.format(Locale.US, "%,.4f", v)
        else -> String.format(Locale.US, "%.8f", v).trimEnd('0').trimEnd('.')
    }
    fun hex(b: ByteArray, sep: String = " "): String = b.joinToString(sep) { "%02X".format(it) }
}

object Geo {
    fun distM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val p1 = lat1 * PI / 180; val p2 = lat2 * PI / 180
        val dp = (lat2 - lat1) * PI / 180; val dl = (lon2 - lon1) * PI / 180
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = lat1 * PI / 180; val p2 = lat2 * PI / 180; val dl = (lon2 - lon1) * PI / 180
        val y = sin(dl) * cos(p2); val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (atan2(y, x) * 180 / PI + 360) % 360
    }
    fun dist(m: Double): String = if (m >= 1000) String.format(Locale.US, "%.2f km", m / 1000) else String.format(Locale.US, "%.0f m", m)
}

/** In-place radix-2 FFT, returns magnitude in dB for the first n/2 bins (fftshifted for IQ data via [shiftComplex]). */
