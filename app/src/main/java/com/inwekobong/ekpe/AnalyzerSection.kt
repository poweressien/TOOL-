package com.inwekobong.ekpe

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.view.View
import android.widget.*
import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class AnalyzerSection(private val a: MainActivity) {
    private val util = PhoneNumberUtil.getInstance()
    private lateinit var result: LinearLayout
    private lateinit var input: EditText
    private lateinit var spinner: Spinner
    private val contacts = ArrayList<Pair<String, String>>()
    private var seq = 0

    fun view(): View {
        val p = a.page()
        p.add(a.tv("Number Analyzer", 22f, C.TXT, true), 0)
        p.add(a.tv("Pick a saved contact or type any number. Everything runs on this phone.", 12f, C.SUB), 2)
        spinner = Spinner(a)
        p.add(spinner, 10)
        input = a.edit("Or type a number, e.g. 0803 123 4567 or +234…", true)
        p.add(input, 8)
        val btns = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(a.button("Scan number") { scan(input.text.toString()) }, LinearLayout.LayoutParams(0, -2, 1f))
        btns.addView(a.button("Allow access") { askPerms() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = a.dp(8) })
        p.add(btns, 8)
        result = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        p.add(result, 12)
        loadContacts()
        return a.scroll(p)
    }

    private fun has(perm: String) = a.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    private fun askPerms() = a.ask(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS)) { loadContacts() }

    private fun loadContacts() {
        contacts.clear()
        if (has(Manifest.permission.READ_CONTACTS)) {
            a.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC")?.use {
                while (it.moveToNext()) contacts.add((it.getString(0) ?: "?") to (it.getString(1) ?: ""))
            }
        }
        val labels = listOf("Choose a contact  ▼") + contacts.map { "${it.first} — ${it.second}" }
        spinner.adapter = ArrayAdapter(a, android.R.layout.simple_spinner_dropdown_item, labels)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos > 0) { input.setText(contacts[pos - 1].second); scan(contacts[pos - 1].second) }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun region(): String {
        val tm = a.getSystemService(TelephonyManager::class.java)
        return (tm?.simCountryIso?.takeIf { it.isNotEmpty() } ?: tm?.networkCountryIso?.takeIf { it.isNotEmpty() } ?: "ng").uppercase()
    }

    private val ngPrefixes = mapOf(
        "MTN" to listOf("0803", "0806", "0703", "0706", "0813", "0816", "0810", "0814", "0903", "0906", "0913", "0916", "0704", "07025", "07026"),
        "Glo" to listOf("0805", "0807", "0705", "0815", "0811", "0905", "0915"),
        "Airtel" to listOf("0802", "0808", "0708", "0812", "0701", "0902", "0901", "0904", "0907", "0912"),
        "9mobile" to listOf("0809", "0818", "0817", "0909", "0908")
    )

    private fun scan(raw: String) {
        if (raw.isBlank()) { a.toast("Enter or pick a number first"); return }
        val my = ++seq
        val rows = ArrayList<Row>()
        val digits = raw.filter { it.isDigit() }
        rows += Row("PHONE NUMBER", header = true)
        rows += Row("Input", raw)
        var e164 = "+$digits"; var regionCode = region()
        try {
            val n = util.parse(raw, region())
            e164 = util.format(n, PhoneNumberUtil.PhoneNumberFormat.E164)
            regionCode = util.getRegionCodeForNumber(n) ?: regionCode
            rows += Row("E.164", e164)
            rows += Row("International", util.format(n, PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL))
            rows += Row("National", util.format(n, PhoneNumberUtil.PhoneNumberFormat.NATIONAL))
            rows += Row("Valid number", if (util.isValidNumber(n)) "Yes" else "No")
            rows += Row("Possible length", if (util.isPossibleNumber(n)) "Yes" else "No")
            rows += Row("Country", "${Locale("", regionCode).displayCountry} ($regionCode, +${n.countryCode})")
            rows += Row("Line type", util.getNumberType(n).name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() })
            if (regionCode == "NG") {
                val nat = "0" + n.nationalNumber
                val c = ngPrefixes.entries.firstOrNull { e -> e.value.any { nat.startsWith(it) } }?.key
                rows += Row("Network (prefix)", (c ?: "Unknown prefix") + "  · ported numbers may differ")
            }
        } catch (ex: Exception) { rows += Row("Parse", "Could not parse (${ex.message})") }

        // Saved contact + linked accounts
        rows += Row("SAVED CONTACT", header = true)
        if (has(Manifest.permission.READ_CONTACTS)) rows += contactRows(raw) else rows += Row("Contacts", "Tap “Allow access”")

        rows += Row("MESSENGERS & SOCIAL", header = true)
        val clean = e164.removePrefix("+")
        rows += Row("WhatsApp app", if (installed("com.whatsapp") || installed("com.whatsapp.w4b")) "Installed" else "Not installed")
        rows += Row("Open WhatsApp chat", "Tap to test", "https://wa.me/$clean")
        rows += Row("Telegram app", if (installed("org.telegram.messenger")) "Installed" else "Not installed")
        rows += Row("Open Telegram", "Tap to test", "https://t.me/+$clean")
        rows += Row("Viber / Signal", ((if (installed("com.viber.voip")) "Viber " else "") + (if (installed("org.thoughtcrime.securesms")) "Signal" else "")).ifBlank { "Not installed" })

        rows += Row("LOOKUP SERVICES", header = true)
        rows += Row("Truecaller name", if (installed("com.truecaller")) "Truecaller installed — tap to search" else "Tap to search on Truecaller web",
            "https://www.truecaller.com/search/${regionCode.lowercase()}/$digits")
        rows += Row("Sync.me", "Tap to search", "https://sync.me/search/?number=${URLEncoder.encode(e164, "UTF-8")}")
        rows += Row("Google", "Tap to search", "https://www.google.com/search?q=" + URLEncoder.encode("\"$raw\" OR \"$e164\"", "UTF-8"))
        rows += Row("Facebook", "Tap to search", "https://www.facebook.com/search/top?q=" + URLEncoder.encode(e164, "UTF-8"))

        rows += Row("CALL HISTORY", header = true)
        rows += callRows(digits)
        rows += Row("YOUR SIM(S)", header = true)
        rows += simRows()
        rows += Row("WEB PRESENCE (DuckDuckGo)", header = true)
        rows += Row("Search results", "Searching…")
        render(rows)

        Thread {
            val web = webHits(raw, e164)
            a.runOnUiThread {
                if (my != seq) return@runOnUiThread
                val idx = rows.indexOfFirst { it.k == "Search results" }
                if (idx >= 0) { rows.removeAt(idx); rows.addAll(idx, web) }
                render(rows)
            }
        }.start()
    }

    private fun render(rows: List<Row>) {
        result.removeAllViews()
        result.addView(a.table(rows))
        result.applyTypeface(a.tf)
    }

    private fun installed(pkg: String) = try { a.packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }

    private fun contactRows(raw: String): List<Row> {
        val out = ArrayList<Row>()
        try {
            val u = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(raw))
            val c = a.contentResolver.query(u, arrayOf(ContactsContract.PhoneLookup.CONTACT_ID, ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            var id: Long? = null; var name: String? = null
            c?.use { if (it.moveToFirst()) { id = it.getLong(0); name = it.getString(1) } }
            if (id == null) { out += Row("In your contacts", "No — unsaved number"); return out }
            out += Row("Name", name ?: "?")
            val emails = ArrayList<String>(); val phones = ArrayList<String>()
            a.contentResolver.query(ContactsContract.CommonDataKinds.Email.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
                "${ContactsContract.CommonDataKinds.Email.CONTACT_ID}=?", arrayOf(id.toString()), null)?.use { while (it.moveToNext()) emails += it.getString(0) }
            a.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID}=?", arrayOf(id.toString()), null)?.use { while (it.moveToNext()) phones += it.getString(0) }
            out += Row("All numbers", phones.distinct().joinToString("\n").ifBlank { "—" })
            out += Row("Emails", emails.joinToString("\n").ifBlank { "None saved" })
            out += Row("Gmail linked", if (emails.any { it.endsWith("@gmail.com", true) || it.endsWith("@googlemail.com", true) }) "Yes (Gmail address on contact)" else "No Gmail address saved")
            a.contentResolver.query(ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Organization.COMPANY, ContactsContract.CommonDataKinds.Organization.TITLE),
                "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                arrayOf(id.toString(), ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE), null)?.use {
                if (it.moveToFirst()) out += Row("Company", listOfNotNull(it.getString(0), it.getString(1)).joinToString(" · ").ifBlank { "—" })
            }
            val accts = ArrayList<Pair<String, String?>>()
            a.contentResolver.query(ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts.ACCOUNT_TYPE, ContactsContract.RawContacts.ACCOUNT_NAME),
                "${ContactsContract.RawContacts.CONTACT_ID}=?", arrayOf(id.toString()), null)?.use {
                while (it.moveToNext()) accts += (it.getString(0) ?: "device") to it.getString(1)
            }
            fun hasAcct(vararg t: String) = accts.any { x -> t.any { x.first.contains(it, true) } }
            out += Row("On WhatsApp", if (hasAcct("whatsapp")) "Yes (WhatsApp synced this contact)" else "Not detected in synced contacts")
            out += Row("On Telegram", if (hasAcct("telegram")) "Yes (Telegram synced this contact)" else "Not detected in synced contacts")
            out += Row("On Signal / Viber", if (hasAcct("securesms", "viber", "signal")) "Yes" else "Not detected")
            out += Row("Google account", accts.filter { it.first == "com.google" }.mapNotNull { it.second }.joinToString("\n").ifBlank { "Not synced to a Google account" })
            out += Row("Stored in", accts.map { "${it.first}${it.second?.let { s -> " ($s)" } ?: ""}" }.distinct().joinToString("\n"))
        } catch (e: Exception) { out += Row("Contacts", "Error: ${e.message}") }
        return out
    }

    private fun callRows(digits: String): List<Row> {
        if (!has(Manifest.permission.READ_CALL_LOG)) return listOf(Row("Call log", "Tap “Allow access”"))
        val tail = digits.takeLast(9)
        var inc = 0; var out = 0; var missed = 0; var secs = 0L; var first = 0L; var last = 0L
        try {
            a.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                "${CallLog.Calls.NUMBER} LIKE ?", arrayOf("%$tail"), "${CallLog.Calls.DATE} DESC")?.use {
                while (it.moveToNext()) {
                    when (it.getInt(0)) { CallLog.Calls.INCOMING_TYPE -> inc++; CallLog.Calls.OUTGOING_TYPE -> out++; CallLog.Calls.MISSED_TYPE -> missed++ }
                    val d = it.getLong(1); if (last == 0L) last = d; first = d; secs += it.getLong(2)
                }
            }
        } catch (e: Exception) { return listOf(Row("Call log", "Error: ${e.message}")) }
        if (inc + out + missed == 0) return listOf(Row("Calls", "None found"))
        val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        return listOf(Row("Incoming / outgoing / missed", "$inc / $out / $missed"), Row("Total talk time", "${secs / 60} min ${secs % 60} s"),
            Row("First call", df.format(Date(first))), Row("Last call", df.format(Date(last))))
    }

    private fun simRows(): List<Row> {
        if (!has(Manifest.permission.READ_PHONE_STATE)) return listOf(Row("SIM", "Tap “Allow access”"))
        val out = ArrayList<Row>()
        try {
            val sm = a.getSystemService(SubscriptionManager::class.java)
            val list = sm.activeSubscriptionInfoList ?: emptyList()
            if (list.isEmpty()) out += Row("SIM", "No active SIM found")
            for (s in list) {
                out += Row("SIM ${s.simSlotIndex + 1}", "${s.displayName} · ${s.carrierName} · ${s.countryIso?.uppercase()}")
            }
        } catch (e: Exception) { out += Row("SIM", "Error: ${e.message}") }
        return out
    }

    private fun webHits(raw: String, e164: String): List<Row> = try {
        val q = URLEncoder.encode("\"$raw\" OR \"$e164\"", "UTF-8")
        val conn = URL("https://html.duckduckgo.com/html/?q=$q").openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        conn.connectTimeout = 10000; conn.readTimeout = 15000
        val html = conn.inputStream.bufferedReader().readText()
        val titles = Regex("class=\"result__a\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(html)
            .map { it.groupValues[1].replace(Regex("<[^>]+>"), "").trim() }.toList()
        if (titles.isEmpty()) listOf(Row("Appears online", "No public pages found (or search blocked)"))
        else listOf(Row("Appears online", "Yes — ${titles.size}+ result(s)")) +
            titles.take(4).mapIndexed { i, t -> Row("Result ${i + 1}", t) }
    } catch (e: Exception) { listOf(Row("Appears online", "Offline or blocked — use the search links above")) }
}
