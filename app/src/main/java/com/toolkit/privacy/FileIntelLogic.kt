package com.toolkit.privacy

import java.util.Calendar
import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/** Pure logic for the Smart Files tab: categories, natural-language search, outliers, ZIP planning, PDF text + entity extraction. */
object FileIntelLogic {
    enum class Cat(val label: String, val icon: String) {
        IMAGES("Images", "🖼"), VIDEO("Videos", "🎬"), AUDIO("Audio", "🎵"), PDF("PDFs", "📕"), DOCS("Documents", "📄"),
        SHEETS("Spreadsheets", "📊"), SLIDES("Presentations", "📽"), ARCHIVE("Archives", "🗜"), APK("Apps (APK)", "📦"), CODE("Code & data", "💻"), OTHER("Other", "📁")
    }

    private val extMap: Map<String, Cat> = buildMap {
        fun add(c: Cat, s: String) = s.split(' ').forEach { put(it, c) }
        add(Cat.IMAGES, "jpg jpeg png gif webp heic heif bmp svg dng raw tif tiff ico")
        add(Cat.VIDEO, "mp4 mkv avi mov webm 3gp flv wmv m4v mpg mpeg")
        add(Cat.AUDIO, "mp3 wav aac m4a ogg opus flac amr wma mid")
        add(Cat.PDF, "pdf")
        add(Cat.DOCS, "doc docx odt rtf txt md epub pages")
        add(Cat.SHEETS, "xls xlsx ods csv tsv numbers")
        add(Cat.SLIDES, "ppt pptx odp key")
        add(Cat.ARCHIVE, "zip rar 7z tar gz xz bz2 tgz")
        add(Cat.APK, "apk xapk apks aab")
        add(Cat.CODE, "kt java js ts py html htm css json xml sh c cpp h cs go rs php sql yml yaml log ini cfg")
    }

    fun human(b: Long): String = when { b >= 1L shl 30 -> "%.1f GB".format(b / 1073741824.0); b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0); b >= 1024 -> "%.0f KB".format(b / 1024.0); else -> "$b B" }
    fun ext(name: String): String = name.substringAfterLast('.', "").lowercase()
    fun categorize(name: String): Cat = extMap[ext(name)] ?: Cat.OTHER

    fun mimeFor(name: String): String = when (ext(name)) {
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"; "pdf" -> "application/pdf"
        "mp4" -> "video/mp4"; "mkv" -> "video/x-matroska"; "mp3" -> "audio/mpeg"; "wav" -> "audio/wav"; "txt", "log", "md" -> "text/plain"
        "csv" -> "text/csv"; "html", "htm" -> "text/html"; "json" -> "application/json"; "zip" -> "application/zip"; "apk" -> "application/vnd.android.package-archive"
        "doc" -> "application/msword"; "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        else -> "*/*"
    }

    // ------------------------------------------------------------------------------------------ natural-language search

    data class Query(val cats: Set<Cat>, val exts: Set<String>, val minSize: Long?, val maxSize: Long?, val after: Long?, val before: Long?,
                     val words: List<String>, val content: List<String>, val describe: String)

    private val stop = setOf("the", "a", "an", "my", "me", "of", "in", "on", "at", "to", "for", "from", "and", "or", "with", "that", "this", "all", "any", "some", "files", "file",
        "find", "show", "search", "get", "look", "looking", "where", "is", "are", "was", "were", "i", "have", "had", "named", "called", "than", "which", "those", "these", "new", "old")

    private val catWords: Map<String, List<Cat>> = mapOf(
        "photo" to listOf(Cat.IMAGES), "picture" to listOf(Cat.IMAGES), "image" to listOf(Cat.IMAGES), "screenshot" to listOf(Cat.IMAGES), "selfie" to listOf(Cat.IMAGES),
        "video" to listOf(Cat.VIDEO), "movie" to listOf(Cat.VIDEO), "clip" to listOf(Cat.VIDEO),
        "music" to listOf(Cat.AUDIO), "song" to listOf(Cat.AUDIO), "audio" to listOf(Cat.AUDIO), "voice" to listOf(Cat.AUDIO), "recording" to listOf(Cat.AUDIO),
        "pdf" to listOf(Cat.PDF), "document" to listOf(Cat.DOCS, Cat.PDF), "doc" to listOf(Cat.DOCS, Cat.PDF), "word" to listOf(Cat.DOCS), "letter" to listOf(Cat.DOCS, Cat.PDF),
        "essay" to listOf(Cat.DOCS, Cat.PDF), "note" to listOf(Cat.DOCS), "spreadsheet" to listOf(Cat.SHEETS), "excel" to listOf(Cat.SHEETS), "sheet" to listOf(Cat.SHEETS),
        "presentation" to listOf(Cat.SLIDES), "slide" to listOf(Cat.SLIDES), "powerpoint" to listOf(Cat.SLIDES),
        "archive" to listOf(Cat.ARCHIVE), "zip" to listOf(Cat.ARCHIVE), "apk" to listOf(Cat.APK), "app" to listOf(Cat.APK),
        "code" to listOf(Cat.CODE), "script" to listOf(Cat.CODE)
    )
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private const val DAY = 86_400_000L

    private fun unit(n: Double, u: String): Long = when (u.lowercase().first()) { 'k' -> (n * 1024).toLong(); 'm' -> (n * 1024 * 1024).toLong(); 'g' -> (n * 1024 * 1024 * 1024).toLong(); else -> n.toLong() }
    private fun startOfDay(t: Long): Long = Calendar.getInstance().apply { timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    private fun cal(t: Long) = Calendar.getInstance().apply { timeInMillis = t }

    fun parse(input: String, now: Long = System.currentTimeMillis()): Query {
        var q = " " + input.lowercase().trim() + " "
        val cats = LinkedHashSet<Cat>(); val exts = LinkedHashSet<String>(); val desc = ArrayList<String>()
        var min: Long? = null; var max: Long? = null; var after: Long? = null; var before: Long? = null
        val content = ArrayList<String>()

        Regex("\\s(?:containing|mentioning|that says|which says|with the words?|saying|about)\\s+(.+)$").find(q)?.let { m ->
            content += m.groupValues[1].trim().split(Regex("\\s+")).filter { it.length > 1 && it !in stop }
            q = q.substring(0, m.range.first) + " "
            if (content.isNotEmpty()) desc += "text contains: ${content.joinToString(" ")}"
        }
        fun take(re: Regex, f: (MatchResult) -> Unit) { re.find(q)?.let { f(it); q = q.replace(it.value, " ") } }

        take(Regex("\\s(?:larger|bigger|greater|more|over|above|>)\\s*(?:than\\s+)?([0-9.]+)\\s*(kb|mb|gb|k|m|g)\\b")) { min = unit(it.groupValues[1].toDouble(), it.groupValues[2]); desc += "size > ${human(min!!)}" }
        take(Regex("\\s(?:smaller|less|under|below|<)\\s*(?:than\\s+)?([0-9.]+)\\s*(kb|mb|gb|k|m|g)\\b")) { max = unit(it.groupValues[1].toDouble(), it.groupValues[2]); desc += "size < ${human(max!!)}" }
        take(Regex("\\s(?:huge|massive|very big|very large)\\s")) { min = 200L * 1024 * 1024; desc += "huge (> 200 MB)" }
        take(Regex("\\s(?:big|large|heavy)\\s")) { if (min == null) { min = 20L * 1024 * 1024; desc += "large (> 20 MB)" } }
        take(Regex("\\s(?:tiny|small)\\s")) { if (max == null) { max = 200L * 1024; desc += "small (< 200 KB)" } }

        val today = startOfDay(now)
        take(Regex("\\s(?:older|more)\\s+than\\s+(\\d+)\\s*(day|week|month|year)s?(?:\\s+old)?\\s")) {
            val n = it.groupValues[1].toInt(); val span = when (it.groupValues[2]) { "day" -> 1L; "week" -> 7L; "month" -> 30L; else -> 365L }
            before = now - n * span * DAY; desc += "older than $n ${it.groupValues[2]}(s)"
        }
        take(Regex("\\s(?:in\\s+)?(?:the\\s+)?(?:last|past)\\s+(\\d+)\\s*(day|week|month|year)s?\\s")) {
            val n = it.groupValues[1].toInt(); val span = when (it.groupValues[2]) { "day" -> 1L; "week" -> 7L; "month" -> 30L; else -> 365L }
            after = now - n * span * DAY; desc += "last $n ${it.groupValues[2]}(s)"
        }
        take(Regex("\\syesterday\\s")) { after = today - DAY; before = today; desc += "yesterday" }
        take(Regex("\\stoday\\s")) { after = today; desc += "today" }
        take(Regex("\\s(?:recent|recently|lately)\\s")) { after = now - 7 * DAY; desc += "last 7 days" }
        take(Regex("\\sthis week\\s")) { after = now - 7 * DAY; desc += "this week" }
        take(Regex("\\slast week\\s")) { after = now - 14 * DAY; before = now - 7 * DAY; desc += "last week" }
        take(Regex("\\sthis month\\s")) { after = cal(now).apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis; desc += "this month" }
        take(Regex("\\slast month\\s")) {
            val c = cal(now).apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
            before = c.timeInMillis; c.add(Calendar.MONTH, -1); after = c.timeInMillis; desc += "last month"
        }
        take(Regex("\\sthis year\\s")) { after = cal(now).apply { set(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis; desc += "this year" }
        take(Regex("\\slast year\\s")) {
            val y = cal(now).get(Calendar.YEAR) - 1
            after = Calendar.getInstance().apply { clear(); set(y, 0, 1) }.timeInMillis; before = Calendar.getInstance().apply { clear(); set(y + 1, 0, 1) }.timeInMillis; desc += "last year"
        }
        take(Regex("\\s(?:in\\s+|from\\s+)?(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\s+(20\\d\\d)\\s")) {
            val m = months.indexOf(it.groupValues[1]); val y = it.groupValues[2].toInt()
            after = Calendar.getInstance().apply { clear(); set(y, m, 1) }.timeInMillis; before = Calendar.getInstance().apply { clear(); set(y, m + 1, 1) }.timeInMillis; desc += "${it.groupValues[1]} $y"
        }
        take(Regex("\\s(?:in\\s+|from\\s+)?(20\\d\\d)\\s")) {
            val y = it.groupValues[1].toInt()
            after = Calendar.getInstance().apply { clear(); set(y, 0, 1) }.timeInMillis; before = Calendar.getInstance().apply { clear(); set(y + 1, 0, 1) }.timeInMillis; desc += "year $y"
        }
        take(Regex("\\sin\\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\s")) {
            val m = months.indexOf(it.groupValues[1]); val c = cal(now); var y = c.get(Calendar.YEAR); if (m > c.get(Calendar.MONTH)) y--
            after = Calendar.getInstance().apply { clear(); set(y, m, 1) }.timeInMillis; before = Calendar.getInstance().apply { clear(); set(y, m + 1, 1) }.timeInMillis; desc += "${it.groupValues[1]} $y"
        }

        val words = ArrayList<String>()
        for (raw in q.split(Regex("[^a-z0-9.+_-]+")).filter { it.isNotEmpty() }) {
            if (raw.startsWith(".") && raw.length > 1 && raw.trimStart('.').all { it.isLetterOrDigit() }) { exts += raw.trimStart('.'); continue }
            val w = raw.trim('.', '-', '_')
            if (w.isEmpty()) continue
            if (w.length in 2..5 && extMap.containsKey(w) && catWords[w] == null) { exts += w; continue }
            val stem = if (w.endsWith("ies") && w.length > 4) w.dropLast(3) + "y" else if (w.endsWith("s") && w.length > 3) w.dropLast(1) else w
            val cw = catWords[stem] ?: catWords[w]
            if (cw != null) {
                cats += cw
                if (stem == "screenshot") words += "screenshot"
                continue
            }
            if (w in stop || w.length < 2) continue
            words += w
        }
        if (cats.isNotEmpty()) desc.add(0, cats.joinToString("/") { it.label })
        if (exts.isNotEmpty()) desc += ".${exts.joinToString(", .")}"
        if (words.isNotEmpty()) desc += "name has: ${words.joinToString(" ")}"
        return Query(cats, exts, min, max, after, before, words, content, if (desc.isEmpty()) "everything" else desc.joinToString(" · "))
    }

    fun matches(q: Query, name: String, size: Long, modified: Long, cat: Cat): Boolean {
        if (q.cats.isNotEmpty() && cat !in q.cats) return false
        if (q.exts.isNotEmpty() && ext(name) !in q.exts) return false
        if (q.minSize != null && size < q.minSize) return false
        if (q.maxSize != null && size > q.maxSize) return false
        if (q.after != null && modified < q.after) return false
        if (q.before != null && modified >= q.before) return false
        val n = name.lowercase()
        return q.words.all { n.contains(it) || n.contains(it.removeSuffix("s")) }
    }

    // ------------------------------------------------------------------------------------------ outliers

    data class Odd(val index: Int, val reason: String)

    /** items: (category, size). Flags files that are far larger than is normal for their type, plus anything gigantic. */
    fun unusuallyLarge(items: List<Pair<Cat, Long>>): List<Odd> {
        val out = HashMap<Int, String>()
        val byCat = items.indices.groupBy { items[it].first }
        for ((cat, idx) in byCat) {
            val logs = idx.map { ln(items[it].second.coerceAtLeast(1L).toDouble()) }
            val sizes = idx.map { items[it].second.toDouble() }
            val med = PriceLogic.median(logs); val m = PriceLogic.mad(logs).coerceAtLeast(0.25)
            val medSize = PriceLogic.median(sizes)
            for (i in idx) {
                val s = items[i].second
                if (s >= 1L shl 30) { out[i] = "Over 1 GB"; continue }
                if (idx.size >= 8) {
                    val z = 0.6745 * (ln(s.coerceAtLeast(1L).toDouble()) - med) / m
                    if (z > 3.5 && s >= 20L * 1024 * 1024) out[i] = "${"%.0f".format(s / medSize.coerceAtLeast(1.0))}x bigger than a typical ${cat.label.lowercase().removeSuffix("s")}"
                } else if (s >= 200L * 1024 * 1024) out[i] = "Very large for ${cat.label.lowercase()}"
            }
        }
        return out.map { Odd(it.key, it.value) }.sortedByDescending { items[it.index].second }
    }

    // ------------------------------------------------------------------------------------------ smart zip

    private val alreadyCompressed = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "mp4", "mkv", "mov", "webm", "3gp", "mp3", "aac", "m4a", "ogg", "opus", "flac",
        "zip", "rar", "7z", "gz", "xz", "bz2", "tgz", "apk", "docx", "xlsx", "pptx", "odt", "epub")

    /** True when deflating would waste time and gain nothing, so the entry should be stored. */
    fun shouldStore(name: String): Boolean = ext(name) in alreadyCompressed

    fun estimateRatio(name: String, cat: Cat): Double = when {
        shouldStore(name) -> 1.0
        cat == Cat.CODE -> 0.22; cat == Cat.SHEETS -> 0.30; cat == Cat.DOCS -> 0.45; cat == Cat.PDF -> 0.88; cat == Cat.IMAGES -> 0.80; cat == Cat.AUDIO -> 0.92
        cat == Cat.VIDEO -> 0.99; else -> 0.65
    }

    data class ZipItem(val name: String, val cat: Cat, val size: Long, val modified: Long, val folder: String)

    /** Returns archive name -> indices. strategy: "One archive", "By type", "By month", "By folder". */
    fun planZips(items: List<ZipItem>, strategy: String): Map<String, List<Int>> {
        val out = LinkedHashMap<String, MutableList<Int>>()
        items.forEachIndexed { i, it ->
            val key = when (strategy) {
                "By type" -> it.cat.label.replace(' ', '_').replace("(", "").replace(")", "")
                "By month" -> { val c = cal(it.modified); "%04d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1) }
                "By folder" -> it.folder.ifEmpty { "root" }.replace(Regex("[^A-Za-z0-9._-]+"), "_")
                else -> "archive"
            }
            out.getOrPut(key) { ArrayList() }.add(i)
        }
        return out
    }

    // ------------------------------------------------------------------------------------------ entities + summary

    private val months3 = "Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec"
    private val entityRes: List<Pair<String, Regex>> = listOf(
        "Emails" to Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
        "Links" to Regex("https?://[^\\s)>\\]]+"),
        "Dates" to Regex("\\b(?:\\d{4}-\\d{2}-\\d{2}|\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4}|\\d{1,2}(?:st|nd|rd|th)?\\s(?:$months3)[a-z]*\\.?,?\\s\\d{4}|(?:$months3)[a-z]*\\.?\\s\\d{1,2}(?:st|nd|rd|th)?,?\\s\\d{4})\\b", RegexOption.IGNORE_CASE),
        "Amounts" to Regex("(?:[$€£₦¥₹]|\\b(?:NGN|USD|EUR|GBP|N)\\s?)\\s?\\d[\\d,]*(?:\\.\\d+)?|\\b\\d[\\d,]*(?:\\.\\d+)?\\s?(?:USD|NGN|EUR|GBP|naira|dollars)\\b", RegexOption.IGNORE_CASE),
        "Reference numbers" to Regex("\\b(?:invoice|inv|receipt|order|ref|reference|po|txn|transaction|account|acct|policy|ticket)\\s*(?:no\\.?|number|#|id)?\\s*[:#-]?\\s*[A-Z0-9][A-Z0-9-]{3,}\\b", RegexOption.IGNORE_CASE),
        "Percentages" to Regex("\\b\\d{1,3}(?:\\.\\d+)?\\s?%")
    )
    private val phoneRe = Regex("(?<![\\d])\\+?\\d[\\d\\s().-]{7,17}\\d(?![\\d])")

    fun entities(text: String): Map<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        for ((k, re) in entityRes) { val l = re.findAll(text).map { it.value.trim().trimEnd('.', ',') }.distinct().take(25).toList(); if (l.isNotEmpty()) out[k] = l }
        val ph = phoneRe.findAll(text).map { it.value.trim() }.filter { v -> v.count { it.isDigit() } in 9..15 && !Regex("^\\d{4}-\\d{2}-\\d{2}").containsMatchIn(v) && !v.contains(Regex("\\d{1,2}[/.]\\d{1,2}[/.]\\d{2,4}")) }.distinct().take(15).toList()
        if (ph.isNotEmpty()) out["Phone numbers"] = ph
        return out
    }

    fun guessDocType(text: String): String {
        val t = text.lowercase()
        val kinds = listOf(
            "Invoice / receipt" to listOf("invoice", "receipt", "subtotal", "amount due", "total due", "vat", "bill to", "paid"),
            "Bank / account statement" to listOf("statement of account", "opening balance", "closing balance", "debit", "credit", "transaction"),
            "Contract / agreement" to listOf("agreement", "hereby", "party", "terms and conditions", "witness", "clause", "signed"),
            "CV / resume" to listOf("curriculum vitae", "resume", "experience", "education", "skills", "references"),
            "Certificate / letter" to listOf("certificate", "certify", "dear sir", "dear madam", "yours faithfully", "sincerely"),
            "Report / paper" to listOf("abstract", "introduction", "conclusion", "references", "methodology", "figure"),
            "Ticket / booking" to listOf("boarding", "booking", "reservation", "ticket", "passenger", "check-in")
        )
        val best = kinds.map { (n, ks) -> n to ks.count { t.contains(it) } }.maxByOrNull { it.second }
        return if (best != null && best.second >= 2) best.first else "General document"
    }

    private val sw = ("the be to of and a in that have it for not on with he as you do at this but his by from they we say her she or an will my one all would there their what so up out if about who get which go me when make can like time no just him know take people into year your good some could them see other than then now look only come its over think also back after use two how our work first well way even new want because any these give day most us is are was were been has had did does").split(' ').toSet()

    fun keywords(text: String, n: Int = 8): List<String> =
        Regex("[A-Za-z][A-Za-z'-]{3,}").findAll(text.lowercase()).map { it.value }.filter { it !in sw }.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.take(n).map { it.key }

    /** Extractive summary: the n most keyword-dense sentences, in original order. */
    fun summarize(text: String, n: Int = 3): List<String> {
        val sentences = text.replace(Regex("\\s+"), " ").split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.length in 30..400 }
        if (sentences.size <= n) return sentences
        val freq = Regex("[A-Za-z]{4,}").findAll(text.lowercase()).map { it.value }.filter { it !in sw }.groupingBy { it }.eachCount()
        val scored = sentences.mapIndexed { i, s ->
            val ws = Regex("[A-Za-z]{4,}").findAll(s.lowercase()).map { it.value }.filter { it !in sw }.toList()
            Triple(i, s, if (ws.isEmpty()) 0.0 else ws.map { freq[it] ?: 0 }.sum() / sqrt(ws.size.toDouble()))
        }
        return scored.sortedByDescending { it.third }.take(n).sortedBy { it.first }.map { it.second }
    }
}

/** Minimal dependency-free PDF reader: metadata + text from Flate-compressed content streams. Scanned or exotic-font PDFs fall back to OCR in the UI. */
object PdfText {
    data class Info(val pages: Int, val text: String, val meta: Map<String, String>, val encrypted: Boolean) {
        /** Heuristic: real text, not a handful of stray glyphs. */
        val usable: Boolean get() = text.count { it.isLetter() } >= 40 && text.count { it.isLetter() }.toDouble() / text.length.coerceAtLeast(1) > 0.4
    }

    private fun inflate(b: ByteArray): ByteArray? = try {
        val inf = Inflater(); inf.setInput(b)
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
        while (!inf.finished()) { val n = inf.inflate(buf); if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break; out.write(buf, 0, n); if (out.size() > 20_000_000) break }
        inf.end(); out.toByteArray()
    } catch (e: Exception) { null }

    private fun unescape(s: String): String {
        val sb = StringBuilder(); var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val n = s[i + 1]
                when (n) {
                    'n' -> { sb.append('\n'); i += 2 }; 'r' -> { i += 2 }; 't' -> { sb.append(' '); i += 2 }; 'b', 'f' -> { i += 2 }
                    '(', ')', '\\' -> { sb.append(n); i += 2 }
                    in '0'..'7' -> { var j = i + 1; var v = 0; var k = 0; while (j < s.length && k < 3 && s[j] in '0'..'7') { v = v * 8 + (s[j] - '0'); j++; k++ }; sb.append((v and 0xFF).toChar()); i = j }
                    '\n' -> i += 2
                    else -> { sb.append(n); i += 2 }
                }
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    private fun hexString(h: String): String {
        val c = h.filter { it.isLetterOrDigit() }.let { if (it.length % 2 == 1) it + "0" else it }
        val bytes = c.chunked(2).mapNotNull { it.toIntOrNull(16) }
        if (bytes.size >= 4 && bytes.size % 2 == 0 && (0 until bytes.size step 2).all { bytes[it] == 0 }) return (1 until bytes.size step 2).map { bytes[it].toChar() }.joinToString("")
        return bytes.map { it.toChar() }.joinToString("")
    }

    /** Pulls text out of one decoded content stream. */
    fun contentText(c: String): String {
        val out = StringBuilder()
        var i = 0; val n = c.length
        var nums = ArrayList<Double>()
        fun nl() { if (out.isNotEmpty() && out.last() != '\n') out.append('\n') }
        while (i < n) {
            val ch = c[i]
            when {
                ch == '(' -> {
                    var depth = 1; var j = i + 1; val sb = StringBuilder()
                    while (j < n && depth > 0) {
                        val d = c[j]
                        if (d == '\\' && j + 1 < n) { sb.append(d).append(c[j + 1]); j += 2; continue }
                        if (d == '(') depth++ else if (d == ')') { depth--; if (depth == 0) break }
                        sb.append(d); j++
                    }
                    out.append(unescape(sb.toString())); i = j + 1; nums.clear()
                }
                ch == '<' && i + 1 < n && c[i + 1] != '<' -> {
                    val e = c.indexOf('>', i); if (e < 0) { i = n } else { out.append(hexString(c.substring(i + 1, e))); i = e + 1 }
                }
                ch == '[' || ch == ']' -> { i++ }
                ch == '-' || ch == '+' || ch == '.' || ch.isDigit() -> {
                    var j = i + 1; while (j < n && (c[j].isDigit() || c[j] == '.')) j++
                    val v = c.substring(i, j).toDoubleOrNull()
                    if (v != null) { nums.add(v); if (v < -250 && out.isNotEmpty() && out.last() != ' ' && out.last() != '\n') out.append(' ') }
                    i = j
                }
                ch.isLetter() || ch == '\'' || ch == '"' || ch == '*' -> {
                    var j = i; while (j < n && (c[j].isLetter() || c[j] == '*' || c[j] == '\'' || c[j] == '"')) j++
                    when (c.substring(i, j)) {
                        "Td", "TD" -> { if (nums.size >= 2 && abs(nums[nums.size - 1]) > 0.01) nl() else if (out.isNotEmpty() && out.last() != ' ' && out.last() != '\n') out.append(' ') }
                        "T*", "'", "\"", "ET", "Tm" -> nl()
                    }
                    nums.clear(); i = j
                }
                else -> i++
            }
        }
        return out.toString()
    }

    fun extract(bytes: ByteArray, maxChars: Int = 200_000): Info {
        val s = String(bytes, Charsets.ISO_8859_1)
        val encrypted = s.contains("/Encrypt")
        val pages = Regex("/Type\\s*/Page(?![s\\w])").findAll(s).count()
        val meta = LinkedHashMap<String, String>()
        for (k in listOf("Title", "Author", "Subject", "Creator", "Producer", "CreationDate", "ModDate")) {
            Regex("/$k\\s*\\(((?:\\\\.|[^)\\\\])*)\\)").find(s)?.let { m ->
                var v = unescape(m.groupValues[1]).trim()
                if (v.startsWith("\u00fe\u00ff")) v = v.drop(2).filter { it.code != 0 }
                if (k.endsWith("Date") && v.startsWith("D:") && v.length >= 10) v = "${v.substring(2, 6)}-${v.substring(6, 8)}-${v.substring(8, 10)}"
                if (v.isNotBlank()) meta[k] = v
            }
        }
        val text = StringBuilder()
        if (!encrypted) {
            var pos = 0
            while (text.length < maxChars) {
                val st = s.indexOf("stream", pos); if (st < 0) break
                var start = st + 6
                if (start < s.length && s[start] == '\r') start++
                if (start < s.length && s[start] == '\n') start++
                val en = s.indexOf("endstream", start); if (en < 0) break
                val dict = s.substring(maxOf(0, st - 500), st).substringAfterLast("<<", "")
                pos = en + 9
                if (dict.contains("/Image") || dict.contains("/ObjStm") || dict.contains("/XRef") || dict.contains("/FontFile") || dict.contains("/Length1")) continue
                val raw = bytes.copyOfRange(start, en)
                val data = if (dict.contains("FlateDecode")) inflate(raw) ?: continue else raw
                val str = String(data, Charsets.ISO_8859_1)
                if (str.contains("begincmap") || !(str.contains("BT") && (str.contains("Tj") || str.contains("TJ")))) continue
                val t = contentText(str)
                if (t.isNotBlank()) { text.append(t); if (!t.endsWith("\n")) text.append('\n'); text.append('\n') }
            }
        }
        return Info(pages, text.toString().replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim().take(maxChars), meta, encrypted)
    }
}
