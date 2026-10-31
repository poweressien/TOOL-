package com.toolkit.privacy

import java.net.URLEncoder
import kotlin.math.abs

/** Pure price maths: barcode validation, outlier / scam-price detection, currency conversion, spending stats. No Android, no network. */
object PriceLogic {
    fun validBarcode(code: String): Boolean {
        if (code.length !in listOf(8, 12, 13, 14) || !code.all { it in '0'..'9' }) return false
        val d = code.map { it - '0' }
        var sum = 0
        for (k in 0 until d.size - 1) sum += d[d.size - 2 - k] * (if (k % 2 == 0) 3 else 1)
        return (10 - sum % 10) % 10 == d.last()
    }

    fun median(v: List<Double>): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sorted(); val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    fun mad(v: List<Double>): Double { val m = median(v); return median(v.map { abs(it - m) }) }

    data class Verdict(val score: Int, val label: String, val reasons: List<String>, val median: Double)

    private val textFlags: List<Triple<Regex, Int, String>> = listOf(
        Triple(Regex("western union|moneygram|gift ?card|crypto only|bitcoin only|wire transfer|zelle only|cash ?app only", RegexOption.IGNORE_CASE), 30, "asks for an untraceable payment method"),
        Triple(Regex("deposit|advance payment|pay first|upfront|pay before", RegexOption.IGNORE_CASE), 22, "wants money before you see the item"),
        Triple(Regex("whatsapp|telegram|text me|contact me (on|at)|dm me", RegexOption.IGNORE_CASE), 14, "pushes the chat off the platform"),
        Triple(Regex("outside (the )?(platform|app|site)|off the (app|platform)", RegexOption.IGNORE_CASE), 22, "wants to deal outside the platform"),
        Triple(Regex("hurry|act now|today only|last (one|piece|unit)|limited stock|only \\d+ left", RegexOption.IGNORE_CASE), 10, "pressure / urgency wording"),
        Triple(Regex("no returns?|no refunds?|as is|final sale", RegexOption.IGNORE_CASE), 8, "no returns or refunds"),
        Triple(Regex("100% (original|authentic|genuine|new)|brand new sealed|not fake", RegexOption.IGNORE_CASE), 8, "over-insisting it is genuine"),
        Triple(Regex("i('m| am) (travelling|traveling|abroad|overseas|relocating|in the army)|shipping agent|courier will", RegexOption.IGNORE_CASE), 18, "common seller backstory used in scams")
    )

    fun judge(price: Double, comps: List<Double>, text: String = ""): Verdict {
        var score = 88
        val why = ArrayList<String>()
        val clean = comps.filter { it > 0 }
        val med = median(clean)
        if (clean.size >= 3 && med > 0) {
            val r = price / med
            val m = mad(clean)
            when {
                r < 0.40 -> { score -= 50; why += "Only ${(r * 100).toInt()}% of the typical price: classic scam bait" }
                r < 0.60 -> { score -= 28; why += "${((1 - r) * 100).toInt()}% below the typical price: too good to be true" }
                r < 0.80 -> { score -= 6; why += "Noticeably below market (${((1 - r) * 100).toInt()}% under): check condition and seller" }
                r > 2.2 -> { score -= 38; why += "${"%.1f".format(r)}x the typical price: heavily overpriced" }
                r > 1.5 -> { score -= 22; why += "${((r - 1) * 100).toInt()}% above the typical price: overpriced" }
                r > 1.2 -> { score -= 8; why += "${((r - 1) * 100).toInt()}% above the typical price" }
                else -> why += "In line with the market (typical ${"%.2f".format(med)})"
            }
            if (m > 0) {
                val z = 0.6745 * (price - med) / m
                if (abs(z) > 3.5) { score -= 10; why += "Statistical outlier vs ${clean.size} comparable offers (z=${"%.1f".format(z)})" }
            }
        } else why += "Not enough comparable prices (${clean.size}) to judge the price itself: add more or search stores"
        for ((re, pen, label) in textFlags) if (re.containsMatchIn(text)) { score -= pen; why += "Listing text: $label" }
        score = score.coerceIn(2, 98)
        val label = when { score >= 75 -> "Looks fair"; score >= 50 -> "Be careful"; else -> "Suspicious" }
        return Verdict(score, label, why, med)
    }

    /** Stores to open a search in. {q} is replaced by the URL-encoded query. */
    data class Store(val name: String, val url: String, val region: String)

    val stores = listOf(
        Store("Google Shopping", "https://www.google.com/search?tbm=shop&q={q}", "Global"),
        Store("Amazon", "https://www.amazon.com/s?k={q}", "Global"),
        Store("eBay", "https://www.ebay.com/sch/i.html?_nkw={q}", "Global"),
        Store("AliExpress", "https://www.aliexpress.com/wholesale?SearchText={q}", "Global"),
        Store("Temu", "https://www.temu.com/search_result.html?search_key={q}", "Global"),
        Store("Walmart", "https://www.walmart.com/search?q={q}", "US"),
        Store("Best Buy", "https://www.bestbuy.com/site/searchpage.jsp?st={q}", "US"),
        Store("Jumia", "https://www.jumia.com.ng/catalog/?q={q}", "Nigeria"),
        Store("Konga", "https://www.konga.com/search?search={q}", "Nigeria"),
        Store("Jiji", "https://jiji.ng/search?query={q}", "Nigeria"),
        Store("Slot", "https://slot.ng/?s={q}&post_type=product", "Nigeria"),
        Store("Takealot", "https://www.takealot.com/all?qsearch={q}", "South Africa"),
        Store("Amazon UK", "https://www.amazon.co.uk/s?k={q}", "UK"),
        Store("Idealo", "https://www.idealo.co.uk/mscat.html?q={q}", "UK/EU"),
        Store("Flipkart", "https://www.flipkart.com/search?q={q}", "India")
    )

    fun storeLink(s: Store, q: String): String = s.url.replace("{q}", URLEncoder.encode(q, "UTF-8"))

    // ---- currencies

    /** rates: units of currency per 1 USD. */
    fun convert(amount: Double, from: String, to: String, rates: Map<String, Double>): Double? {
        val a = rates[from.uppercase()] ?: return null
        val b = rates[to.uppercase()] ?: return null
        if (a == 0.0) return null
        return amount / a * b
    }

    val fiatGroups: Map<String, List<String>> = linkedMapOf(
        "Major" to listOf("USD", "EUR", "GBP", "JPY", "CHF", "CAD", "AUD", "NZD", "CNY"),
        "Africa" to listOf("NGN", "GHS", "ZAR", "KES", "EGP", "MAD", "TZS", "UGX", "XOF", "XAF", "ETB", "RWF", "ZMW", "DZD", "TND"),
        "Asia" to listOf("INR", "PKR", "BDT", "IDR", "PHP", "THB", "VND", "MYR", "SGD", "KRW", "HKD", "TWD", "LKR"),
        "Americas" to listOf("MXN", "BRL", "ARS", "CLP", "COP", "PEN", "CAD", "USD"),
        "Middle East" to listOf("AED", "SAR", "QAR", "KWD", "ILS", "TRY", "JOD", "OMR"),
        "Europe" to listOf("EUR", "GBP", "CHF", "SEK", "NOK", "DKK", "PLN", "CZK", "HUF", "RON", "UAH", "RUB"),
        "Metals" to listOf("XAU", "XAG", "XPT", "XPD")
    )

    val cryptoCategories = listOf(
        "All" to "", "Layer 1" to "layer-1", "Layer 2" to "layer-2", "DeFi" to "decentralized-finance-defi",
        "Stablecoins" to "stablecoins", "Meme" to "meme-token", "AI" to "artificial-intelligence", "Gaming" to "gaming",
        "Exchange tokens" to "exchange-based-tokens", "Privacy" to "privacy-coins", "Oracles" to "oracle",
        "RWA" to "real-world-assets-rwa", "NFT" to "non-fungible-tokens-nft"
    )

    fun money(v: Double, cur: String): String {
        val big = abs(v) >= 1000
        val n = when { big -> "%,.0f".format(v); abs(v) >= 1 -> "%,.2f".format(v); abs(v) >= 0.01 -> "%.4f".format(v); else -> "%.8f".format(v).trimEnd('0') }
        return "$n $cur"
    }

    // ---- spending

    data class Spend(val t: Long, val amount: Double, val cur: String, val cat: String, val note: String)

    fun totalsByCategory(items: List<Spend>, since: Long, toCur: String, rates: Map<String, Double>): List<Pair<String, Double>> =
        items.filter { it.t >= since }
            .groupBy { it.cat }
            .map { (c, l) -> c to l.map { convert(it.amount, it.cur, toCur, rates) ?: it.amount }.sum() }
            .sortedByDescending { it.second }

    /** Sum per day for the last n days (oldest first). */
    fun dailyTotals(items: List<Spend>, days: Int, toCur: String, rates: Map<String, Double>, now: Long = System.currentTimeMillis()): List<Double> {
        val day = 86_400_000L
        val start = now - days * day
        val out = DoubleArray(days)
        items.filter { it.t >= start && it.t <= now }.forEach {
            val idx = ((it.t - start) / day).toInt().coerceIn(0, days - 1)
            out[idx] += convert(it.amount, it.cur, toCur, rates) ?: it.amount
        }
        return out.toList()
    }

    data class Pattern(val total: Double, val perDay: Double, val projectedMonth: Double, val prevTotal: Double, val changePct: Double?, val topCat: String?, val biggest: Spend?)

    fun pattern(items: List<Spend>, days: Int, toCur: String, rates: Map<String, Double>, now: Long = System.currentTimeMillis()): Pattern {
        val day = 86_400_000L
        val cur = items.filter { it.t >= now - days * day }
        val prev = items.filter { it.t >= now - 2 * days * day && it.t < now - days * day }
        fun sum(l: List<Spend>) = l.map { convert(it.amount, it.cur, toCur, rates) ?: it.amount }.sum()
        val total = sum(cur); val pt = sum(prev)
        val per = total / days.coerceAtLeast(1)
        val top = totalsByCategory(items, now - days * day, toCur, rates).firstOrNull()?.first
        val big = cur.maxByOrNull { convert(it.amount, it.cur, toCur, rates) ?: it.amount }
        return Pattern(total, per, per * 30, pt, if (pt > 0) (total - pt) / pt * 100 else null, top, big)
    }

    // ---- tracking

    data class Point(val t: Long, val price: Double)

    fun priceTrend(points: List<Point>): String {
        if (points.size < 2) return "Not enough history yet"
        val first = points.first().price; val last = points.last().price
        val low = points.minOf { it.price }; val high = points.maxOf { it.price }
        val ch = if (first > 0) (last - first) / first * 100 else 0.0
        val state = when { last <= low * 1.001 -> "at its lowest recorded price"; last >= high * 0.999 -> "at its highest recorded price"; else -> "between ${"%.2f".format(low)} and ${"%.2f".format(high)}" }
        return "${if (ch >= 0) "+" else ""}${"%.1f".format(ch)}% since first seen · $state"
    }

    fun hitTarget(current: Double, target: Double): Boolean = target > 0 && current > 0 && current <= target
}
