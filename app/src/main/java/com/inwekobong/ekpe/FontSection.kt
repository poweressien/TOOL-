package com.inwekobong.ekpe

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.File

class FontSection(private val a: MainActivity) {
    private data class Item(val name: String, val file: File?, val remote: String?, val group: String)
    private lateinit var list: ListView
    private lateinit var sample: EditText
    private var items = ArrayList<Item>()
    private lateinit var filter: EditText
    private val cache = HashMap<String, Typeface?>()

    fun view(): View {
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG); setPadding(a.dp(14), a.dp(14), a.dp(14), 0) }
        root.add(a.tv("Font Studio", 22f, C.TXT, true), 0)
        root.add(a.tv("Phone fonts + Google Fonts + your own .ttf/.otf. Apply to this app, the floating clock, or export for your phone's font manager.", 12f, C.SUB), 2)
        sample = a.edit("Preview text").apply { setText("The quick brown fox 0123456789") }
        root.add(sample, 8)
        filter = a.edit("Filter fonts…")
        root.add(filter, 6)
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(a.button("Import font file") { a.pickFile { u -> if (u != null) { val f = FontStore.importFrom(a, u); a.toast(if (f != null) "Imported ${f.name}" else "Not a valid font file"); rebuild() } } }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(a.button("Reset app font") { a.prefs().edit().remove("font_app").remove("font_clock").apply(); a.recreate() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = a.dp(8) })
        root.add(row, 6)
        root.add(a.slider("System text size % (needs permission)", 100, systemScale()) { v -> setSystemScale(60 + v) }.also { }, 8)
        list = ListView(a).apply { divider = null; setBackgroundColor(C.BG) }
        root.add(list, 8)
        (list.layoutParams as LinearLayout.LayoutParams).apply { height = 0; weight = 1f }
        filter.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { refresh() }
            override fun beforeTextChanged(s: CharSequence?, x: Int, y: Int, z: Int) {}
            override fun onTextChanged(s: CharSequence?, x: Int, y: Int, z: Int) {}
        })
        sample.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { (list.adapter as? BaseAdapter)?.notifyDataSetChanged() }
            override fun beforeTextChanged(s: CharSequence?, x: Int, y: Int, z: Int) {}
            override fun onTextChanged(s: CharSequence?, x: Int, y: Int, z: Int) {}
        })
        rebuild()
        return root
    }

    private fun systemScale(): Int = try { ((Settings.System.getFloat(a.contentResolver, Settings.System.FONT_SCALE) * 100).toInt() - 60).coerceIn(0, 100) } catch (e: Exception) { 40 }

    private fun setSystemScale(pct: Int) {
        if (!Settings.System.canWrite(a)) {
            a.toast("Allow “Modify system settings” for EKPE, then try again")
            a.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${a.packageName}")))
            return
        }
        Settings.System.putFloat(a.contentResolver, Settings.System.FONT_SCALE, pct / 100f)
    }

    private fun rebuild() {
        items = ArrayList()
        FontStore.imported(a).forEach { items.add(Item(it.nameWithoutExtension, it, null, "MY FONTS / DOWNLOADED")) }
        val have = FontStore.imported(a).map { it.nameWithoutExtension.lowercase() }
        FontStore.catalog.filter { it.first.replace(" ", "").lowercase() !in have }.forEach { items.add(Item(it.first, null, it.second, "GOOGLE FONTS (tap Download)")) }
        FontStore.systemFonts().forEach { items.add(Item(it.nameWithoutExtension, it, null, "PHONE SYSTEM FONTS")) }
        refresh()
    }

    private fun refresh() {
        val q = filter.text.toString().trim().lowercase()
        val shown = items.filter { q.isEmpty() || it.name.lowercase().contains(q) }
        list.adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(p: Int) = shown[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
                val it = shown[p]
                val box = a.card()
                box.layoutParams = AbsListView.LayoutParams(-1, -2)
                val wrap = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, a.dp(4), 0, a.dp(4)) }
                if (p == 0 || shown[p - 1].group != it.group) wrap.addView(a.tv(it.group, 12f, C.ACC, true).apply { setPadding(0, a.dp(8), 0, a.dp(4)) })
                val prev = a.tv(sample.text.toString().ifBlank { it.name }, 20f)
                it.file?.let { f -> prev.typeface = cache.getOrPut(f.path) { FontStore.load(f.path) } ?: Typeface.DEFAULT }
                box.addView(prev); box.addView(a.tv(it.name, 11f, C.SUB))
                val btns = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
                if (it.file != null) {
                    btns.addView(a.button("App") { a.prefs().edit().putString("font_app", it.file.path).apply(); a.recreate() }, LinearLayout.LayoutParams(0, -2, 1f))
                    btns.addView(a.button("Clock") { a.prefs().edit().putString("font_clock", it.file.path).apply(); a.toast("Clock font set") }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = a.dp(6) })
                    btns.addView(a.button("Phone…") { phoneDialog(it.file) }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = a.dp(6) })
                } else {
                    btns.addView(a.button("Download") {
                        a.toast("Downloading ${it.name}…")
                        Thread {
                            val f = FontStore.download(a, it.name, it.remote!!)
                            a.runOnUiThread { a.toast(if (f != null) "${it.name} ready" else "Download failed — check internet"); if (f != null) rebuild() }
                        }.start()
                    }, LinearLayout.LayoutParams(-1, -2))
                }
                box.addView(btns, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(6) })
                wrap.addView(box)
                wrap.applyTypeface(null)
                return wrap
            }
        }
    }

    private fun phoneDialog(f: File) {
        AlertDialog.Builder(a).setTitle("Use as phone font")
            .setMessage("Android does not let a normal app overwrite the system font (that needs root).\n\nThis button copies the font to Downloads/EKPEFonts, and opens your display settings. On Xiaomi/POCO use Themes → Fonts → Local/Import; on Samsung use Display → Font style. The system text SIZE slider above does work system-wide.")
            .setPositiveButton("Export & open settings") { _, _ ->
                a.toast(if (FontStore.exportToDownloads(a, f)) "Saved to Downloads/EKPEFonts" else "Export failed")
                try { a.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS)) } catch (e: Exception) {}
            }.setNegativeButton("Cancel", null).show()
    }
}
