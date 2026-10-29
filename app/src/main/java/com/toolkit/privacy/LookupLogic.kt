package com.toolkit.privacy

import java.net.URLEncoder
import java.security.MessageDigest

data class PhoneInfo(
    val valid: Boolean,
    val input: String,
    val e164: String,
    val national: String,
    val countryCode: String,
    val country: String,
    val carrier: String?,
    val notes: List<String>
) {
    val digits: String get() = e164.removePrefix("+")
}

data class EmailInfo(
    val valid: Boolean,
    val input: String,
    val local: String,
    val domain: String,
    val kind: List<String>,       // free / disposable / role / custom domain
    val notes: List<Finding>,     // static findings (delta shows effect on trust)
    val md5: String
)

data class WebLink(val title: String, val url: String, val hint: String = "")

object LookupLogic {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ------------------------------------------------------------------ phone

    // calling code -> (country, ISO2)
    private val COUNTRIES = mapOf(
        "1" to ("USA / Canada" to "us"), "7" to ("Russia / Kazakhstan" to "ru"), "20" to ("Egypt" to "eg"),
        "27" to ("South Africa" to "za"), "31" to ("Netherlands" to "nl"), "33" to ("France" to "fr"), "34" to ("Spain" to "es"),
        "39" to ("Italy" to "it"), "44" to ("United Kingdom" to "gb"), "49" to ("Germany" to "de"), "55" to ("Brazil" to "br"),
        "61" to ("Australia" to "au"), "62" to ("Indonesia" to "id"), "63" to ("Philippines" to "ph"), "81" to ("Japan" to "jp"),
        "86" to ("China" to "cn"), "90" to ("Turkey" to "tr"), "91" to ("India" to "in"), "92" to ("Pakistan" to "pk"),
        "212" to ("Morocco" to "ma"), "220" to ("Gambia" to "gm"), "221" to ("Senegal" to "sn"), "223" to ("Mali" to "ml"),
        "224" to ("Guinea" to "gn"), "225" to ("Côte d'Ivoire" to "ci"), "226" to ("Burkina Faso" to "bf"), "227" to ("Niger" to "ne"),
        "228" to ("Togo" to "tg"), "229" to ("Benin" to "bj"), "230" to ("Mauritius" to "mu"), "231" to ("Liberia" to "lr"),
        "232" to ("Sierra Leone" to "sl"), "233" to ("Ghana" to "gh"), "234" to ("Nigeria" to "ng"), "235" to ("Chad" to "td"),
        "237" to ("Cameroon" to "cm"), "243" to ("DR Congo" to "cd"), "244" to ("Angola" to "ao"), "250" to ("Rwanda" to "rw"),
        "251" to ("Ethiopia" to "et"), "252" to ("Somalia" to "so"), "254" to ("Kenya" to "ke"), "255" to ("Tanzania" to "tz"),
        "256" to ("Uganda" to "ug"), "260" to ("Zambia" to "zm"), "263" to ("Zimbabwe" to "zw"), "353" to ("Ireland" to "ie"),
        "355" to ("Albania" to "al"), "375" to ("Belarus" to "by"), "880" to ("Bangladesh" to "bd"), "966" to ("Saudi Arabia" to "sa"),
        "971" to ("United Arab Emirates" to "ae"), "881" to ("Satellite / shared service" to "xx"), "882" to ("International network" to "xx"),
        "883" to ("International network" to "xx")
    )

    private val NG_CARRIERS: Map<String, String> = buildMap {
        fun add(name: String, vararg p: String) = p.forEach { put(it, name) }
        add("MTN", "0703", "0704", "0706", "0803", "0806", "0810", "0813", "0814", "0816", "0903", "0906", "0913", "0916")
        add("Airtel", "0701", "0708", "0802", "0808", "0812", "0901", "0902", "0904", "0907", "0912")
        add("Glo", "0705", "0805", "0807", "0811", "0815", "0905", "0915")
        add("9mobile", "0809", "0817", "0818", "0908", "0909")
        add("Ntel", "0804")
        add("Smile", "0702")
    }

    /** Longest matching calling code for a digit string, or null. */
    private fun matchCc(digits: String): String? = (3 downTo 1).map { digits.take(it) }.firstOrNull { it in COUNTRIES }

    fun parsePhone(raw: String, defaultCc: String = "234"): PhoneInfo {
        val trimmed = raw.trim()
        var d = trimmed.filter { it.isDigit() || it == '+' }
        val hadPlus = d.startsWith("+")
        d = d.filter { it.isDigit() }
        val notes = ArrayList<String>()
        if (d.length < 6) return PhoneInfo(false, raw, "", "", "", "", null, listOf("Too short to be a phone number"))

        var cc: String
        var national: String
        when {
            hadPlus || d.startsWith("00") -> {
                if (d.startsWith("00")) d = d.substring(2)
                cc = matchCc(d) ?: return PhoneInfo(false, raw, "+$d", d, "", "Unknown", null, listOf("Couldn't recognise the country code"))
                national = d.substring(cc.length)
                // people often type +2340803... (extra 0)
                if (national.startsWith("0") && cc == "234") { national = national.removePrefix("0"); notes += "Removed the extra 0 after +234" }
            }
            d.startsWith("0") -> { cc = defaultCc; national = d.substring(1) }
            d.startsWith(defaultCc) && d.length > defaultCc.length + 6 -> { cc = defaultCc; national = d.substring(defaultCc.length) }
            else -> { cc = defaultCc; national = d }
        }
        val (country, iso) = COUNTRIES[cc] ?: ("Unknown" to "xx")
        val e164 = "+$cc$national"
        var valid = national.length in 6..14 && e164.length <= 16
        var carrier: String? = null
        var nationalPretty = national

        if (cc == "234") {
            val ok = national.length == 10 && national[0] in "789"
            valid = ok
            if (!ok) notes += "A Nigerian mobile number has 10 digits after +234 (e.g. 803 123 4567). This one has ${national.length}"
            carrier = NG_CARRIERS["0" + national.take(3)]
            nationalPretty = "0" + national
            if (ok && carrier == null) notes += "Prefix 0${national.take(3)} isn't in the known mobile ranges"
            if (carrier != null) notes += "Carrier is read from the number prefix. If the owner moved networks (number portability) it can be different"
        } else if (cc == "1" && national.length != 10) {
            valid = false; notes += "US/Canada numbers have 10 digits after +1"
        } else if (cc == "44" && national.length !in 9..10) {
            valid = false; notes += "UK numbers have 9 or 10 digits after +44"
        }
        if (cc in setOf("881", "882", "883")) notes += "Numbers in this range are international shared services. They are often abused for premium-rate and callback scams"
        if (cc != defaultCc && cc != "xx" && valid) notes += "Foreign number. If you don't know anyone there, don't call or text back (callback and premium-rate scams use unfamiliar international numbers)"
        if (national.length >= 6 && national.toSet().size <= 2) notes += "Digits look repeated or fake"
        return PhoneInfo(valid, raw, e164, nationalPretty, cc, country, carrier, notes)
    }

    fun phoneLinks(p: PhoneInfo): List<WebLink> {
        if (p.e164.isEmpty()) return emptyList()
        val local = if (p.countryCode == "234") "0" + p.e164.removePrefix("+234") else p.e164
        val q = "\"${local}\" OR \"${p.e164}\" OR \"${p.digits}\""
        val iso = ISO[p.countryCode] ?: "ng"
        val nat = p.e164.removePrefix("+" + p.countryCode)
        return listOf(
            WebLink("Google: the number everywhere", "https://www.google.com/search?q=${enc(q)}", "Pages, ads, listings that mention it"),
            WebLink("Google: scam / spam reports", "https://www.google.com/search?q=${enc("\"$local\" OR \"${p.e164}\" scam OR fraud OR spam OR 419")}", "Has anyone been burned by it?"),
            WebLink("Truecaller", "https://www.truecaller.com/search/$iso/$nat", "Caller name (may ask you to log in)"),
            WebLink("Sync.me", "https://sync.me/search/?number=${enc(p.e164)}", "Crowd-sourced caller names"),
            WebLink("WhatsApp: is it registered?", "https://wa.me/${p.digits}", "Opens a chat if the number is on WhatsApp"),
            WebLink("Telegram", "https://t.me/${p.e164}", "Shows the profile if the number is public"),
            WebLink("Facebook search", "https://www.facebook.com/search/top?q=${enc(local)}", "Profiles or pages that list it"),
            WebLink("X / Twitter search", "https://x.com/search?q=${enc(local)}&src=typed_query", "Public posts mentioning it")
        )
    }

    private val ISO: Map<String, String> = COUNTRIES.mapValues { it.value.second }

    // ------------------------------------------------------------------ email

    private val EMAIL_RE = Regex("""^[A-Za-z0-9._%+'-]{1,64}@([A-Za-z0-9-]+\.)+[A-Za-z]{2,24}$""")

    private val DISPOSABLE = setOf(
        "mailinator.com", "guerrillamail.com", "guerrillamail.net", "guerrillamail.org", "guerrillamail.biz", "grr.la",
        "sharklasers.com", "10minutemail.com", "10minutemail.net", "tempmail.com", "temp-mail.org", "temp-mail.io",
        "yopmail.com", "yopmail.fr", "trashmail.com", "getnada.com", "nada.email", "dispostable.com", "maildrop.cc",
        "throwawaymail.com", "fakeinbox.com", "mohmal.com", "emailondeck.com", "moakt.com", "mintemail.com", "tempinbox.com",
        "spamgourmet.com", "mailnesia.com", "burnermail.io", "discard.email", "tempail.com", "inboxkitten.com", "1secmail.com",
        "1secmail.net", "1secmail.org", "mailcatch.com", "tmail.ws", "tmpmail.org", "tmpmail.net", "emailfake.com", "fakemail.net",
        "mytemp.email", "tempr.email", "dropmail.me", "mail.tm", "mailpoof.com", "trbvm.com", "luxusmail.org", "zetmail.com"
    )

    private val FREE = setOf(
        "gmail.com", "googlemail.com", "yahoo.com", "yahoo.co.uk", "ymail.com", "outlook.com", "hotmail.com", "live.com", "msn.com",
        "icloud.com", "me.com", "proton.me", "protonmail.com", "aol.com", "zoho.com", "mail.com", "gmx.com", "yandex.com", "yandex.ru"
    )

    private val TYPOS = mapOf(
        "gmial.com" to "gmail.com", "gmai.com" to "gmail.com", "gamil.com" to "gmail.com", "gmail.co" to "gmail.com",
        "gmaill.com" to "gmail.com", "gnail.com" to "gmail.com", "gmali.com" to "gmail.com", "gmail.con" to "gmail.com",
        "yaho.com" to "yahoo.com", "yahooo.com" to "yahoo.com", "yahoo.con" to "yahoo.com", "hotmial.com" to "hotmail.com",
        "hotmal.com" to "hotmail.com", "outlok.com" to "outlook.com", "outllook.com" to "outlook.com", "iclod.com" to "icloud.com"
    )

    private val ROLES = setOf(
        "admin", "support", "info", "sales", "contact", "noreply", "no-reply", "billing", "security", "help", "service",
        "hr", "careers", "office", "team", "hello", "accounts", "payments", "verify", "alert", "notification"
    )

    fun md5(s: String): String = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun parseEmail(raw: String): EmailInfo {
        val e = raw.trim().trim('<', '>', '"', ' ')
        if (!EMAIL_RE.matches(e) || e.contains("..")) {
            return EmailInfo(false, raw, "", "", emptyList(), listOf(Finding("That isn't a valid email address", 0)), "")
        }
        val local = e.substringBefore('@')
        val domain = e.substringAfter('@').lowercase()
        val kind = ArrayList<String>()
        val notes = ArrayList<Finding>()
        val reg = SiteRules.registrable(domain)

        when {
            domain in DISPOSABLE -> { kind += "disposable"; notes += Finding("Throwaway inbox service. Real people and real businesses rarely use these", -40) }
            domain in FREE -> { kind += "free"; notes += Finding("Free mail provider (${domain})", 0) }
            else -> { kind += "custom"; notes += Finding("Uses its own domain ($reg)", 0) }
        }
        TYPOS[domain]?.let { notes += Finding("Domain looks like a typo of $it", -25) }
        if (local.lowercase().substringBefore('+') in ROLES) { kind += "role"; notes += Finding("Role address (${local.substringBefore('+')}@). A team inbox, not a person", -3) }
        if (local.count { it.isDigit() } >= 6) notes += Finding("Lots of digits in the name part (auto-generated look)", -8)
        if ("custom" in kind) {
            val v = SiteRules.analyze("https://$domain")
            v.findings.filter { it.delta < 0 && (it.text.startsWith("Uses the name") || it.text.startsWith("Looks like a spoof") || it.text.startsWith("Domain extension") || it.text.startsWith("Free throwaway") || it.text.startsWith("Scam-style")) }
                .forEach { notes += it }
        }
        return EmailInfo(true, raw, local, domain, kind, notes, md5(e.lowercase()))
    }

    fun emailLinks(e: EmailInfo): List<WebLink> {
        if (!e.valid) return emptyList()
        val addr = "${e.local}@${e.domain}"
        return listOf(
            WebLink("Google: the address everywhere", "https://www.google.com/search?q=${enc("\"$addr\"")}", "Pages, forums, listings"),
            WebLink("Google: scam reports", "https://www.google.com/search?q=${enc("\"$addr\" scam OR fraud OR phishing OR spam")}", "Has it been reported?"),
            WebLink("Have I Been Pwned", "https://haveibeenpwned.com/account/${enc(addr)}", "Was it in a data breach?"),
            WebLink("Epieos", "https://epieos.com/?q=${enc(addr)}&t=email", "Which public services know this address"),
            WebLink("GitHub", "https://github.com/search?q=${enc(addr)}&type=users", "Developer profile with this email"),
            WebLink("LinkedIn", "https://www.linkedin.com/search/results/all/?keywords=${enc(addr)}", "Professional profile"),
            WebLink("Facebook search", "https://www.facebook.com/search/top?q=${enc(addr)}", "Profiles or pages that list it"),
            WebLink("X / Twitter search", "https://x.com/search?q=${enc(addr)}&src=typed_query", "Public posts mentioning it")
        )
    }

    /** Trust score for an email from static + network findings. 100 = looks genuine. */
    fun emailScore(e: EmailInfo, extra: List<Finding>): Int {
        if (!e.valid) return 1
        return (70 + e.notes.sumOf { it.delta } + extra.sumOf { it.delta }).coerceIn(1, 100)
    }
}
