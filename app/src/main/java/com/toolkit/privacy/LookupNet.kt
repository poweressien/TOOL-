package com.toolkit.privacy

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class EmailReport(val info: EmailInfo, val score: Int, val live: List<Finding>, val facts: List<String>)

/** Blocking network probes for an email address. Uses only public, key-less endpoints. */
object LookupNet {
    private const val UA = "PrivacyToolkit/1.1"

    private fun get(url: String, timeout: Int = 6000, accept: String? = null): Pair<Int, String>? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeout; c.readTimeout = timeout
        c.setRequestProperty("User-Agent", UA)
        if (accept != null) c.setRequestProperty("Accept", accept)
        val code = c.responseCode
        val body = try { (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "" } catch (e: Exception) { "" }
        c.disconnect()
        code to body
    } catch (e: Exception) { null }

    data class Dns(val status: Int, val answers: List<String>)

    fun parseDoh(json: String): Dns {
        val status = Regex(""""Status"\s*:\s*(\d+)""").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val answers = Regex(""""data"\s*:\s*"((?:[^"\\]|\\.)*)"""").findAll(json).map { it.groupValues[1].replace("\\\"", "\"") }.toList()
        return Dns(status, answers)
    }

    fun doh(name: String, type: String): Dns? {
        val r = get("https://dns.google/resolve?name=${URLEncoder.encode(name, "UTF-8")}&type=$type", accept = "application/dns-json") ?: return null
        return if (r.first in 200..299) parseDoh(r.second) else null
    }

    fun gravatarExists(md5: String): Boolean? {
        return try {
            val c = URL("https://www.gravatar.com/avatar/$md5?d=404").openConnection() as HttpURLConnection
            c.connectTimeout = 6000; c.readTimeout = 6000; c.requestMethod = "HEAD"
            val code = c.responseCode; c.disconnect()
            when (code) { 200 -> true; 404 -> false; else -> null }
        } catch (e: Exception) { null }
    }

    /** (total, firstLogin) for public GitHub profiles that show this email. */
    fun githubUsers(email: String): Pair<Int, String?>? {
        val r = get("https://api.github.com/search/users?q=${URLEncoder.encode(email, "UTF-8")}+in:email", accept = "application/vnd.github+json") ?: return null
        if (r.first != 200) return null
        val total = Regex(""""total_count"\s*:\s*(\d+)""").find(r.second)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val login = Regex(""""login"\s*:\s*"([^"]+)"""").find(r.second)?.groupValues?.get(1)
        return total to login
    }

    fun analyzeEmail(raw: String): EmailReport {
        val info = LookupLogic.parseEmail(raw)
        if (!info.valid) return EmailReport(info, 1, emptyList(), emptyList())
        val domain = info.domain
        val reg = SiteRules.registrable(domain)
        val pool = Executors.newFixedThreadPool(5)
        val live = ArrayList<Finding>()
        val facts = ArrayList<String>()
        try {
            val mx = pool.submit(Callable { doh(domain, "MX") })
            val spf = pool.submit(Callable { doh(domain, "TXT") })
            val dmarc = pool.submit(Callable { doh("_dmarc.$domain", "TXT") })
            val grav = pool.submit(Callable { gravatarExists(info.md5) })
            val gh = pool.submit(Callable { githubUsers("${info.local}@${domain}") })
            val age = if ("custom" in info.kind) pool.submit(Callable { SiteCheck.domainAgeDays(reg) }) else null
            fun <T> res(f: java.util.concurrent.Future<T>?): T? = try { f?.get(12, TimeUnit.SECONDS) } catch (e: Exception) { null }

            val mxR = res(mx)
            if (mxR == null) facts += "Couldn't check mail servers (offline?)"
            else if (mxR.status == 3) live += Finding("Domain doesn't exist, so this address can't receive mail", -45)
            else if (mxR.answers.isEmpty()) live += Finding("Domain has no mail servers, so mail to it would bounce", -35)
            else {
                live += Finding("Domain can receive mail (${mxR.answers.size} mail server${if (mxR.answers.size > 1) "s" else ""})", 8)
                facts += "Mail server: " + mxR.answers.first().substringAfter(' ').trimEnd('.')
            }
            if ("custom" in info.kind && mxR != null && mxR.answers.isNotEmpty()) {
                val hasSpf = res(spf)?.answers?.any { it.contains("v=spf1", true) }
                val hasDmarc = res(dmarc)?.answers?.any { it.contains("v=DMARC1", true) }
                if (hasSpf == false && hasDmarc == false) live += Finding("No SPF or DMARC records, so anyone can easily fake mail from this domain", -6)
                else if (hasSpf == true && hasDmarc == true) live += Finding("Domain has SPF and DMARC protection", 4)
            }
            res(grav)?.let { if (it) { live += Finding("Has a Gravatar profile picture (real accounts usually do)", 5); facts += "Gravatar: yes" } else facts += "Gravatar: none" }
            res(gh)?.let { (n, login) -> if (n > 0) { live += Finding("Linked to a public GitHub profile${login?.let { " ($it)" } ?: ""}", 6); facts += "GitHub: github.com/$login" } }
            SiteRules.ageFinding(res(age))?.let { live += it }
        } finally { pool.shutdownNow() }
        return EmailReport(info, LookupLogic.emailScore(info, live), live, facts)
    }
}
