package com.toolkit.privacy

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class WebHit(val title: String, val url: String, val host: String, val snippet: String)
data class DomainIntel(val registrar: String?, val created: String?, val nameservers: List<String>, val ip: String?, val country: String?, val isp: String?)
data class WebReport(val query: String, val hits: List<WebHit>, val scamHits: Int, val scamNotes: List<Finding>, val facts: List<String>, val reachable: Boolean)

/** Key-less web lookups. Everything is best-effort: any failure just means fewer facts. */
object WebIntel {
    private const val UA = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"

    fun get(url: String, timeout: Int = 8000, accept: String? = null): Pair<Int, String>? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeout; c.readTimeout = timeout
        c.setRequestProperty("User-Agent", UA)
        if (accept != null) c.setRequestProperty("Accept", accept)
        val code = c.responseCode
        val body = try { (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "" } catch (e: Exception) { "" }
        c.disconnect(); code to body
    } catch (e: Exception) { null }

    private fun stripTags(s: String) = s.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'")
        .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace(Regex("\\s+"), " ").trim()

    /** Parse DuckDuckGo's HTML results page. */
    fun parseDdg(html: String, max: Int = 10): List<WebHit> {
        val out = ArrayList<WebHit>()
        val block = Regex("""<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>(.*?)</a>(.*?)(?=<a[^>]*class="[^"]*result__a|$)""", RegexOption.DOT_MATCHES_ALL)
        for (m in block.findAll(html)) {
            var href = m.groupValues[1].replace("&amp;", "&")
            val uddg = Regex("""[?&]uddg=([^&]+)""").find(href)?.groupValues?.get(1)
            if (uddg != null) href = try { URLDecoder.decode(uddg, "UTF-8") } catch (e: Exception) { href }
            if (href.startsWith("//")) href = "https:$href"
            if (!href.startsWith("http") || href.contains("duckduckgo.com/y.js")) continue
            val title = stripTags(m.groupValues[2])
            val snip = Regex("""class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).find(m.groupValues[3])?.groupValues?.get(1)?.let { stripTags(it) } ?: ""
            val host = SiteRules.parse(href)?.host ?: continue
            out += WebHit(title, href, host, snip)
            if (out.size >= max) break
        }
        return out
    }

    private val SCAM_WORDS = Regex("""\b(scam|scammer|fraud|fraudster|419|phishing|spam|fake|stolen|blackmail|harass|beware|reported|complaint|ripoff|rip-off|warning|blacklist(ed)?|catfish)\b""", RegexOption.IGNORE_CASE)

    fun scamSignals(hits: List<WebHit>): Pair<Int, List<Finding>> {
        val bad = hits.filter { SCAM_WORDS.containsMatchIn(it.title + " " + it.snippet) }
        val notes = ArrayList<Finding>()
        if (bad.isNotEmpty()) notes += Finding("${bad.size} of ${hits.size} web results mention scam, spam or fraud (${bad.take(2).joinToString(", ") { it.host }})", -(10 * bad.size).coerceAtMost(40))
        else if (hits.isNotEmpty()) notes += Finding("Appears on the web with no scam reports in the top results", 3)
        else notes += Finding("No public web footprint found", 0)
        return bad.size to notes
    }

    fun search(query: String, max: Int = 10): List<WebHit>? {
        val r = get("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8")) ?: return null
        return if (r.first in 200..299) parseDdg(r.second, max) else null
    }

    fun footprint(queries: List<String>, scamTerm: String): WebReport {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val fs = queries.map { q -> pool.submit(Callable { search(q) }) }
            val all = ArrayList<WebHit>(); var reached = false
            fs.forEach { f -> try { f.get(15, TimeUnit.SECONDS)?.let { reached = true; all += it } } catch (e: Exception) { } }
            val uniq = all.distinctBy { it.url }.take(12)
            val (n, notes) = scamSignals(uniq)
            val domains = uniq.map { it.host.removePrefix("www.") }.distinct()
            val facts = ArrayList<String>()
            if (uniq.isNotEmpty()) facts += "${uniq.size} web results across ${domains.size} sites"
            val social = domains.filter { d -> listOf("facebook", "instagram", "linkedin", "twitter", "x.com", "tiktok", "truecaller", "github", "jiji", "olx", "nairaland").any { d.contains(it) } }
            if (social.isNotEmpty()) facts += "Shows up on: " + social.take(5).joinToString(", ")
            return WebReport(scamTerm, uniq, n, notes, facts, reached)
        } finally { pool.shutdownNow() }
    }

    fun phoneFootprint(p: PhoneInfo): WebReport {
        val local = if (p.countryCode == "234") "0" + p.e164.removePrefix("+234") else p.e164
        return footprint(listOf("\"$local\" OR \"${p.e164}\"", "\"$local\" scam OR fraud OR spam OR 419"), local)
    }

    fun emailFootprint(addr: String): WebReport =
        footprint(listOf("\"$addr\"", "\"$addr\" scam OR fraud OR phishing OR spam"), addr)

    // ---- Gravatar profile (name, bio, linked accounts) ----
    fun parseGravatar(json: String): List<String> {
        val out = ArrayList<String>()
        Regex(""""displayName"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { out += "Gravatar name: $it" }
        Regex(""""currentLocation"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { out += "Location on profile: $it" }
        Regex(""""aboutMe"\s*:\s*"([^"]{1,140})""").find(json)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { out += "Bio: $it" }
        val accts = Regex(""""shortname"\s*:\s*"([^"]+)"""").findAll(json).map { it.groupValues[1] }.distinct().toList()
        if (accts.isNotEmpty()) out += "Linked accounts: " + accts.joinToString(", ")
        return out
    }

    fun gravatar(md5: String): List<String> {
        val r = get("https://en.gravatar.com/$md5.json") ?: return emptyList()
        return if (r.first == 200) parseGravatar(r.second) else emptyList()
    }

    // ---- RDAP details ----
    fun parseRdap(json: String): Triple<String?, String?, List<String>> {
        val reg = Regex("""\{[^{}]*"roles"\s*:\s*\[[^\]]*"registrar"[^\]]*\][^{}]*\}""").find(json)?.value
        var registrar = reg?.let { Regex(""""(?:fn|name)"\s*,\s*\{\}\s*,\s*"text"\s*,\s*"([^"]+)"""").find(it)?.groupValues?.get(1) }
        if (registrar == null) registrar = Regex("""\["fn"\s*,\s*\{\}\s*,\s*"text"\s*,\s*"([^"]+)"""").findAll(json).map { it.groupValues[1] }.firstOrNull()
        val created = Regex("""\{[^{}]*"eventAction"\s*:\s*"registration"[^{}]*\}""").find(json)?.value
            ?.let { Regex(""""eventDate"\s*:\s*"(\d{4}-\d{2}-\d{2})""").find(it)?.groupValues?.get(1) }
        val ns = Regex(""""ldhName"\s*:\s*"([^"]+)"""").findAll(json).map { it.groupValues[1].lowercase() }.filter { it.contains('.') }.distinct().take(4).toList()
        return Triple(registrar, created, ns)
    }

    fun parseIpWho(json: String): Triple<String?, String?, String?> {
        if (!Regex(""""success"\s*:\s*true""").containsMatchIn(json)) return Triple(null, null, null)
        val country = Regex(""""country"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
        val isp = Regex(""""isp"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
            ?: Regex(""""org"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
        return Triple(null, country, isp)
    }

    fun domainIntel(domain: String): DomainIntel {
        val reg = SiteRules.registrable(domain)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val rd = pool.submit(Callable { get("https://rdap.org/domain/$reg", accept = "application/rdap+json")?.takeIf { it.first in 200..299 }?.second })
            val a = pool.submit(Callable { LookupNet.doh(domain, "A")?.answers?.firstOrNull { Regex("""^\d+\.\d+\.\d+\.\d+$""").matches(it) } })
            val ip = try { a.get(10, TimeUnit.SECONDS) } catch (e: Exception) { null }
            val rdap = try { rd.get(10, TimeUnit.SECONDS) } catch (e: Exception) { null }
            val (registrar, created, ns) = if (rdap != null) parseRdap(rdap) else Triple(null, null, emptyList())
            var country: String? = null; var isp: String? = null
            if (ip != null) get("https://ipwho.is/$ip")?.takeIf { it.first == 200 }?.let { val t = parseIpWho(it.second); country = t.second; isp = t.third }
            return DomainIntel(registrar, created, ns, ip, country, isp)
        } finally { pool.shutdownNow() }
    }

    fun domainFacts(d: DomainIntel): List<String> {
        val o = ArrayList<String>()
        d.registrar?.let { o += "Domain registrar: $it" }
        d.created?.let { o += "Domain registered: $it" }
        if (d.nameservers.isNotEmpty()) o += "Nameservers: " + d.nameservers.joinToString(", ")
        if (d.ip != null) o += "Server IP: ${d.ip}" + (d.country?.let { " ($it)" } ?: "") + (d.isp?.let { ", host: $it" } ?: "")
        return o
    }
}
