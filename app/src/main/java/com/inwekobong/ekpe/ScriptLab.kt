package com.inwekobong.ekpe

import android.app.AlertDialog
import android.graphics.Typeface
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File

/** A Python console on the phone (Chaquopy). Variables persist between runs until you reset the session. */
class ScriptLabSection(private val a: MainActivity) : Tool {
    private lateinit var code: EditText
    private lateinit var out: TextView
    private var running = false
    private fun dir() = File(a.filesDir, "scripts").also { it.mkdirs() }

    private val example = """r = radar()
print("Wi-Fi networks:", len(r["wifi"]))
print("Bluetooth devices:", len(r["ble"]))
for w in sorted(r["wifi"], key=lambda w: -w["r"])[:5]:
    print(w["s"] or "<hidden>", w["r"], "dBm", w["sec"])
"""

    override fun view(): View {
        val p = a.page()
        p.add(a.tv("Script Lab", 20f, C.TXT, true), 0)
        p.add(a.tv("Python 3.12 on the phone. Use radar() for live Radar data and notify(title, text) for an alert. Packages are limited to what is bundled (requests, beautifulsoup4, dnspython, phonenumbers, openpyxl…). A script can't be stopped once it starts, so avoid endless loops.", 12f, C.SUB), 4)
        code = a.edit("Type Python here").apply { typeface = Typeface.MONOSPACE; minLines = 8; gravity = android.view.Gravity.TOP; setText(example); textSize = 13f }
        p.add(code, 10)
        val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        r.addView(a.button("▶ Run") { run() }, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(a.button("Save") { save() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = a.dp(6) })
        r.addView(a.button("Open") { open() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = a.dp(6) })
        p.add(r, 8)
        val r2 = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        r2.addView(a.button("Reset session") { runCatching { py().getModule("scriptlab").callAttr("reset") }; out.text = "Session reset." }, LinearLayout.LayoutParams(0, -2, 1f))
        r2.addView(a.button("Clear output") { out.text = "" }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = a.dp(6) })
        p.add(r2, 6)
        out = a.tv("", 12f, C.TXT).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true); background = round(C.CARD, a.dp(12)); setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10)) }
        p.add(out, 10)
        GuardPerm.startRadar(a)
        return p
    }

    private fun py(): Python { if (!Python.isStarted()) Python.start(AndroidPlatform(a.applicationContext)); return Python.getInstance() }

    private fun run() {
        if (running) { a.toast("A script is still running"); return }
        val src = code.text.toString(); running = true; out.append("\n>>> run\n")
        Thread {
            val r = try { py().getModule("scriptlab").callAttr("run", src).toString() } catch (e: Exception) { "Engine error: ${e.message}" }
            a.runOnUiThread { out.append(r); running = false }
        }.start()
    }

    private fun save() {
        val n = a.edit("File name"); val l = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(16), a.dp(8), a.dp(16), 0) }; l.add(n, 0)
        AlertDialog.Builder(a).setTitle("Save script").setView(l).setPositiveButton("Save") { _, _ ->
            val nm = n.text.toString().trim().replace(Regex("[^A-Za-z0-9_-]"), "_").ifEmpty { "script" }
            File(dir(), "$nm.py").writeText(code.text.toString()); a.toast("Saved $nm.py")
        }.setNegativeButton("Cancel", null).show()
    }

    private fun open() {
        val fs = dir().listFiles { f -> f.name.endsWith(".py") }?.sortedBy { it.name } ?: emptyList()
        if (fs.isEmpty()) { a.toast("No saved scripts"); return }
        AlertDialog.Builder(a).setTitle("Open script").setItems(fs.map { it.name }.toTypedArray()) { _, i -> code.setText(fs[i].readText()) }
            .setNeutralButton("Delete one…") { _, _ ->
                AlertDialog.Builder(a).setTitle("Delete which?").setItems(fs.map { it.name }.toTypedArray()) { _, i -> fs[i].delete() }.show()
            }.show()
    }
}
