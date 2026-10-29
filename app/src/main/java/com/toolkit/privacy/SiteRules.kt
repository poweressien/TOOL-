package com.toolkit.privacy

/** One reason behind a score. delta < 0 hurts the score, delta > 0 helps it. */
data class Finding(val text: String, val delta: Int)

data class ParsedUrl(
    val scheme: String,
    val userInfo: String?,
    val host: String,
    val port: Int?,
    val path: String,
    val query: String,
    val full: String
)

data class SiteVerdict(
    val url: String,
    val host: String,
    val base: Int,
    val findings: List<Finding>,
    val network: Boolean = false
) {
    val score: Int get() = (base + findings.sumOf { it.delta }).coerceIn(1, 100)
    val label: String get() = SiteRules.labelFor(score)
}

/**
 * On-device heuristics. No network, no Android. Everything here is deterministic so it can be tested on a plain JVM.
 * 100 = looks safe, 1 = looks dangerous.
 */
object SiteRules {
    fun labelFor(score: Int) = when {
        score >= 75 -> "Looks safe"
        score >= 45 -> "Be careful"
        else -> "Dangerous"
    }

    /** ARGB int. Red until 25, red->yellow until 60, yellow->green until 80, green after. */
    fun colorFor(score: Int): Int {
        val red = 0xFFEF4444.toInt(); val yellow = 0xFFFBBF24.toInt(); val green = 0xFF34D399.toInt()
        return when {
            score <= 25 -> red
            score < 60 -> lerp(red, yellow, (score - 25) / 35f)
            score < 80 -> lerp(yellow, green, (score - 60) / 20f)
            else -> green
        }
    }

    private fun lerp(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a ushr shift) and 0xFF) + ((((b ushr shift) and 0xFF) - ((a ushr shift) and 0xFF)) * t)).toInt() and 0xFF
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private val SUSPICIOUS_TLDS = setOf(
        "zip", "mov", "xyz", "top", "click", "gq", "tk", "ml", "cf", "ga", "work", "loan", "country", "stream",
        "men", "bid", "icu", "cyou", "rest", "monster", "cfd", "sbs", "kim", "party", "science", "date", "download",
        "racing", "review", "trade", "webcam", "accountant", "faith", "win", "buzz", "support", "fit", "quest"
    )
    private val FREE_TLDS = setOf("tk", "ml", "ga", "cf", "gq")

    private val SHORTENERS = setOf(
        "bit.ly", "tinyurl.com", "t.co", "goo.gl", "is.gd", "cutt.ly", "rb.gy", "ow.ly", "shorturl.at", "tiny.cc",
        "buff.ly", "rebrand.ly", "s.id", "v.gd", "t.ly", "lnkd.in", "shorte.st", "bl.ink", "clck.ru", "qr.ae"
    )

    private val FREE_HOSTS = listOf(
        "blogspot.com", "weebly.com", "wixsite.com", "000webhostapp.com", "web.app", "firebaseapp.com", "netlify.app",
        "vercel.app", "github.io", "pages.dev", "workers.dev", "glitch.me", "herokuapp.com", "onrender.com",
        "duckdns.org", "ngrok.io", "ngrok-free.app", "trycloudflare.com", "sites.google.com", "carrd.co", "webflow.io"
    )

    private val MULTI_SUFFIX = setOf(
        "com.ng", "org.ng", "gov.ng", "edu.ng", "net.ng", "sch.ng", "name.ng", "mil.ng", "co.uk", "org.uk", "ac.uk",
        "gov.uk", "co.za", "org.za", "com.au", "co.in", "com.gh", "co.ke", "com.br", "co.jp", "com.cn", "co.tz", "co.ug"
    )

    private val TRUSTED = setOf(
        "google.com", "youtube.com", "gmail.com", "facebook.com", "instagram.com", "whatsapp.com", "twitter.com", "x.com",
        "linkedin.com", "microsoft.com", "live.com", "office.com", "outlook.com", "apple.com", "icloud.com", "amazon.com",
        "wikipedia.org", "github.com", "gitlab.com", "stackoverflow.com", "reddit.com", "netflix.com", "spotify.com",
        "paypal.com", "dropbox.com", "zoom.us", "cloudflare.com", "mozilla.org", "telegram.org", "tiktok.com",
        "openai.com", "anthropic.com", "claude.ai", "yahoo.com", "bing.com", "duckduckgo.com", "whatsapp.net", "t.me",
        "paystack.com", "flutterwave.com", "opay.ng", "opayweb.com", "palmpay.com", "moniepoint.com", "kuda.com",
        "gtbank.com", "zenithbank.com", "firstbanknigeria.com", "accessbankplc.com", "uba.com", "wemabank.com",
        "sterling.ng", "fcmb.com", "ecobank.com", "stanbicibtcbank.com", "fidelitybank.ng", "unionbankng.com",
        "polarisbanklimited.com", "keystonebankng.com", "cbn.gov.ng", "nimc.gov.ng", "firs.gov.ng", "jamb.gov.ng",
        "waec.org", "waecdirect.org", "neco.gov.ng", "nysc.gov.ng", "jumia.com.ng", "konga.com", "mtn.ng", "mtnonline.com",
        "ng.airtel.com", "airtel.com", "gloworld.com", "9mobile.com.ng", "piggyvest.com", "cowrywise.com", "bet9ja.com",
        "sportybet.com", "binance.com", "coinbase.com", "udemy.com", "coursera.org", "medium.com", "apache.org",
        "android.com", "play.google.com", "gov.ng", "gov.uk", "edu.ng"
    )

    private class Brand(val key: String, val official: Set<String> = emptySet())

    private val BRANDS = listOf(
        Brand("paypal", setOf("paypal.com", "paypal.me", "paypalobjects.com")),
        Brand("google", setOf("google.com", "googleapis.com", "gstatic.com", "googleusercontent.com", "googlevideo.com", "withgoogle.com", "google.org")),
        Brand("gmail", setOf("gmail.com")),
        Brand("facebook", setOf("facebook.com", "fb.com", "fbcdn.net", "facebook.net", "fb.me")),
        Brand("instagram", setOf("instagram.com", "cdninstagram.com")),
        Brand("whatsapp", setOf("whatsapp.com", "whatsapp.net", "wa.me")),
        Brand("telegram", setOf("telegram.org", "telegram.me", "t.me")),
        Brand("apple", setOf("apple.com", "icloud.com")),
        Brand("icloud", setOf("icloud.com")),
        Brand("microsoft", setOf("microsoft.com", "live.com", "office.com", "microsoftonline.com", "outlook.com", "windows.com", "azure.com")),
        Brand("outlook", setOf("outlook.com", "live.com")),
        Brand("amazon", setOf("amazon.com", "amazonaws.com", "amazon.co.uk", "amazon.de", "amazon.in")),
        Brand("netflix", setOf("netflix.com", "nflxvideo.net")),
        Brand("binance", setOf("binance.com", "binance.us")),
        Brand("coinbase", setOf("coinbase.com")),
        Brand("metamask", setOf("metamask.io")),
        Brand("trustwallet", setOf("trustwallet.com")),
        Brand("tiktok", setOf("tiktok.com", "tiktokv.com")),
        Brand("twitter", setOf("twitter.com")),
        Brand("linkedin", setOf("linkedin.com", "licdn.com")),
        Brand("dropbox", setOf("dropbox.com")),
        Brand("spotify", setOf("spotify.com", "scdn.co")),
        Brand("gtbank", setOf("gtbank.com", "gtbankapps.com", "gtbank.ng")),
        Brand("gtco", setOf("gtco.com", "gtbank.com")),
        Brand("zenithbank", setOf("zenithbank.com")),
        Brand("firstbank", setOf("firstbanknigeria.com", "firstbankgroup.com", "firstbank.com.ng")),
        Brand("accessbank", setOf("accessbankplc.com", "accessbankplc.net", "accessbank.com")),
        Brand("uba", setOf("uba.com", "ubagroup.com")),
        Brand("opay", setOf("opay.ng", "opayweb.com", "opay-nigeria.com", "operapay.com", "opayinc.com")),
        Brand("palmpay", setOf("palmpay.com", "palmpay.ng")),
        Brand("moniepoint", setOf("moniepoint.com", "teamapt.com")),
        Brand("kuda", setOf("kuda.com")),
        Brand("paystack", setOf("paystack.com", "paystack.co")),
        Brand("flutterwave", setOf("flutterwave.com", "flw.ng")),
        Brand("wemabank", setOf("wemabank.com")),
        Brand("fcmb", setOf("fcmb.com")),
        Brand("ecobank", setOf("ecobank.com")),
        Brand("stanbic", setOf("stanbicibtcbank.com", "stanbicibtc.com")),
        Brand("fidelitybank", setOf("fidelitybank.ng")),
        Brand("unionbank", setOf("unionbankng.com")),
        Brand("jumia", setOf("jumia.com.ng", "jumia.com", "jumia.co.ke", "jumia.com.gh")),
        Brand("konga", setOf("konga.com")),
        Brand("mtn", setOf("mtn.ng", "mtn.com", "mtnonline.com", "mtn.com.gh", "mtn.co.za")),
        Brand("airtel", setOf("airtel.com", "airtel.africa", "ng.airtel.com")),
        Brand("glo", setOf("gloworld.com", "glo.com.ng")),
        Brand("9mobile", setOf("9mobile.com.ng")),
        Brand("cbn", setOf("cbn.gov.ng")),
        Brand("nimc", setOf("nimc.gov.ng")),
        Brand("firs", setOf("firs.gov.ng")),
        Brand("jamb", setOf("jamb.gov.ng", "jamb.org.ng")),
        Brand("waec", setOf("waec.org", "waecdirect.org", "waecnigeria.org")),
        Brand("nysc", setOf("nysc.gov.ng", "nysc.org.ng")),
        Brand("npower", setOf("npower.gov.ng", "npvn.gov.ng")),
        Brand("piggyvest", setOf("piggyvest.com")),
        Brand("cowrywise", setOf("cowrywise.com")),
        Brand("bet9ja", setOf("bet9ja.com")),
        Brand("sportybet", setOf("sportybet.com"))
    )

    private val HOST_WORDS = listOf(
        "login", "signin", "sign-in", "verify", "verification", "secure", "security", "account", "update", "confirm",
        "wallet", "bonus", "claim", "prize", "giveaway", "airdrop", "recover", "recovery", "unlock", "suspended",
        "support", "helpdesk", "alert", "payment", "billing", "refund", "reward", "gift", "free", "promo", "grant", "bvn", "otp"
    )
    private val PATH_WORDS = listOf("login", "signin", "verify", "update", "confirm", "password", "account", "secure", "otp", "bvn", "wallet", "claim")
    private val LOGINISH = listOf("login", "signin", "sign-in", "password", "verify", "otp", "bvn", "wallet", "account")
    private val RISKY_FILES = listOf(".apk", ".exe", ".scr", ".msi", ".bat", ".jar", ".vbs", ".cmd", ".ps1", ".dmg", ".iso")

    private val COMMON_TLDS = setOf(
        "com", "net", "org", "ng", "co", "io", "me", "ly", "gl", "to", "ai", "app", "dev", "info", "biz", "gov", "edu",
        "uk", "us", "za", "gh", "ke", "in", "tv", "cc", "ws", "link", "site", "online", "store", "shop", "live", "page",
        "id", "ru", "cn", "de", "fr", "br"
    ) + SUSPICIOUS_TLDS

    // ---------- parsing ----------

    private val URL_RE = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"'()\[\]{}]+""")
    private val BARE_RE = Regex("""(?i)(?<![@\w./-])((?:[a-z0-9-]+\.)+([a-z]{2,24}))(/[^\s<>"'()\[\]{}]*)?(?![\w@-])""")

    /** All links found in free text (explicit URLs first, then bare domains with a plausible TLD). */
    fun extractUrls(text: String): List<String> {
        val out = LinkedHashSet<String>()
        URL_RE.findAll(text).forEach { out += it.value.trimEnd('.', ',', ';', ':', '!', '?') }
        BARE_RE.findAll(text).forEach { m ->
            val tld = m.groupValues[2].lowercase()
            val whole = m.value.trimEnd('.', ',', ';', ':', '!', '?')
            if (tld in COMMON_TLDS && out.none { it.contains(whole, ignoreCase = true) }) out += whole
        }
        return out.toList()
    }

    fun parse(raw: String): ParsedUrl? {
        var s = raw.trim().trim('<', '>', '"', '\'')
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        var scheme = "https"
        val m = Regex("""(?i)^([a-z][a-z0-9+.-]*)://""").find(s)
        if (m != null) {
            scheme = m.groupValues[1].lowercase()
            s = s.substring(m.value.length)
        } else if (s.startsWith("//")) s = s.substring(2)
        if (scheme != "http" && scheme != "https") return null
        val cut = s.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (cut < 0) s else s.substring(0, cut)
        val rest = if (cut < 0) "" else s.substring(cut)
        if (authority.isEmpty()) return null
        var userInfo: String? = null
        var hostPort = authority
        val at = authority.lastIndexOf('@')
        if (at >= 0) { userInfo = authority.substring(0, at); hostPort = authority.substring(at + 1) }
        var host = hostPort
        var port: Int? = null
        if (hostPort.startsWith("[")) {
            val end = hostPort.indexOf(']')
            if (end > 0) { host = hostPort.substring(0, end + 1); hostPort.substring(end + 1).removePrefix(":").toIntOrNull()?.let { port = it } }
        } else if (hostPort.contains(':')) {
            host = hostPort.substringBefore(':'); port = hostPort.substringAfter(':').toIntOrNull()
        }
        host = host.lowercase().trimEnd('.')
        if (host.isEmpty() || !host.contains('.') && !host.startsWith("[")) return null
        if (host.any { !(it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' || it == '[' || it == ']' || it == ':') }) return null
        val q = rest.indexOf('?')
        val hash = rest.indexOf('#')
        val pathEnd = listOf(q, hash).filter { it >= 0 }.minOrNull() ?: rest.length
        val path = rest.substring(0, pathEnd)
        val query = if (q >= 0) rest.substring(q + 1, if (hash > q) hash else rest.length) else ""
        return ParsedUrl(scheme, userInfo, host, port, path, query, "$scheme://$authority$rest")
    }

    fun isIp(host: String) = Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(host) || host.startsWith("[") ||
        Regex("""^(0x[0-9a-f]+|\d{8,10})$""", RegexOption.IGNORE_CASE).matches(host)

    fun registrable(host: String): String {
        if (isIp(host)) return host
        val labels = host.split('.')
        if (labels.size <= 2) return host
        val last2 = labels.takeLast(2).joinToString(".")
        return if (last2 in MULTI_SUFFIX && labels.size >= 3) labels.takeLast(3).joinToString(".") else last2
    }

    private fun tldOf(reg: String) = reg.substringAfter('.')
    private fun sldOf(reg: String) = reg.substringBefore('.')

    private fun leet(s: String) = s.map {
        when (it) { '0' -> 'o'; '1' -> 'l'; '3' -> 'e'; '4' -> 'a'; '5' -> 's'; '7' -> 't'; '8' -> 'b'; '@' -> 'a'; else -> it }
    }.joinToString("").replace("rn", "m").replace("vv", "w")

    private fun tokens(host: String) = host.split('.', '-').filter { it.isNotEmpty() }

    private fun brandHit(tokens: List<String>, key: String) =
        tokens.any { it == key || (key.length >= 5 && it.contains(key)) }

    private fun isOfficial(b: Brand, reg: String): Boolean {
        if (reg in b.official) return true
        if (b.official.any { reg.endsWith(".$it") }) return true
        // same brand on its own domain under a normal TLD (google.com.ng, amazon.co.uk, mtn.com.gh)
        return sldOf(reg) == b.key && tldOf(reg).substringAfterLast('.') !in SUSPICIOUS_TLDS
    }

    // ---------- analysis ----------

    fun analyze(raw: String): SiteVerdict {
        val p = parse(raw) ?: return SiteVerdict(raw.trim(), "", 50, listOf(Finding("That doesn't look like a web address", 0)))
        val f = ArrayList<Finding>()
        val host = p.host
        val reg = registrable(host)
        val ip = isIp(host)
        val toks = tokens(host)
        var base = 82

        val trusted = !ip && (reg in TRUSTED || reg.endsWith(".gov.ng") || reg.endsWith(".edu.ng") || reg.endsWith(".gov.uk"))
        var impersonated = false

        if (p.scheme == "http") {
            f += Finding("No encryption (plain http). Anyone on the network can read or change what you send", -25)
            if (LOGINISH.any { host.contains(it) || p.path.lowercase().contains(it) })
                f += Finding("Looks like a login or payment page over an unencrypted connection", -12)
        } else f += Finding("Uses HTTPS", 0)

        if (ip) f += Finding("Address is a raw IP number, not a real domain name", -30)
        val puny = host.contains("xn--")
        if (puny) f += Finding("Uses disguised look-alike characters (punycode). Often used to fake real brands", -38)
        if (p.userInfo != null) f += Finding("Has a hidden '@' trick that points somewhere else than it appears", -25)
        if (p.port != null && p.port != 80 && p.port != 443) f += Finding("Unusual port :${p.port}", -8)

        if (!ip) {
            val tld = reg.substringAfterLast('.')
            if (tld in FREE_TLDS) f += Finding("Free throwaway domain extension (.$tld) that scammers love", -20)
            else if (tld in SUSPICIOUS_TLDS) f += Finding("Domain extension (.$tld) is common on spam and phishing sites", -15)

            if (reg in SHORTENERS) f += Finding("Short link. It hides where it really goes", -15)

            val sub = host.removeSuffix(reg).trimEnd('.')
            val depth = if (sub.isEmpty()) 0 else sub.split('.').size
            if (depth >= 4) f += Finding("Very long chain of sub-domains ($depth levels)", -10)
            else if (depth == 3) f += Finding("Many sub-domains ($depth levels)", -5)
            if (host.length > 35) f += Finding("Unusually long domain name", -8)
            if (!puny && host.count { it == '-' } >= 3) f += Finding("Lots of hyphens in the domain", -10)
            if (reg.any { it.isDigit() } && reg.count { it.isDigit() } >= 4) f += Finding("Domain is stuffed with numbers", -6)

            FREE_HOSTS.firstOrNull { reg == it || host.endsWith(".$it") }?.let {
                if (!(trusted && reg == "github.com"))
                    f += Finding("Hosted on a free site builder ($it). Easy to fake, so check who owns it", -10)
            }

            // brand impersonation
            val leetToks = tokens(leet(host))
            for (b in BRANDS) {
                if (isOfficial(b, reg)) continue
                if (brandHit(toks, b.key)) {
                    f += Finding("Uses the name \"${b.key}\" but isn't its official domain", -32); impersonated = true; break
                }
                if (b.key.length >= 4 && brandHit(leetToks, b.key)) {
                    f += Finding("Looks like a spoof of \"${b.key}\" (swapped letters or numbers)", -35); impersonated = true; break
                }
            }

            val hw = HOST_WORDS.filter { host.contains(it) }
            if (hw.isNotEmpty() && !trusted) f += Finding("Scam-style words in the domain: ${hw.take(3).joinToString(", ")}", -(8 * hw.size).coerceAtMost(24))
        }

        val pl = (p.path + "?" + p.query).lowercase()
        val pw = PATH_WORDS.filter { pl.contains(it) }
        if (pw.isNotEmpty() && !trusted) f += Finding("Sensitive words in the link: ${pw.take(3).joinToString(", ")}", -(3 * pw.size).coerceAtMost(9))
        RISKY_FILES.firstOrNull { p.path.lowercase().endsWith(it) }?.let {
            f += Finding("Link downloads a program file ($it)", if (it == ".apk") -22 else -25)
        }
        if (p.full.length > 140) f += Finding("Extremely long link", -5)

        if (trusted && !impersonated && p.userInfo == null && !ip) {
            base = 97
            f += Finding("Well-known, established website", 0)
        }
        return SiteVerdict(p.full, host, base, f)
    }

    /** Merge live-network findings into a static verdict. */
    fun withNetwork(v: SiteVerdict, extra: List<Finding>): SiteVerdict =
        v.copy(findings = v.findings + extra, network = true)

    // ---------- helpers used by network layer ----------

    /** Domain age -> finding. Null when unknown. */
    fun ageFinding(days: Long?): Finding? = when {
        days == null -> null
        days < 0 -> null
        days < 30 -> Finding("Domain registered only $days days ago (new sites are a classic scam sign)", -30)
        days < 180 -> Finding("Domain is young (${days / 30} months old)", -15)
        days < 365 -> Finding("Domain is under a year old", -5)
        days >= 365 * 5 -> Finding("Domain has existed for ${days / 365} years", 8)
        days >= 365 * 2 -> Finding("Domain has existed for ${days / 365} years", 5)
        else -> Finding("Domain is about ${days / 365} year old", 2)
    }
}
