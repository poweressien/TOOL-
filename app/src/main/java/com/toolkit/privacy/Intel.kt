package com.toolkit.privacy

import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

typealias Lines = List<Pair<String, String>>

/** Live look-ups against public data sources. Every call goes straight from the phone, no server of ours. */
object Intel {
    fun isIp(s: String) = Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(s) || (s.contains(':') && Regex("^[0-9a-fA-F:]+$").matches(s))

    fun hostOf(input: String): String {
        val t = input.trim()
        val p = SiteRules.parse(if (t.contains("://")) t else "https://$t")
        return (p?.host ?: t).lowercase().removePrefix("www.").let { if (isIp(t)) t else it }
    }

    fun ipGeo(q: String): Lines {
        val j = Net.json("https://ipwho.is/$q") ?: return listOf("Error" to "Could not reach the IP database")
        if (!j.optBoolean("success", false)) return listOf("Error" to j.optString("message", "Lookup failed"))
        val c = j.optJSONObject("connection"); val tz = j.optJSONObject("timezone"); val f = j.optJSONObject("flag")
        val out = mutableListOf<Pair<String, String>>()
        out += "IP" to j.optString("ip") + " (${j.optString("type")})"
        out += "Location" to listOf(j.optString("city"), j.optString("region"), j.optString("country")).filter { it.isNotBlank() }.joinToString(", ") + (f?.optString("emoji")?.let { " $it" } ?: "")
        out += "Coordinates" to "${j.optDouble("latitude")}, ${j.optDouble("longitude")}"
        out += "Continent" to j.optString("continent")
        c?.let {
            out += "Network owner" to it.optString("org").ifBlank { it.optString("isp") }
            out += "ISP" to it.optString("isp")
            out += "ASN" to "AS${it.optInt("asn")}"
            if (it.optString("domain").isNotBlank()) out += "Operator domain" to it.optString("domain")
        }
        tz?.let { out += "Timezone" to it.optString("id") + " (UTC${it.optString("utc")})" }
        return out
    }

    fun resolve(host: String): Lines {
        val out = mutableListOf<Pair<String, String>>()
        try {
            InetAddress.getAllByName(host).forEach { out += (if (it.address.size == 4) "A" else "AAAA") to (it.hostAddress ?: "") }
            val first = InetAddress.getByName(host)
            val rev = try { first.canonicalHostName } catch (e: Exception) { first.hostAddress }
            if (rev != first.hostAddress) out += "Reverse DNS" to rev
        } catch (e: Exception) { out += "Error" to "Does not resolve (${e.message})" }
        return out
    }

    fun dnsRecords(domain: String): Lines {
        val out = mutableListOf<Pair<String, String>>()
        for (t in listOf("A", "AAAA", "MX", "NS", "TXT", "CNAME", "SOA", "CAA")) {
            val j = Net.json("https://cloudflare-dns.com/dns-query?name=${Net.enc(domain)}&type=$t", 6000, mapOf("Accept" to "application/dns-json")) ?: continue
            val ans = j.optJSONArray("Answer") ?: continue
            for (i in 0 until ans.length()) {
                val a = ans.getJSONObject(i)
                if (a.optInt("type") == typeNum(t)) out += t to a.optString("data").trim('"').take(160) + "  (ttl ${a.optInt("TTL")})"
            }
        }
        if (out.isEmpty()) out += "DNS" to "No records returned"
        return out
    }

    private fun typeNum(t: String) = when (t) { "A" -> 1; "NS" -> 2; "CNAME" -> 5; "SOA" -> 6; "MX" -> 15; "TXT" -> 16; "AAAA" -> 28; "CAA" -> 257; else -> -1 }

    fun mailAuth(domain: String): Lines {
        fun txt(name: String): List<String> {
            val j = Net.json("https://cloudflare-dns.com/dns-query?name=${Net.enc(name)}&type=TXT", 6000, mapOf("Accept" to "application/dns-json")) ?: return emptyList()
            val a = j.optJSONArray("Answer") ?: return emptyList()
            return (0 until a.length()).map { a.getJSONObject(it).optString("data").trim('"') }
        }
        val out = mutableListOf<Pair<String, String>>()
        val spf = txt(domain).firstOrNull { it.startsWith("v=spf1") }
        out += "SPF" to (spf?.take(140) ?: "MISSING: anyone can spoof email from this domain")
        if (spf != null) out += "SPF policy" to when { spf.contains("-all") -> "Strict (-all)"; spf.contains("~all") -> "Soft fail (~all)"; spf.contains("?all") -> "Neutral"; spf.contains("+all") -> "DANGEROUS (+all allows everyone)"; else -> "No default policy" }
        val dm = txt("_dmarc.$domain").firstOrNull { it.startsWith("v=DMARC1") }
        out += "DMARC" to (dm?.take(140) ?: "MISSING: spoofed mail will not be rejected")
        if (dm != null) out += "DMARC policy" to Regex("p=(\\w+)").find(dm)?.groupValues?.get(1).orEmpty().ifEmpty { "?" }
        val sel = listOf("default", "google", "selector1", "selector2", "k1", "s1", "mail", "dkim").firstOrNull { txt("$it._domainkey.$domain").any { r -> r.contains("p=") } }
        out += "DKIM" to (sel?.let { "found (selector \"$it\")" } ?: "no common selector found")
        val bimi = txt("default._bimi.$domain").firstOrNull()
        if (bimi != null) out += "BIMI" to bimi.take(100)
        return out
    }

    fun whois(domain: String): Lines {
        val j = Net.json("https://rdap.org/domain/${Net.enc(domain)}", 10000) ?: return listOf("WHOIS" to "No RDAP record (TLD may not support it)")
        val out = mutableListOf<Pair<String, String>>()
        val ev = j.optJSONArray("events")
        if (ev != null) for (i in 0 until ev.length()) {
            val e = ev.getJSONObject(i)
            out += e.optString("eventAction").replaceFirstChar { it.uppercase() } to e.optString("eventDate").take(10)
        }
        j.optJSONArray("status")?.let { out += "Status" to (0 until it.length()).joinToString(", ") { i -> it.getString(i) } }
        val ents = j.optJSONArray("entities")
        if (ents != null) for (i in 0 until ents.length()) {
            val e = ents.getJSONObject(i)
            val roles = e.optJSONArray("roles")?.let { r -> (0 until r.length()).map { r.getString(it) } } ?: emptyList()
            if ("registrar" in roles) {
                val vc = e.optJSONArray("vcardArray")?.optJSONArray(1)
                var nm: String? = null
                if (vc != null) for (k in 0 until vc.length()) { val row = vc.optJSONArray(k); if (row != null && row.optString(0) == "fn") nm = row.optString(3) }
                out += "Registrar" to (nm ?: "unknown")
            }
        }
        val ns = j.optJSONArray("nameservers")
        if (ns != null) out += "Name servers" to (0 until ns.length()).joinToString(", ") { ns.getJSONObject(it).optString("ldhName").lowercase() }
        if (j.optJSONObject("secureDNS")?.optBoolean("delegationSigned") == true) out += "DNSSEC" to "signed"
        return out
    }

    fun archiveAge(host: String): Lines {
        val j = Net.jsonArr("https://web.archive.org/cdx/search/cdx?url=${Net.enc(host)}&limit=1&output=json&fl=timestamp,original", 9000)
        val out = mutableListOf<Pair<String, String>>()
        val row = j?.optJSONArray(1)
        if (row == null) out += "Wayback Machine" to "Never archived: the site is very new or obscure"
        else {
            val ts = row.optString(0)
            out += "First archived" to "${ts.take(4)}-${ts.substring(4, 6)}-${ts.substring(6, 8)}"
            val yrs = (java.time.LocalDate.now().year - ts.take(4).toInt())
            out += "Seen for" to "$yrs year(s)"
        }
        return out
    }

    private fun trustAll(): SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>?, a: String?) { }
            override fun checkServerTrusted(c: Array<X509Certificate>?, a: String?) { }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }), null)
    }

    fun tls(host: String, port: Int = 443): Lines {
        val out = mutableListOf<Pair<String, String>>()
        var trusted = true; var why = ""
        try {
            (SSLSocketFactory.getDefault().createSocket() as SSLSocket).use { s ->
                s.connect(InetSocketAddress(host, port), 6000); s.soTimeout = 6000; s.startHandshake()
            }
        } catch (e: Exception) { trusted = false; why = e.message?.take(100).orEmpty() }
        out += "Trusted by Android" to (if (trusted) "Yes" else "NO: $why")
        try {
            (trustAll().socketFactory.createSocket() as SSLSocket).use { s ->
                s.connect(InetSocketAddress(host, port), 6000); s.soTimeout = 6000
                s.startHandshake()
                out += "Protocol" to s.session.protocol + if (s.session.protocol in listOf("TLSv1", "TLSv1.1", "SSLv3")) "  (OUTDATED)" else ""
                out += "Cipher" to s.session.cipherSuite
                val chain = s.session.peerCertificates.filterIsInstance<X509Certificate>()
                chain.forEachIndexed { i, c ->
                    val tag = if (i == 0) "Certificate" else "Chain #$i"
                    out += "$tag subject" to c.subjectX500Principal.name.take(120)
                    out += "$tag issuer" to c.issuerX500Principal.name.take(120)
                    if (i == 0) {
                        val left = (c.notAfter.time - System.currentTimeMillis()) / 86_400_000L
                        out += "Valid from" to Fmt.time(c.notBefore.time, "yyyy-MM-dd")
                        out += "Expires" to Fmt.time(c.notAfter.time, "yyyy-MM-dd") + if (left < 0) "  (EXPIRED)" else "  ($left days left)"
                        out += "Signature" to c.sigAlgName
                        val k = c.publicKey
                        out += "Key" to when (k) { is RSAPublicKey -> "RSA ${k.modulus.bitLength()}-bit"; is ECPublicKey -> "EC ${k.params.order.bitLength()}-bit"; else -> k.algorithm }
                        val sans = try { c.subjectAlternativeNames?.mapNotNull { it.getOrNull(1)?.toString() } ?: emptyList() } catch (e: Exception) { emptyList() }
                        if (sans.isNotEmpty()) out += "Covers (${sans.size})" to sans.take(12).joinToString(", ") + if (sans.size > 12) " ..." else ""
                    }
                }
            }
        } catch (e: Exception) { out += "TLS" to "No HTTPS service on port $port (${e.message?.take(60)})" }
        return out
    }

    class HeaderReport(val lines: Lines, val grade: String, val score: Int)

    fun securityHeaders(host: String): HeaderReport {
        val r = Net.headers("https://$host") ?: Net.headers("http://$host")
            ?: return HeaderReport(listOf("Request" to "Could not connect"), "-", 0)
        val (code, h) = r
        val out = mutableListOf<Pair<String, String>>()
        out += "HTTP status" to code.toString()
        h["location"]?.let { out += "Redirects to" to it }
        h["server"]?.let { out += "Server" to it }
        h["x-powered-by"]?.let { out += "Powered by" to it + "  (reveals technology)" }
        var score = 0
        fun chk(name: String, pts: Int, label: String, test: (String) -> Boolean = { true }) {
            val v = h[name]
            if (v != null && test(v)) { score += pts; out += label to "✔ " + v.take(90) } else out += label to "✘ missing" + (if (v != null) " / weak" else "")
        }
        chk("strict-transport-security", 25, "HSTS") { Regex("max-age=(\\d+)").find(it)?.groupValues?.get(1)?.toLongOrNull()?.let { s -> s >= 15552000 } == true }
        chk("content-security-policy", 25, "Content-Security-Policy")
        chk("x-content-type-options", 12, "X-Content-Type-Options") { it.contains("nosniff", true) }
        chk("x-frame-options", 12, "X-Frame-Options")
        chk("referrer-policy", 10, "Referrer-Policy")
        chk("permissions-policy", 10, "Permissions-Policy")
        if (h.containsKey("cross-origin-opener-policy")) score += 6
        val ck = h["set-cookie"]
        if (ck != null) {
            val ok = ck.contains("secure", true) && ck.contains("httponly", true)
            out += "Cookies" to if (ok) "✔ Secure + HttpOnly" else "✘ missing Secure/HttpOnly flags"
            if (ok) score += 6
        }
        score = score.coerceAtMost(100)
        val grade = when { score >= 90 -> "A+"; score >= 80 -> "A"; score >= 65 -> "B"; score >= 50 -> "C"; score >= 35 -> "D"; else -> "F" }
        return HeaderReport(out, grade, score)
    }

    fun wellKnown(host: String): Lines {
        val out = mutableListOf<Pair<String, String>>()
        val sec = Net.get("https://$host/.well-known/security.txt", 5000)
        out += "security.txt" to (if (sec != null && sec.contains("Contact", true)) "✔ present: " + (Regex("(?im)^contact:\\s*(.+)$").find(sec)?.groupValues?.get(1) ?: "") else "not published")
        val rb = Net.get("https://$host/robots.txt", 5000)
        if (rb != null && rb.length < 20000) {
            val dis = rb.lines().filter { it.startsWith("Disallow:", true) }.map { it.substringAfter(':').trim() }.filter { it.length > 1 }
            out += "robots.txt" to "present, ${dis.size} blocked path(s)"
            val sens = dis.filter { Regex("admin|login|backup|private|secret|config|db|sql|panel|staging|test|old", RegexOption.IGNORE_CASE).containsMatchIn(it) }.take(6)
            if (sens.isNotEmpty()) out += "Interesting paths" to sens.joinToString("  ")
        } else out += "robots.txt" to "none"
        return out
    }

    fun subdomains(domain: String): Lines {
        val a = Net.jsonArr("https://crt.sh/?q=%25.${Net.enc(domain)}&output=json", 25000) ?: return listOf("Certificate log" to "crt.sh did not answer (try again)")
        val set = sortedSetOf<String>()
        for (i in 0 until a.length()) {
            a.optJSONObject(i)?.optString("name_value")?.split('\n')?.forEach { n -> val x = n.trim().lowercase().removePrefix("*."); if (x.endsWith(domain)) set += x }
        }
        val out = mutableListOf<Pair<String, String>>()
        out += "Found" to "${set.size} hostname(s) in public certificate logs"
        set.take(60).forEach { out += "•" to it }
        if (set.size > 60) out += "..." to "${set.size - 60} more"
        return out
    }

    fun redirectChain(url: String): Lines {
        val out = mutableListOf<Pair<String, String>>()
        var cur = if (url.contains("://")) url else "https://$url"
        for (i in 1..8) {
            val r = Net.headers(cur, 6000) ?: run { out += "Hop $i" to "$cur → no answer"; return out }
            out += "Hop $i" to "${r.first}  $cur"
            val loc = r.second["location"] ?: break
            cur = try { java.net.URL(java.net.URL(cur), loc).toString() } catch (e: Exception) { break }
        }
        return out
    }
}
