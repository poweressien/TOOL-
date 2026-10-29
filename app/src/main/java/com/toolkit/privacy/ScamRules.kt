package com.toolkit.privacy

data class ScamVerdict(val score: Int, val label: String, val findings: List<Finding>, val links: List<SiteVerdict>)

/** Reads a pasted SMS / WhatsApp / email and scores how scammy it is. 100 = looks fine, 1 = almost certainly a scam. */
object ScamRules {
    private class Rule(val re: Regex, val delta: Int, val why: String)

    private fun r(p: String, d: Int, why: String) = Rule(Regex(p, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), -d, why)

    private val RULES = listOf(
        r("""\b(send|share|give|provide|reply with|forward|confirm|enter|submit|tell)\b.{0,40}\b(otp|pin|cvv|bvn|nin|password|passcode|token|card number|verification code|secret code)\b""", 38,
            "Asks you to hand over a PIN, OTP, BVN, NIN or password. Real banks never do this"),
        r("""\b(account|card|sim|line|wallet|bvn|nin|atm)\b.{0,30}\b(blocked|suspended|deactivated|restricted|closed|locked|disabled|expire[sd]?|on hold|frozen)\b""", 26,
            "Threatens to block or close something unless you act"),
        r("""\b(urgent(ly)?|immediately|within \d+ ?(hours?|hrs?|minutes?|mins?|days?)|last warning|final notice|act now|expires? (today|soon|tonight)|right now|asap)\b""", 14,
            "Pushes you to hurry"),
        r("""(you('ve| have)? (just )?(won|been selected|been chosen)|congratulations|lucky (winner|draw)|lottery|giveaway|\bgrant\b|free (data|airtime|money|recharge)|claim (your )?(prize|reward|gift|bonus|money))""", 32,
            "Prize, grant or freebie you never signed up for"),
        r("""\b(processing|registration|clearance|delivery|activation|release|admin|handling|insurance|verification)\s+fee\b|\bpay\b.{0,25}\bto (receive|release|claim|unlock|withdraw)\b""", 36,
            "Wants an upfront fee before you get anything (advance-fee scam)"),
        r("""\b(send|transfer|deposit|pay)\b\s+(me\s+)?(₦|n|ngn|#|\$|usd|gbp|£|€)?\s?\d[\d,.]*""", 18,
            "Asks you to send money"),
        r("""(double your|triple your|guaranteed (returns?|profits?)|100% (profits?|returns?|guaranteed)|risk[- ]free).{0,60}(invest|returns?|profits?|daily|weekly|money)|\b(forex|crypto|bitcoin|usdt|ponzi|mmm)\b.{0,60}\b(invest|returns?|profits?|daily|weekly)\b""", 32,
            "Too-good-to-be-true investment promise"),
        r("""\b(download|install)\b.{0,50}\b(apk|apps?|application)\b|\b(anydesk|teamviewer|quicksupport|rustdesk|ultraviewer)\b""", 30,
            "Wants you to install an app or give remote access to your phone"),
        r("""\b(gift ?cards?|itunes card|steam card|google play card|amazon card|vanilla card)\b""", 32,
            "Gift cards as payment is a textbook scam"),
        r("""\b(work from home|earn \S{0,6}\d[\d,]*\s?(daily|weekly|per (day|week))|loan (approved|offer)|instant loan|no collateral|part[- ]time job|data entry job)\b""", 20,
            "Too-easy money, job or loan offer"),
        r("""\b(dear (customer|user|client|beneficiary|winner|account holder|sir/?madam|valued)|valued customer)\b""", 10,
            "Generic greeting. Real banks use your name"),
        r("""\b(don'?t|do not|never) (tell|inform|share this with|let) (anyone|anybody|your (family|bank|wife|husband|friends?))\b|\bkeep (this|it) (secret|confidential)\b""", 20,
            "Tells you to keep it secret"),
        r("""\b(efcc|police|nnpc|firs|nimc|cbn|customs|immigration|interpol|fbi|irs)\b.{0,80}\b(arrest|warrant|case|investigation|fine|penalty|owe|seiz|summon)""", 26,
            "Pretends to be an agency and threatens you"),
        r("""\b(your (package|parcel|delivery|order)|shipment)\b.{0,60}\b(held|pending|failed|unable|could not|customs|reschedule)\b""", 18,
            "Fake delivery problem"),
        r("""\b(bvn|nin)\b.{0,40}\b(update|verify|link|validate|expire|suspend)""", 26,
            "Fake BVN/NIN update request")
    )

    private val NEGATED = Regex(
        """\b(do not|don'?t|never|please do not|kindly do not)\s+(share|give|disclose|send|provide|reveal|tell|forward)\b[^.!\n]{0,50}?\b(otp|pin|code|password|cvv|token|bvn|details)\b[^.!\n]{0,30}""",
        RegexOption.IGNORE_CASE
    )

    private val GOOD = listOf(
        Rule(Regex("""(do not|don'?t|never) share (this|your|the) (otp|code|pin)""", RegexOption.IGNORE_CASE), 14,
            "Includes the standard \"don't share this code\" warning that genuine alerts have"),
        Rule(Regex("""\b(acct|a/c|avail(able)? bal|bal:|credit alert|debit alert|txn)\b""", RegexOption.IGNORE_CASE), 10,
            "Reads like a normal bank transaction alert")
    )

    fun analyze(text: String): ScamVerdict {
        val f = ArrayList<Finding>()
        val body = text.trim()
        if (body.isEmpty()) return ScamVerdict(50, "Paste a message first", emptyList(), emptyList())
        // "never share your OTP" is a warning, not a request. Remove those phrases before looking for requests.
        val scanned = NEGATED.replace(body, " ")
        RULES.forEach { if (it.re.containsMatchIn(scanned)) f += Finding(it.why, it.delta) }

        val urls = SiteRules.extractUrls(body)
        val links = urls.map { SiteRules.analyze(it) }.filter { it.host.isNotEmpty() }
        if (links.isNotEmpty()) {
            f += Finding("Contains ${links.size} link${if (links.size > 1) "s" else ""}", -6)
            val worst = links.minByOrNull { it.score }!!
            when {
                worst.score < 45 -> f += Finding("A link in it looks dangerous (${worst.host})", -30)
                worst.score < 75 -> f += Finding("A link in it looks off (${worst.host})", -14)
            }
        }
        val hasLink = links.isNotEmpty()
        val hasAskOrThreat = f.any { it.delta <= -20 }
        GOOD.forEach { g ->
            if (g.re.containsMatchIn(body) && !hasAskOrThreat && !(g.why.contains("bank transaction") && hasLink))
                f += Finding(g.why, g.delta)
        }
        val score = (96 + f.sumOf { it.delta }).coerceIn(1, 100)
        val label = when { score >= 75 -> "Looks OK"; score >= 45 -> "Suspicious"; else -> "Likely a scam" }
        return ScamVerdict(score, label, f, links)
    }
}
