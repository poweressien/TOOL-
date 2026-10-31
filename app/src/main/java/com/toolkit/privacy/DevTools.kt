package com.toolkit.privacy

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Pure developer helpers (no Android UI). Each tool takes up to two text inputs and returns text. */
object DevTools {
    class Tool(val name: String, val labelA: String, val labelB: String?, val hint: String)

    val tools: List<Tool> = listOf(
        Tool("JWT", "JWT token", null, "Decode header + payload, check expiry, flag weak algorithms"),
        Tool("Regex", "Test text", "Regex pattern (add (?i) for ignore-case)", "All matches and capture groups"),
        Tool("Diff", "Text A", "Text B", "Line-by-line difference"),
        Tool("Cron", "Cron expression (5 fields)", null, "Explains it and lists the next 5 runs"),
        Tool("Bases", "Number (0x.. hex, 0b.. binary, or decimal)", null, "Binary / octal / decimal / hex + bit info"),
        Tool("HMAC", "Message", "Secret key", "HMAC-SHA1 / 256 / 512"),
        Tool("Hex/Text", "Text or hex bytes", null, "Text to hex and hex to text"),
        Tool("Unicode", "Text", null, "Code points, UTF-8 bytes, invisible characters"),
        Tool("Escape", "Text", null, "JSON / Java / HTML escape and unescape"),
        Tool("Case", "Text", null, "camelCase, snake_case, kebab-case, PascalCase, CONSTANT"),
        Tool("JSON→CSV", "JSON array of objects", null, "Converts to CSV"),
        Tool("CSV→JSON", "CSV with header row", null, "Converts to JSON"),
        Tool("JSON path", "JSON", "Path e.g. data.items[0].name", "Pick a value out of JSON"),
        Tool("JSON sort", "JSON", null, "Sort keys alphabetically"),
        Tool("Tokens", "Length (default 32)", null, "Random hex / base64 / password / ULID / API-key style"),
        Tool("URL parts", "URL", null, "Scheme, host, path, query params"),
        Tool("chmod", "Octal (755) or symbolic (rwxr-xr-x)", null, "Unix permission calculator"),
        Tool("HTTP status", "Code (e.g. 418) or word", null, "What a status code means"),
        Tool("User agent", "User-Agent string", null, "Browser / OS / device"),
        Tool("Text stats", "Text", null, "Chars, words, lines, reading time, top words"),
        Tool("Lines", "Lines", "Operation: sort, unique, reverse, trim, number, shuffle", "Line utilities"),
        Tool("Slug", "Text", null, "URL slug"),
        Tool("Lorem", "Paragraphs (default 2)", null, "Placeholder text"),
        Tool("Bytes", "Size e.g. 1536000 or 1.5GB", null, "Human readable sizes (SI and binary)"),
        Tool("Duration", "Seconds or 1h30m", null, "Seconds ↔ days/hours/minutes"),
        Tool("Port", "Port number or service name", null, "What a port is used for"),
        Tool("CIDR", "Address with prefix, e.g. 192.168.1.10/24", null, "Network, mask, range and host count"),
        Tool("Hash all", "Text to hash", null, "MD5, SHA-1, SHA-256, SHA-384 and SHA-512 at once")
    )

    fun run(tool: String, a: String, b: String): String = try {
        when (tool) {
            "JWT" -> jwt(a)
            "Regex" -> regex(a, b)
            "Diff" -> diff(a, b)
            "Cron" -> cron(a)
            "Bases" -> bases(a)
            "HMAC" -> hmac(a, b)
            "Hex/Text" -> hexText(a)
            "Unicode" -> unicode(a)
            "Escape" -> escape(a)
            "Case" -> caseConv(a)
            "JSON→CSV" -> jsonToCsv(a)
            "CSV→JSON" -> csvToJson(a)
            "JSON path" -> jsonPath(a, b)
            "JSON sort" -> jsonSort(a)
            "Tokens" -> tokens(a)
            "URL parts" -> urlParts(a)
            "chmod" -> chmod(a)
            "HTTP status" -> httpStatus(a)
            "User agent" -> userAgent(a)
            "Text stats" -> textStats(a)
            "Lines" -> lines(a, b)
            "Slug" -> slug(a)
            "Lorem" -> lorem(a)
            "Bytes" -> bytes(a)
            "Duration" -> duration(a)
            "Port" -> port(a)
            "CIDR" -> cidr(a)
            "Hash all" -> hashAll(a)
            else -> "Unknown tool"
        }
    } catch (e: Exception) { "Error: ${e.message}" }

    private fun b64url(s: String): ByteArray {
        var t = s.replace('-', '+').replace('_', '/')
        while (t.length % 4 != 0) t += "="
        return android.util.Base64.decode(t, android.util.Base64.DEFAULT)
    }

    fun jwt(tok: String): String {
        val p = tok.trim().removePrefix("Bearer ").split('.')
        if (p.size < 2) return "Not a JWT (needs header.payload.signature)"
        val h = JSONObject(String(b64url(p[0])))
        val pl = JSONObject(String(b64url(p[1])))
        val sb = StringBuilder()
        sb.appendLine("HEADER\n" + h.toString(2)).appendLine().appendLine("PAYLOAD\n" + pl.toString(2)).appendLine()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val now = System.currentTimeMillis() / 1000
        for (k in listOf("iat", "nbf", "exp")) if (pl.has(k)) sb.appendLine("$k: ${fmt.format(Date(pl.getLong(k) * 1000))}")
        if (pl.has("exp")) sb.appendLine(if (pl.getLong("exp") < now) "⚠ EXPIRED ${(now - pl.getLong("exp")) / 60} minutes ago" else "✓ valid for another ${(pl.getLong("exp") - now) / 60} minutes")
        else sb.appendLine("⚠ No expiry (exp): token never expires")
        val alg = h.optString("alg")
        if (alg.equals("none", true)) sb.appendLine("🚨 alg=none: signature not verified, anyone can forge this token")
        if (alg.startsWith("HS")) sb.appendLine("ℹ HMAC token: the secret must be long and random; short secrets can be brute-forced")
        if (p.size < 3 || p[2].isEmpty()) sb.appendLine("⚠ Token has no signature part")
        sb.appendLine("Signature is NOT verified here (no key).")
        return sb.toString()
    }

    fun regex(text: String, pat: String): String {
        if (pat.isEmpty()) return "Enter a pattern"
        val re = Regex(pat)
        val ms = re.findAll(text).toList()
        if (ms.isEmpty()) return "No matches"
        val sb = StringBuilder("${ms.size} match(es)\n")
        ms.take(100).forEachIndexed { i, m ->
            sb.appendLine("#${i + 1} [${m.range.first}-${m.range.last}] \"${m.value}\"")
            m.groupValues.drop(1).forEachIndexed { g, v -> sb.appendLine("     group ${g + 1}: \"$v\"") }
        }
        return sb.toString()
    }

    fun diff(a: String, b: String): String {
        val x = a.lines().take(600); val y = b.lines().take(600)
        val n = x.size; val m = y.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) dp[i][j] = if (x[i] == y[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        val sb = StringBuilder(); var i = 0; var j = 0; var add = 0; var del = 0
        while (i < n && j < m) {
            if (x[i] == y[j]) { sb.appendLine("  ${x[i]}"); i++; j++ }
            else if (dp[i + 1][j] >= dp[i][j + 1]) { sb.appendLine("- ${x[i]}"); i++; del++ } else { sb.appendLine("+ ${y[j]}"); j++; add++ }
        }
        while (i < n) { sb.appendLine("- ${x[i++]}"); del++ }
        while (j < m) { sb.appendLine("+ ${y[j++]}"); add++ }
        return "+$add added, -$del removed\n\n$sb"
    }

    private fun cronField(f: String, lo: Int, hi: Int): Set<Int> {
        val out = sortedSetOf<Int>()
        for (part in f.split(',')) {
            val step = part.substringAfter('/', "1").toInt()
            val range = part.substringBefore('/')
            val (s, e) = when {
                range == "*" -> lo to hi
                '-' in range -> range.substringBefore('-').toInt() to range.substringAfter('-').toInt()
                else -> range.toInt() to (if ('/' in part) hi else range.toInt())
            }
            var v = s; while (v <= e) { if (v in lo..hi) out += v; v += step }
        }
        return out
    }

    fun cron(expr: String): String {
        val f = expr.trim().split(Regex("\\s+"))
        if (f.size != 5) return "Cron needs 5 fields: minute hour day-of-month month day-of-week"
        val mi = cronField(f[0], 0, 59); val ho = cronField(f[1], 0, 23); val dom = cronField(f[2], 1, 31); val mo = cronField(f[3], 1, 12)
        val dow = cronField(f[4].replace("7", "0"), 0, 6)
        val sb = StringBuilder()
        sb.appendLine("minute: ${f[0]}  hour: ${f[1]}  day: ${f[2]}  month: ${f[3]}  weekday: ${f[4]}")
        sb.appendLine("Runs at minute ${if (mi.size > 8) "${mi.size} values" else mi.joinToString(",")} of hour ${if (ho.size > 12) "${ho.size} values" else ho.joinToString(",")}" +
            (if (f[2] != "*") ", on day ${f[2]}" else "") + (if (f[3] != "*") ", in month ${f[3]}" else "") + (if (f[4] != "*") ", on weekday ${f[4]} (0=Sun)" else ""))
        sb.appendLine().appendLine("Next runs (this phone's time zone):")
        val c = Calendar.getInstance(); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0); c.add(Calendar.MINUTE, 1)
        val fmt = SimpleDateFormat("EEE yyyy-MM-dd HH:mm", Locale.US)
        var found = 0; var guard = 0
        val domStar = f[2] == "*"; val dowStar = f[4] == "*"
        while (found < 5 && guard++ < 600_000) {
            val ok = c.get(Calendar.MINUTE) in mi && c.get(Calendar.HOUR_OF_DAY) in ho && (c.get(Calendar.MONTH) + 1) in mo &&
                (if (!domStar && !dowStar) (c.get(Calendar.DAY_OF_MONTH) in dom || (c.get(Calendar.DAY_OF_WEEK) - 1) in dow) else (c.get(Calendar.DAY_OF_MONTH) in dom && (c.get(Calendar.DAY_OF_WEEK) - 1) in dow))
            if (ok) { sb.appendLine(fmt.format(c.time)); found++ }
            c.add(Calendar.MINUTE, 1)
        }
        if (found == 0) sb.appendLine("(none in the next year)")
        return sb.toString()
    }

    fun bases(s: String): String {
        val t = s.trim().lowercase().replace("_", "")
        val v = when {
            t.startsWith("0x") -> t.substring(2).toLong(16)
            t.startsWith("0b") -> t.substring(2).toLong(2)
            t.startsWith("0o") -> t.substring(2).toLong(8)
            else -> t.toLong()
        }
        return "Decimal : $v\nHex     : 0x${java.lang.Long.toHexString(v)}\nBinary  : 0b${java.lang.Long.toBinaryString(v)}\nOctal   : 0o${java.lang.Long.toOctalString(v)}\nBits set: ${java.lang.Long.bitCount(v)}   Bit length: ${64 - java.lang.Long.numberOfLeadingZeros(v)}\nAs bytes: " +
            (if (v in 0..0xFFFFFFFFL) (3 downTo 0).joinToString(".") { ((v shr (it * 8)) and 255).toString() } + " (as IPv4)" else "-")
    }

    fun hmac(msg: String, key: String): String {
        if (key.isEmpty()) return "Enter a secret key in the second box"
        return listOf("HmacSHA1", "HmacSHA256", "HmacSHA512").joinToString("\n\n") { alg ->
            val m = Mac.getInstance(alg); m.init(SecretKeySpec(key.toByteArray(), alg))
            alg + "\n" + m.doFinal(msg.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }

    fun hexText(s: String): String {
        val t = s.trim()
        val hex = t.replace(Regex("[\\s:]|0x"), "")
        return if (hex.isNotEmpty() && hex.length % 2 == 0 && hex.all { it in "0123456789abcdefABCDEF" })
            "Text: " + String(ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
        else "Hex: " + t.toByteArray().joinToString(" ") { "%02x".format(it) }
    }

    fun unicode(s: String): String {
        val sb = StringBuilder("${s.codePointCount(0, s.length)} code points, ${s.toByteArray().size} UTF-8 bytes\n")
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i); i += Character.charCount(cp)
            val name = try { Character.getName(cp) ?: "" } catch (e: Exception) { "" }
            val invisible = Character.isISOControl(cp) || cp in listOf(0x200B, 0x200C, 0x200D, 0xFEFF, 0x00A0, 0x202E, 0x2060)
            sb.appendLine("U+%04X  %s  %s%s".format(cp, if (invisible) "·" else String(Character.toChars(cp)), name, if (invisible) "  ⚠ invisible/control" else ""))
            if (sb.length > 6000) { sb.appendLine("…"); break }
        }
        return sb.toString()
    }

    fun escape(s: String): String {
        val j = JSONObject.quote(s)
        val html = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
        val unHtml = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
        val unJson = try { JSONTokener(if (s.trim().startsWith("\"")) s.trim() else "\"" + s + "\"").nextValue().toString() } catch (e: Exception) { "(not valid JSON string)" }
        return "JSON string:\n$j\n\nUn-JSON:\n$unJson\n\nHTML escaped:\n$html\n\nHTML unescaped:\n$unHtml"
    }

    private fun words(s: String): List<String> =
        s.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }.map { it.lowercase() }

    fun caseConv(s: String): String {
        val w = words(s)
        if (w.isEmpty()) return "Enter some text"
        val pascal = w.joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }
        return "camelCase  : ${pascal.replaceFirstChar { it.lowercase() }}\nPascalCase : $pascal\nsnake_case : ${w.joinToString("_")}\nkebab-case : ${w.joinToString("-")}\nCONSTANT   : ${w.joinToString("_").uppercase()}\nTitle Case : ${w.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }}\nsentence   : ${w.joinToString(" ").replaceFirstChar { it.uppercase() }}"
    }

    fun jsonToCsv(s: String): String {
        val a = JSONArray(s.trim())
        val cols = LinkedHashSet<String>()
        for (i in 0 until a.length()) a.optJSONObject(i)?.keys()?.forEach { cols += it }
        fun q(v: String) = if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
        val sb = StringBuilder(cols.joinToString(",") { q(it) }).append('\n')
        for (i in 0 until a.length()) { val o = a.optJSONObject(i) ?: continue; sb.append(cols.joinToString(",") { c -> q(if (o.isNull(c)) "" else o.get(c).toString()) }).append('\n') }
        return sb.toString()
    }

    fun parseCsvLine(l: String): List<String> {
        val out = ArrayList<String>(); val cur = StringBuilder(); var q = false; var i = 0
        while (i < l.length) {
            val c = l[i]
            when {
                q && c == '"' && i + 1 < l.length && l[i + 1] == '"' -> { cur.append('"'); i++ }
                c == '"' -> q = !q
                c == ',' && !q -> { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString(); return out
    }

    fun csvToJson(s: String): String {
        val rows = s.trim().lines().filter { it.isNotBlank() }.map { parseCsvLine(it) }
        if (rows.size < 2) return "Need a header row and at least one data row"
        val h = rows[0]; val arr = JSONArray()
        rows.drop(1).forEach { r -> val o = JSONObject(); h.forEachIndexed { i, k -> val v = r.getOrElse(i) { "" }; o.put(k, v.toDoubleOrNull()?.let { d -> if (d % 1.0 == 0.0 && !v.contains('.') && v.length < 16) v.toLong() else d } ?: v) }; arr.put(o) }
        return arr.toString(2)
    }

    fun jsonPath(json: String, path: String): String {
        var cur: Any? = JSONTokener(json.trim()).nextValue()
        for (tok in Regex("[^.\\[\\]]+|\\[\\d+\\]").findAll(path).map { it.value }) {
            cur = if (tok.startsWith("[")) (cur as JSONArray).get(tok.trim('[', ']').toInt()) else (cur as JSONObject).get(tok)
        }
        return when (cur) { is JSONObject -> cur.toString(2); is JSONArray -> cur.toString(2); else -> cur.toString() }
    }

    private fun sortAny(v: Any?): Any? = when (v) {
        is JSONObject -> JSONObject().also { o -> v.keys().asSequence().sorted().forEach { k -> o.put(k, sortAny(v.get(k))) } }
        is JSONArray -> JSONArray().also { a -> for (i in 0 until v.length()) a.put(sortAny(v.get(i))) }
        else -> v
    }

    fun jsonSort(s: String): String = when (val r = sortAny(JSONTokener(s.trim()).nextValue())) { is JSONObject -> r.toString(2); is JSONArray -> r.toString(2); else -> "Not JSON object/array" }

    fun tokens(lenIn: String): String {
        val n = lenIn.trim().toIntOrNull()?.coerceIn(4, 256) ?: 32
        val r = SecureRandom()
        val hex = (1..(n + 1) / 2).joinToString("") { "%02x".format(r.nextInt(256)) }.take(n)
        val b64 = android.util.Base64.encodeToString(ByteArray(n).also { r.nextBytes(it) }, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING).take(n)
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%^&*-_"
        val pw = (1..n).map { chars[r.nextInt(chars.length)] }.joinToString("")
        val crock = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        var t = System.currentTimeMillis(); val ts = CharArray(10); for (i in 9 downTo 0) { ts[i] = crock[(t % 32).toInt()]; t /= 32 }
        val ulid = String(ts) + (1..16).map { crock[r.nextInt(32)] }.joinToString("")
        return "Hex ($n): $hex\n\nBase64url ($n): $b64\n\nPassword ($n): $pw\n\nULID: $ulid\n\nUUID v4: ${java.util.UUID.randomUUID()}\n\nAPI-key style: sk_" + (1..32).map { "abcdefghijklmnopqrstuvwxyz0123456789"[r.nextInt(36)] }.joinToString("")
    }

    fun urlParts(u: String): String {
        val uri = java.net.URI(u.trim().let { if (it.contains("://")) it else "https://$it" })
        val sb = StringBuilder()
        sb.appendLine("Scheme : ${uri.scheme}").appendLine("Host   : ${uri.host}").appendLine("Port   : ${if (uri.port == -1) "(default)" else uri.port}")
        sb.appendLine("Path   : ${uri.path}").appendLine("Fragment: ${uri.fragment ?: "-"}")
        uri.userInfo?.let { sb.appendLine("⚠ User info in URL: $it") }
        sb.appendLine("Query params:")
        (uri.rawQuery ?: "").split('&').filter { it.isNotEmpty() }.forEach { kv -> sb.appendLine("  ${java.net.URLDecoder.decode(kv.substringBefore('='), "UTF-8")} = ${java.net.URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8")}") }
        return sb.toString()
    }

    fun chmod(s: String): String {
        val t = s.trim()
        val oct: Int
        if (Regex("[0-7]{3,4}").matches(t)) oct = t.takeLast(3).toInt(8)
        else if (t.length == 9 && t.all { it in "rwxsStT-" }) oct = t.chunked(3).fold(0) { a, c -> (a shl 3) or ((if (c[0] == 'r') 4 else 0) + (if (c[1] == 'w') 2 else 0) + (if (c[2] in "xst") 1 else 0)) }
        else return "Use 755 or rwxr-xr-x"
        fun sym(v: Int) = (if (v and 4 != 0) "r" else "-") + (if (v and 2 != 0) "w" else "-") + (if (v and 1 != 0) "x" else "-")
        val o = (oct shr 6) and 7; val g = (oct shr 3) and 7; val w = oct and 7
        return "Octal: ${Integer.toOctalString(oct).padStart(3, '0')}\nSymbolic: ${sym(o)}${sym(g)}${sym(w)}\nOwner: ${sym(o)}  Group: ${sym(g)}  Others: ${sym(w)}\nCommand: chmod ${Integer.toOctalString(oct).padStart(3, '0')} file" +
            (if (w and 2 != 0) "\n⚠ World-writable: any user can modify this file" else "")
    }

    private val STATUS = mapOf(
        100 to "Continue", 101 to "Switching Protocols", 200 to "OK", 201 to "Created", 202 to "Accepted", 204 to "No Content", 206 to "Partial Content",
        301 to "Moved Permanently", 302 to "Found (temporary redirect)", 303 to "See Other", 304 to "Not Modified", 307 to "Temporary Redirect", 308 to "Permanent Redirect",
        400 to "Bad Request - malformed request", 401 to "Unauthorized - login needed", 403 to "Forbidden - not allowed", 404 to "Not Found", 405 to "Method Not Allowed",
        406 to "Not Acceptable", 408 to "Request Timeout", 409 to "Conflict", 410 to "Gone", 413 to "Payload Too Large", 415 to "Unsupported Media Type", 418 to "I'm a teapot",
        422 to "Unprocessable Entity", 429 to "Too Many Requests - rate limited", 451 to "Unavailable For Legal Reasons", 500 to "Internal Server Error", 501 to "Not Implemented",
        502 to "Bad Gateway", 503 to "Service Unavailable", 504 to "Gateway Timeout", 520 to "Cloudflare: unknown error", 521 to "Cloudflare: origin down", 522 to "Cloudflare: connection timed out", 530 to "Cloudflare: origin DNS error"
    )

    fun httpStatus(s: String): String {
        val n = s.trim().toIntOrNull()
        if (n != null) return STATUS[n]?.let { "$n $it" } ?: "$n: ${when (n / 100) { 1 -> "informational"; 2 -> "success"; 3 -> "redirect"; 4 -> "client error"; 5 -> "server error"; else -> "unknown" }}"
        return STATUS.filter { it.value.contains(s.trim(), true) }.entries.joinToString("\n") { "${it.key} ${it.value}" }.ifEmpty { "No match" }
    }

    fun userAgent(ua: String): String {
        val os = when { ua.contains("Android") -> "Android " + (Regex("Android ([\\d.]+)").find(ua)?.groupValues?.get(1) ?: ""); ua.contains("iPhone") || ua.contains("iPad") -> "iOS " + (Regex("OS ([\\d_]+)").find(ua)?.groupValues?.get(1)?.replace('_', '.') ?: ""); ua.contains("Windows") -> "Windows"; ua.contains("Mac OS X") -> "macOS"; ua.contains("Linux") -> "Linux"; else -> "?" }
        val br = when { ua.contains("Edg/") -> "Edge"; ua.contains("OPR/") -> "Opera"; ua.contains("SamsungBrowser") -> "Samsung Internet"; ua.contains("Firefox/") -> "Firefox"; ua.contains("Chrome/") -> "Chrome"; ua.contains("Safari/") -> "Safari"; ua.contains("curl") -> "curl"; else -> "?" }
        val ver = Regex("(?:Chrome|Firefox|Edg|Version|OPR)/([\\d.]+)").find(ua)?.groupValues?.get(1) ?: ""
        val model = Regex("; ([^;)]+) Build/").find(ua)?.groupValues?.get(1)
        val bot = Regex("bot|spider|crawl|curl|wget|python|okhttp", RegexOption.IGNORE_CASE).containsMatchIn(ua)
        return "Browser: $br $ver\nOS: $os\nDevice: ${model ?: if (ua.contains("Mobile")) "phone" else "desktop"}\nBot / script: ${if (bot) "likely" else "no"}"
    }

    fun textStats(s: String): String {
        val w = s.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val top = w.map { it.lowercase().trim { c -> !c.isLetterOrDigit() } }.filter { it.length > 3 }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(8)
        return "Characters: ${s.length} (${s.count { !it.isWhitespace() }} without spaces)\nWords: ${w.size}\nLines: ${s.lines().size}\nSentences: ${s.split(Regex("[.!?]+\\s")).count { it.isNotBlank() }}\nReading time: ${"%.1f".format(w.size / 220.0)} min\nTop words: " + top.joinToString { "${it.key}(${it.value})" }
    }

    fun lines(s: String, op: String): String {
        val l = s.lines()
        return when (op.trim().lowercase()) {
            "sort" -> l.sorted().joinToString("\n"); "unique" -> l.distinct().joinToString("\n"); "reverse" -> l.reversed().joinToString("\n")
            "trim" -> l.joinToString("\n") { it.trim() }; "number" -> l.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n"); "shuffle" -> l.shuffled().joinToString("\n")
            else -> "Type one of: sort, unique, reverse, trim, number, shuffle in the second box"
        }
    }

    fun slug(s: String): String = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    fun lorem(n: String): String {
        val w = "lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor incididunt ut labore et dolore magna aliqua enim ad minim veniam quis nostrud exercitation ullamco laboris nisi aliquip ex ea commodo consequat".split(' ')
        val r = java.util.Random(7)
        return (1..(n.trim().toIntOrNull()?.coerceIn(1, 20) ?: 2)).joinToString("\n\n") { (1..5).joinToString(" ") { (1..(8 + r.nextInt(8))).joinToString(" ") { w[r.nextInt(w.size)] }.replaceFirstChar { c -> c.uppercase() } + "." } }
    }

    fun bytes(s: String): String {
        val m = Regex("([\\d.]+)\\s*([kmgtp]?i?b?)", RegexOption.IGNORE_CASE).matchEntire(s.trim()) ?: return "Examples: 1536000, 1.5GB, 700MiB"
        val v = m.groupValues[1].toDouble(); val u = m.groupValues[2].lowercase()
        val mult = when { u.startsWith("k") -> if ("i" in u) 1024.0 else 1000.0; u.startsWith("m") -> if ("i" in u) 1048576.0 else 1e6; u.startsWith("g") -> if ("i" in u) 1073741824.0 else 1e9; u.startsWith("t") -> if ("i" in u) 1099511627776.0 else 1e12; else -> 1.0 }
        val b = v * mult
        fun f(d: Double, base: Double, units: List<String>): String { var x = d; var i = 0; while (x >= base && i < units.size - 1) { x /= base; i++ }; return "%.2f %s".format(x, units[i]) }
        return "Bytes: ${"%.0f".format(b)}\nSI    : ${f(b, 1000.0, listOf("B", "kB", "MB", "GB", "TB", "PB"))}\nBinary: ${f(b, 1024.0, listOf("B", "KiB", "MiB", "GiB", "TiB", "PiB"))}\nBits  : ${"%.0f".format(b * 8)}"
    }

    fun duration(s: String): String {
        val t = s.trim().lowercase()
        val secs = t.toLongOrNull() ?: Regex("(\\d+)\\s*([dhms])").findAll(t).sumOf { m -> m.groupValues[1].toLong() * when (m.groupValues[2]) { "d" -> 86400L; "h" -> 3600L; "m" -> 60L; else -> 1L } }
        if (secs == 0L) return "Enter seconds or something like 1h30m"
        return "$secs seconds\n= ${secs / 86400}d ${secs % 86400 / 3600}h ${secs % 3600 / 60}m ${secs % 60}s\n= ${"%.2f".format(secs / 3600.0)} hours\n= ${"%.3f".format(secs / 86400.0)} days"
    }

    fun port(s: String): String {
        val n = s.trim().toIntOrNull()
        if (n != null) return PortDb.info(n).let { "${it.port}/tcp  ${it.service}\n${it.note}\nRisk level ${it.risk}/3" }
        return PortDb.standard.map { PortDb.info(it) }.filter { it.service.contains(s.trim(), true) }.joinToString("\n") { "${it.port}  ${it.service} - ${it.note}" }.ifEmpty { "No match" }
    }

    fun cidr(s: String): String = NetMath.cidr(s)

    fun hashAll(text: String): String = listOf("MD5", "SHA-1", "SHA-256", "SHA-384", "SHA-512").joinToString("\n") {
        "${it.padEnd(8)} " + MessageDigest.getInstance(it).digest(text.toByteArray()).joinToString("") { b -> "%02x".format(b) }
    }
}
