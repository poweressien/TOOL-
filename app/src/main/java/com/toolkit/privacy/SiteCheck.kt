package com.toolkit.privacy

import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.cert.X509Certificate
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

/** Blocking helpers. Call from a background thread. Plain java.net so the logic runs on a normal JVM too. */
object SiteCheck {
    private const val UA = "Mozilla/5.0 (Linux; Android 13) PrivacyToolkit/1.1"

    /** Static heuristics first, then live checks (TLS, redirects, domain age) merged in. */
    fun run(raw: String, timeoutMs: Int = 6000): SiteVerdict {
        val v0 = SiteRules.analyze(raw)
        val p = SiteRules.parse(raw) ?: return v0
        val reg = SiteRules.registrable(p.host)
        val ip = SiteRules.isIp(p.host)
        val pool = Executors.newFixedThreadPool(3)
        val extra = ArrayList<Finding>()
        try {
            val tls = pool.submit(Callable { tlsFindings(p, timeoutMs) })
            val redir = pool.submit(Callable { redirectFindings(p, timeoutMs) })
            val age = if (ip) null else pool.submit(Callable { SiteRules.ageFinding(domainAgeDays(reg, timeoutMs)) })
            val limit = (timeoutMs * 2L)
            fun <T> get(f: java.util.concurrent.Future<T>?): T? = try { f?.get(limit, TimeUnit.MILLISECONDS) } catch (e: Exception) { null }
            get(tls)?.let { extra += it }
            get(redir)?.let { extra += it }
            get(age)?.let { extra += it }
        } finally {
            pool.shutdownNow()
        }
        return SiteRules.withNetwork(v0, extra)
    }

    fun tlsFindings(p: ParsedUrl, timeoutMs: Int): List<Finding> {
        val out = ArrayList<Finding>()
        val port = if (p.scheme == "https") (p.port ?: 443) else 443
        try {
            val c = URL("https://${p.host}:$port/").openConnection() as HttpsURLConnection
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
            c.requestMethod = "HEAD"; c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", UA)
            c.responseCode
            val cert = c.serverCertificates?.firstOrNull() as? X509Certificate
            if (cert != null) {
                val left = ChronoUnit.DAYS.between(LocalDate.now(), cert.notAfter.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate())
                val issuer = Regex("O=([^,]+)").find(cert.issuerX500Principal.name)?.groupValues?.get(1)?.trim('"') ?: "a certificate authority"
                if (left < 0) out += Finding("Security certificate has expired", -40)
                else if (left < 7) out += Finding("Security certificate expires in $left days", -5)
                else out += Finding("Valid security certificate (issued by $issuer)", 3)
            }
            c.disconnect()
        } catch (e: UnknownHostException) {
            out += Finding("This domain doesn't exist or can't be reached", -25)
        } catch (e: SocketTimeoutException) {
            out += Finding("Site took too long to answer", -3)
        } catch (e: SSLException) {
            val m = (e.message ?: "") + (e.cause?.message ?: "")
            out += when {
                m.contains("expired", true) -> Finding("Security certificate has expired", -40)
                m.contains("match", true) || m.contains("hostname", true) -> Finding("Certificate belongs to a different site (name mismatch)", -38)
                m.contains("trust", true) || m.contains("path", true) -> Finding("Certificate isn't from a trusted authority (self-signed or fake)", -38)
                else -> Finding("HTTPS handshake failed, so the site's security is broken", -30)
            }
        } catch (e: Exception) {
            if (p.scheme == "https") out += Finding("Couldn't open a secure connection", -8)
        }
        return out
    }

    fun redirectFindings(p: ParsedUrl, timeoutMs: Int): List<Finding> {
        val out = ArrayList<Finding>()
        var cur = p.full
        var hops = 0
        var last: HttpURLConnection? = null
        try {
            while (hops < 6) {
                val c = URL(cur).openConnection() as HttpURLConnection
                c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
                c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", UA)
                c.setRequestProperty("Range", "bytes=0-0")
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location")
                    c.disconnect()
                    if (loc.isNullOrBlank()) break
                    cur = URL(URL(cur), loc).toString()
                    hops++
                } else { last = c; break }
            }
            val final = SiteRules.parse(cur)
            if (hops >= 4) out += Finding("Bounces through $hops redirects before landing", -6)
            if (final != null && SiteRules.registrable(final.host) != SiteRules.registrable(p.host)) {
                val dest = SiteRules.analyze(cur)
                out += Finding("Sends you to a different site: ${final.host}", -8)
                if (dest.score < 45) out += Finding("...and that destination looks dangerous", -25)
            }
            last?.let { c ->
                val type = (c.contentType ?: "").lowercase()
                val disp = (c.getHeaderField("Content-Disposition") ?: "").lowercase()
                if (type.contains("android.package-archive") || disp.contains(".apk")) out += Finding("Starts an APK download as soon as you open it", -28)
                else if (type.contains("x-msdownload") || type.contains("x-dosexec") || disp.contains(".exe")) out += Finding("Starts a program download as soon as you open it", -28)
                else if (disp.contains("attachment")) out += Finding("Forces a file download", -8)
                c.disconnect()
            }
        } catch (e: Exception) {
            // unreachable is already reported by the TLS probe
        }
        return out
    }

    /** Days since the domain was registered according to RDAP, or null if unknown. */
    fun domainAgeDays(reg: String, timeoutMs: Int = 6000): Long? {
        return try {
            val c = URL("https://rdap.org/domain/$reg").openConnection() as HttpURLConnection
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
            c.setRequestProperty("Accept", "application/rdap+json")
            c.setRequestProperty("User-Agent", UA)
            if (c.responseCode !in 200..299) { c.disconnect(); return null }
            val body = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            parseRdapAgeDays(body, LocalDate.now())
        } catch (e: Exception) { null }
    }

    fun parseRdapAgeDays(json: String, today: LocalDate): Long? {
        val ev = Regex("""\{[^{}]*"eventAction"\s*:\s*"registration"[^{}]*\}""").find(json)?.value ?: return null
        val d = Regex(""""eventDate"\s*:\s*"(\d{4}-\d{2}-\d{2})""").find(ev)?.groupValues?.get(1) ?: return null
        return try { ChronoUnit.DAYS.between(LocalDate.parse(d), today) } catch (e: Exception) { null }
    }
}
