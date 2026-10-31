package com.toolkit.privacy

import kotlin.math.ln

/** Finds secrets and personal data inside text so the user can remove or mask them before sharing. All on-device. */
object SensitiveScan {
    class Rule(val id: String, val label: String, val severity: Int, val regex: Regex, val check: ((String) -> Boolean)? = null)
    data class Hit(val rule: String, val label: String, val severity: Int, val value: String, val line: Int, val start: Int, val end: Int) {
        fun masked(): String = if (value.length <= 8) "*".repeat(value.length) else value.take(3) + "*".repeat((value.length - 6).coerceAtMost(14)) + value.takeLast(3)
    }
    data class Report(val hits: List<Hit>, val risk: Int) {
        val safety get() = 100 - risk
        val level get() = when { risk >= 60 -> "Dangerous to share"; risk >= 30 -> "Sensitive"; risk >= 10 -> "Some personal data"; else -> "Looks safe" }
    }

    fun luhn(s: String): Boolean {
        val d = s.filter { it.isDigit() }
        if (d.length !in 13..19) return false
        var sum = 0; var alt = false
        for (i in d.length - 1 downTo 0) { var n = d[i] - '0'; if (alt) { n *= 2; if (n > 9) n -= 9 }; sum += n; alt = !alt }
        return sum % 10 == 0
    }

    fun entropy(s: String): Double {
        if (s.isEmpty()) return 0.0
        val f = s.groupingBy { it }.eachCount()
        return -f.values.sumOf { c -> val p = c / s.length.toDouble(); p * ln(p) / ln(2.0) }
    }

    private val rules: List<Rule> = listOf(
        Rule("privkey", "Private key", 3, Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----")),
        Rule("aws", "AWS access key", 3, Regex("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b")),
        Rule("google", "Google API key", 3, Regex("\\bAIza[0-9A-Za-z_\\-]{35}\\b")),
        Rule("github", "GitHub token", 3, Regex("\\bgh[pousr]_[A-Za-z0-9]{30,}\\b")),
        Rule("slack", "Slack token", 3, Regex("\\bxox[baprs]-[A-Za-z0-9-]{10,}\\b")),
        Rule("stripe", "Stripe / payment secret key", 3, Regex("\\b(?:sk|rk)_(?:live|test)_[0-9A-Za-z]{16,}\\b")),
        Rule("paystack", "Paystack / Flutterwave secret", 3, Regex("\\bFLWSECK(?:_TEST)?-[0-9a-zA-Z]{20,}\\b")),
        Rule("twilio", "Twilio key", 3, Regex("\\bSK[0-9a-fA-F]{32}\\b")),
        Rule("jwt", "JSON web token", 2, Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]*")),
        Rule("bearer", "Bearer token", 2, Regex("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{20,}")),
        Rule("dburl", "Database URL with password", 3, Regex("(?i)\\b[a-z][a-z0-9+.-]*://[^\\s:/@]+:[^\\s@/]+@[^\\s/]+")),
        Rule("password", "Password / secret assignment", 3, Regex("(?i)\\b(?:pass(?:word|wd)?|pwd|secret|api[_-]?key|token|auth)\\b\\s*[:=]\\s*[\"']?([^\\s\"',;]{4,})")),
        Rule("card", "Payment card number", 3, Regex("\\b(?:\\d[ -]?){13,19}\\b"), { luhn(it) }),
        Rule("bvn", "BVN / NIN (11 digits near label)", 3, Regex("(?i)\\b(?:bvn|nin|nimc)\\D{0,15}(\\d{11})\\b")),
        Rule("iban", "IBAN", 2, Regex("\\b[A-Z]{2}\\d{2}[A-Z0-9]{11,30}\\b")),
        Rule("email", "Email address", 1, Regex("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b")),
        Rule("phone", "Phone number", 1, Regex("(?<![\\w])(?:\\+?234|0)[789][01]\\d{8}(?!\\d)")),
        Rule("phoneint", "International phone number", 1, Regex("(?<![\\w])\\+[1-9]\\d{9,13}(?!\\d)")),
        Rule("ipv4", "IP address", 1, Regex("\\b(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\b")),
        Rule("eth", "Ethereum wallet address", 2, Regex("\\b0x[a-fA-F0-9]{40}\\b")),
        Rule("btc", "Bitcoin wallet address", 2, Regex("\\b(?:bc1[a-z0-9]{25,60}|[13][a-km-zA-HJ-NP-Z1-9]{25,34})\\b")),
        Rule("seed", "Possible recovery phrase (12/24 words)", 3, Regex("(?i)\\b(?:seed|mnemonic|recovery)\\b[^\\n]{0,20}[:=]?\\s*(?:[a-z]{3,8}\\s+){11,23}[a-z]{3,8}")),
        Rule("gps", "GPS coordinates", 1, Regex("(?<!\\d)-?\\d{1,2}\\.\\d{4,},\\s*-?\\d{1,3}\\.\\d{4,}(?!\\d)")),
        Rule("dob", "Date of birth", 1, Regex("(?i)\\b(?:dob|date of birth|born)\\b\\D{0,10}(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4})"))
    )

    fun scan(text: String): Report {
        val hits = ArrayList<Hit>()
        val lineStarts = ArrayList<Int>().apply { add(0); text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }
        fun lineOf(pos: Int): Int { var lo = 0; var hi = lineStarts.size - 1; while (lo < hi) { val m = (lo + hi + 1) / 2; if (lineStarts[m] <= pos) lo = m else hi = m - 1 }; return lo + 1 }
        for (r in rules) {
            for (m in r.regex.findAll(text)) {
                val g = if (m.groups.size > 1 && m.groups[1] != null) m.groups[1]!! else null
                val value = g?.value ?: m.value
                val start = g?.range?.first ?: m.range.first
                if (r.check != null && !r.check.invoke(value)) continue
                if (r.id == "ipv4" && (value.startsWith("0.") || value == "127.0.0.1" || value.startsWith("255."))) continue
                if (r.id == "password" && (value.length < 6 || value.equals("true", true) || value.equals("false", true) || value.startsWith("$") || value.startsWith("{"))) continue
                if (hits.any { it.start < start + value.length && start < it.end }) { if (r.severity < 3) continue }
                hits += Hit(r.id, r.label, r.severity, value, lineOf(start), start, start + value.length)
            }
        }
        // high-entropy tokens that no rule named
        Regex("[A-Za-z0-9+/_=-]{28,}").findAll(text).forEach { m ->
            if (hits.none { it.start < m.range.last + 1 && m.range.first < it.end } && entropy(m.value) > 4.3 && m.value.any { it.isDigit() } && m.value.any { it.isLetter() })
                hits += Hit("entropy", "Random-looking secret", 2, m.value, lineOf(m.range.first), m.range.first, m.range.last + 1)
        }
        val sorted = hits.sortedBy { it.start }
        val w = sorted.map { when (it.severity) { 3 -> 28; 2 -> 14; else -> 4 } }.sum()
        return Report(sorted, w.coerceIn(0, 100))
    }

    /** mode: "full" -> [REDACTED:TYPE], "partial" -> keeps first/last chars */
    fun redact(text: String, report: Report, mode: String = "full"): String {
        val sb = StringBuilder(); var pos = 0
        for (h in report.hits) {
            if (h.start < pos) continue
            sb.append(text, pos, h.start)
            sb.append(if (mode == "partial") h.masked() else "[REDACTED:${h.rule.uppercase()}]")
            pos = h.end
        }
        sb.append(text, pos, text.length)
        return sb.toString()
    }
}
