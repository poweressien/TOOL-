package com.inwekobong.ekpe

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class SmsRule(val name: String, val senders: String, val debit: String, val credit: String, val amount: String) {
    fun json() = JSONObject().put("n", name).put("s", senders).put("d", debit).put("c", credit).put("a", amount)
    companion object { fun from(o: JSONObject) = SmsRule(o.getString("n"), o.getString("s"), o.getString("d"), o.getString("c"), o.getString("a")) }
}

class Txn(val id: Long, val ts: Long, val out: Boolean, val amt: Double, val cat: String, val sender: String, val note: String)

class BarsView(c: Context) : View(c) {
    var values: List<Pair<String, Double>> = emptyList()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(cv: Canvas) {
        if (values.isEmpty()) return
        val mx = values.maxOf { it.second }.coerceAtLeast(1.0); val w = width.toFloat() / values.size
        p.color = C.ACC
        values.forEachIndexed { i, v -> val h = (v.second / mx * (height - 36)).toFloat(); cv.drawRect(i * w + 3, height - 28 - h, (i + 1) * w - 3, height - 28f, p) }
        p.color = C.SUB; p.textSize = 22f
        values.forEachIndexed { i, v -> if (values.size <= 16 || i % 5 == 0) cv.drawText(v.first, i * w + 2, height - 6f, p) }
    }
}

/** Turns bank / fintech SMS alerts into a local ledger. Nothing leaves the phone. Alert formats vary, so the rules are editable. */
class MoneyTrackerSection(private val a: MainActivity) : Tool {
    private val ledgerFile get() = File(a.filesDir, "ledger.json")
    private var txns = ArrayList<Txn>()
    private var month = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    private lateinit var body: LinearLayout
    private lateinit var status: TextView

    private val DEBIT = "\\b(DR|debit(ed)?|sent|sent to|purchase|withdrawal|withdrawn|paid|payment|transfer to|airtime)\\b"
    private val CREDIT = "\\b(CR|credit(ed)?|received|deposit(ed)?|transfer from|reversal)\\b"
    private val AMOUNT = "(?:(?<![A-Za-z])(?:NGN|₦|N)\\s?|Amt[:\\s]*(?:NGN|₦|N)?\\s?|Amount[:\\s]*(?:NGN|₦|N)?\\s?)([0-9][0-9,]*(?:\\.[0-9]{1,2})?)"

    private fun defaults() = listOf(
        SmsRule("Banks", "GTB,GTBank,Access,FirstBank,FBN,UBA,Zenith,Sterling,FCMB,Wema,Fidelity,Ecobank,Stanbic,Polaris,Union,Providus,Keystone,Heritage", DEBIT, CREDIT, AMOUNT),
        SmsRule("Fintech", "OPay,Moniepoint,PalmPay,Kuda,Paga,Carbon,FairMoney,Paystack", DEBIT, CREDIT, AMOUNT)
    )

    private fun rules(): MutableList<SmsRule> = runCatching {
        val j = JSONArray(a.prefs().getString("sms_rules", null) ?: throw Exception()); (0 until j.length()).map { SmsRule.from(j.getJSONObject(it)) }.toMutableList()
    }.getOrElse { defaults().toMutableList() }
    private fun saveRules(l: List<SmsRule>) { val j = JSONArray(); l.forEach { j.put(it.json()) }; a.prefs().edit().putString("sms_rules", j.toString()).apply() }

    private val CATS = listOf("Airtime & data" to "airtime|data bundle|mtn|glo|airtel|9mobile|recharge", "Bills" to "dstv|gotv|startimes|electric|ikedc|ekedc|phcn|prepaid|nepa|water|bill|subscription",
        "Cash" to "atm|withdraw|cash", "Fees" to "fee|charge|stamp duty|vat|sms alert|maintenance", "Shopping" to "pos|purchase|web|shop|store|market|supermarket|jumia|konga",
        "Food & transport" to "food|restaurant|chicken|eatery|uber|bolt|fuel|filling|petrol|transport", "Transfers" to "transfer|trf|nip|sent to|received from|from ")

    private fun category(body: String, out: Boolean): String { if (!out) return "Income"; val b = body.lowercase(); return CATS.firstOrNull { Regex(it.second).containsMatchIn(b) }?.first ?: "Other" }


    private fun parseSms(id: Long, ts: Long, address: String, text: String, rs: List<SmsRule>): Txn? {
        val r = rs.firstOrNull { rule -> rule.senders.isBlank() || rule.senders.split(',').any { it.isNotBlank() && address.contains(it.trim(), true) } } ?: return null
        val d = runCatching { Regex(r.debit, RegexOption.IGNORE_CASE).find(text)?.range?.first }.getOrNull()
        val c = runCatching { Regex(r.credit, RegexOption.IGNORE_CASE).find(text)?.range?.first }.getOrNull()
        if (d == null && c == null) return null
        val out = when { c == null -> true; d == null -> false; else -> d < c }
        val m = runCatching { Regex(r.amount, RegexOption.IGNORE_CASE).findAll(text).firstOrNull { x -> !text.substring(maxOf(0, x.range.first - 14), x.range.first).contains("bal", true) } }.getOrNull() ?: return null
        val amt = m.groupValues.getOrNull(1)?.replace(",", "")?.toDoubleOrNull() ?: return null
        if (amt <= 0) return null
        return Txn(id, ts, out, amt, category(text, out), address, text.take(90).replace('\n', ' '))
    }

    private fun load() { txns.clear(); runCatching { val j = JSONArray(ledgerFile.readText()); for (i in 0 until j.length()) { val o = j.getJSONObject(i); txns.add(Txn(o.getLong("id"), o.getLong("t"), o.getBoolean("o"), o.getDouble("a"), o.getString("c"), o.getString("s"), o.getString("n"))) } } }
    private fun save() { val j = JSONArray(); txns.forEach { j.put(JSONObject().put("id", it.id).put("t", it.ts).put("o", it.out).put("a", it.amt).put("c", it.cat).put("s", it.sender).put("n", it.note)) }; ledgerFile.writeText(j.toString()) }

    override fun view(): View {
        load()
        val p = a.page()
        p.add(a.tv("Money Tracker", 20f, C.TXT, true), 0)
        p.add(a.tv("Reads bank, OPay, Moniepoint and PalmPay SMS alerts on this phone and builds a ledger. Nothing is uploaded. If an alert isn't picked up, edit the rules.", 12f, C.SUB), 4)
        val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        r.addView(a.button("Sync SMS") { sync() }, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(a.button("Edit rules") { rulesDialog() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = a.dp(6) })
        r.addView(a.button("Export month") { export() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = a.dp(6) })
        p.add(r, 10)
        status = a.tv("", 12f, C.SUB); p.add(status, 6)
        val nav = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        nav.addView(a.button("◀") { month.add(Calendar.MONTH, -1); render() })
        nav.addView(a.button("▶") { month.add(Calendar.MONTH, 1); render() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = a.dp(6) })
        p.add(nav, 8)
        body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }; p.add(body, 8)
        render()
        return p
    }

    private fun sync() {
        if (a.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) { a.ask(arrayOf(Manifest.permission.READ_SMS)) { if (a.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) sync() else a.toast("SMS permission is needed to read alerts") }; return }
        status.text = "Reading messages…"
        Thread {
            val rs = rules(); val have = txns.map { it.id }.toHashSet(); var added = 0; var skipped = 0
            val since = System.currentTimeMillis() - 400L * 86400000
            a.contentResolver.query(Uri.parse("content://sms/inbox"), arrayOf("_id", "address", "body", "date"), "date > ?", arrayOf(since.toString()), "date DESC")?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0); if (id in have) continue
                    val addr = c.getString(1) ?: ""; val body = c.getString(2) ?: ""
                    val t = parseSms(id, c.getLong(3), addr, body, rs)
                    if (t != null) { txns.add(t); added++ } else if (rs.any { r -> r.senders.split(',').any { it.isNotBlank() && addr.contains(it.trim(), true) } }) skipped++
                }
            }
            save()
            a.runOnUiThread { status.text = "Added $added transaction(s). $skipped bank message(s) were not recognised (OTPs, adverts, or a format the rules don't match)."; render() }
        }.start()
    }

    private fun render() {
        body.removeAllViews()
        val end = (month.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
        val list = txns.filter { it.ts >= month.timeInMillis && it.ts < end.timeInMillis }.sortedByDescending { it.ts }
        val label = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(month.time)
        val spent = list.filter { it.out }.sumOf { it.amt }; val inc = list.filter { !it.out }.sumOf { it.amt }
        fun n(x: Double) = "₦" + "%,.2f".format(x)
        body.add(a.table(listOf(Row(label.uppercase(), header = true), Row("Money in", n(inc)), Row("Money out", n(spent)), Row("Net", n(inc - spent)), Row("Transactions", "${list.size}"))), 0)
        if (list.isEmpty()) { body.add(a.tv("Nothing for this month yet. Tap Sync SMS.", 12f, C.SUB), 10); body.applyTypeface(a.tf); return }
        val cats = list.filter { it.out }.groupBy { it.cat }.mapValues { e -> e.value.sumOf { it.amt } }.entries.sortedByDescending { it.value }
        body.add(a.tv("Spending by category", 14f, C.TXT, true), 12)
        cats.forEach { e ->
            body.add(a.tv("${e.key} · ${n(e.value)}", 12f, C.SUB), 6)
            body.addView(ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progress = (e.value / spent * 1000).toInt() }, LinearLayout.LayoutParams(-1, a.dp(10)))
        }
        val days = (month.clone() as Calendar).getActualMaximum(Calendar.DAY_OF_MONTH)
        val perDay = DoubleArray(days); list.filter { it.out }.forEach { perDay[Calendar.getInstance().apply { timeInMillis = it.ts }.get(Calendar.DAY_OF_MONTH) - 1] += it.amt }
        body.add(a.tv("Spending per day", 14f, C.TXT, true), 14)
        body.addView(BarsView(a).apply { values = perDay.mapIndexed { i, v -> "${i + 1}" to v } }, LinearLayout.LayoutParams(-1, a.dp(140)).apply { topMargin = a.dp(6) })
        val df = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
        val rows = ArrayList<Row>(); rows += Row("TRANSACTIONS", header = true)
        list.take(60).forEach { rows += Row(df.format(Date(it.ts)), (if (it.out) "− " else "+ ") + n(it.amt) + " · " + it.cat + "\n" + it.sender) }
        body.add(a.table(rows), 14)
        body.applyTypeface(a.tf)
    }

    private fun export() {
        val end = (month.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
        val list = txns.filter { it.ts >= month.timeInMillis && it.ts < end.timeInMillis }.sortedBy { it.ts }
        if (list.isEmpty()) { a.toast("Nothing to export for this month"); return }
        val name = "money-" + SimpleDateFormat("yyyy-MM", Locale.US).format(month.time) + ".csv"
        a.saveFile(name) { uri ->
            if (uri != null) runCatching {
                val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                val sb = StringBuilder("date,type,amount,category,sender,note\n")
                list.forEach { sb.append("${df.format(Date(it.ts))},${if (it.out) "out" else "in"},${it.amt},${it.cat},\"${it.sender}\",\"${it.note.replace("\"", "'")}\"\n") }
                sb.append("\nTOTAL IN,,${list.filter { !it.out }.sumOf { it.amt }}\nTOTAL OUT,,${list.filter { it.out }.sumOf { it.amt }}\n")
                a.contentResolver.openOutputStream(uri)!!.use { it.write(sb.toString().toByteArray()) }
                a.toast("Report saved")
            }.onFailure { a.toast("Could not save report") }
        }
    }

    private fun rulesDialog() {
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(16), a.dp(8), a.dp(16), 0) }
        val rs = rules()
        val fields = rs.map { r ->
            val f = arrayOf(a.edit("Name").apply { setText(r.name) }, a.edit("Sender names, comma separated").apply { setText(r.senders) },
                a.edit("Debit words (regex)").apply { setText(r.debit) }, a.edit("Credit words (regex)").apply { setText(r.credit) }, a.edit("Amount regex (group 1 = number)").apply { setText(r.amount) })
            col.add(a.tv("Rule", 13f, C.ACC, true), 10); f.forEachIndexed { i, e -> col.add(e, if (i == 0) 2 else 4) }; f
        }
        AlertDialog.Builder(a).setTitle("SMS rules").setView(android.widget.ScrollView(a).apply { addView(col) })
            .setPositiveButton("Save") { _, _ -> saveRules(fields.map { SmsRule(it[0].text.toString(), it[1].text.toString(), it[2].text.toString(), it[3].text.toString(), it[4].text.toString()) }); a.toast("Rules saved. Sync again to re-read new messages.") }
            .setNeutralButton("Add rule") { _, _ -> saveRules(rules() + SmsRule("New rule", "", DEBIT, CREDIT, AMOUNT)); rulesDialog() }
            .setNegativeButton("Reset") { _, _ -> a.prefs().edit().remove("sms_rules").apply(); a.toast("Defaults restored") }.show()
    }
}
