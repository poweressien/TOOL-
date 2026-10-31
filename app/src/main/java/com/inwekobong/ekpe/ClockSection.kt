package com.inwekobong.ekpe

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.widget.*

class ClockSection(private val a: MainActivity) {
    fun view(): View {
        val sp = a.prefs()
        val p = a.page()
        p.add(a.tv("Floating Clock", 22f, C.TXT, true), 0)
        p.add(a.tv("Drag to move · pinch or drag ◢ to resize · double-tap the clock to open this page.", 12f, C.SUB), 2)
        val on = a.sw("Show floating clock", sp.getBoolean("clock_on", false)) { v ->
            sp.edit().putBoolean("clock_on", v).apply()
            if (v) {
                if (!Settings.canDrawOverlays(a)) { a.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${a.packageName}"))); a.toast("Allow overlay, then switch on again") }
                else a.startForegroundService(Intent(a, FloatingClockService::class.java))
            } else a.stopService(Intent(a, FloatingClockService::class.java))
        }
        p.add(on, 10)

        val look = a.card()
        look.add(a.tv("Background", 15f, C.ACC, true), 0)
        colorPicker(look, "clock_bg", 0x101827, "Background colour")
        look.add(a.slider("Background opacity", 255, sp.getInt("clock_bg_a", 200)) { sp.edit().putInt("clock_bg_a", it).apply() }, 6)
        look.add(a.slider("Corner radius", 60, sp.getInt("clock_r", 16)) { sp.edit().putInt("clock_r", it).apply() }, 6)
        p.add(look, 12)

        val txt = a.card()
        txt.add(a.tv("Text", 15f, C.ACC, true), 0)
        colorPicker(txt, "clock_fg", 0xFFFFFF, "Text colour")
        txt.add(a.sw("24-hour time", sp.getBoolean("clock_24h", false)) { sp.edit().putBoolean("clock_24h", it).apply() }, 8)
        txt.add(a.sw("Show seconds", sp.getBoolean("clock_sec", false)) { sp.edit().putBoolean("clock_sec", it).apply() }, 6)
        txt.add(a.sw("Show date", sp.getBoolean("clock_date", true)) { sp.edit().putBoolean("clock_date", it).apply() }, 6)
        txt.add(a.sw("Bold", sp.getBoolean("clock_bold", true)) { sp.edit().putBoolean("clock_bold", it).apply() }, 6)
        txt.add(a.sw("Text shadow", sp.getBoolean("clock_shadow", true)) { sp.edit().putBoolean("clock_shadow", it).apply() }, 6)
        txt.add(a.tv("Pick a clock font in the Fonts tab (button “Clock”).", 12f, C.SUB), 6)
        p.add(txt, 12)

        val border = a.card()
        border.add(a.tv("Border", 15f, C.ACC, true), 0)
        border.add(a.slider("Border width", 10, sp.getInt("clock_sw", 0)) { sp.edit().putInt("clock_sw", it).apply() }, 4)
        colorPicker(border, "clock_sc", 0x4F8CFF, "Border colour")
        p.add(border, 12)

        val size = a.card()
        size.add(a.tv("Size, position & lock", 15f, C.ACC, true), 0)
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        fun preset(label: String, w: Int, h: Int) = row.addView(a.button(label) { sp.edit().putInt("clock_w", a.dp(w)).putInt("clock_h", a.dp(h)).apply() }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = a.dp(6) })
        preset("S", 130, 60); preset("M", 200, 90); preset("L", 300, 130); preset("XL", 340, 200)
        size.add(row, 8)
        size.add(a.sw("Lock position", sp.getBoolean("clock_lock", false)) { sp.edit().putBoolean("clock_lock", it).apply() }, 8)
        size.add(a.button("Move to top-left") { sp.edit().putInt("clock_x", a.dp(20)).putInt("clock_y", a.dp(80)).apply() }, 8)
        size.add(a.button("Reset everything") {
            sp.edit().remove("clock_bg").remove("clock_bg_a").remove("clock_fg").remove("clock_r").remove("clock_sw").remove("clock_sc")
                .remove("clock_24h").remove("clock_sec").remove("clock_date").remove("clock_bold").remove("clock_shadow").remove("clock_lock").remove("font_clock")
                .putInt("clock_w", a.dp(200)).putInt("clock_h", a.dp(90)).apply()
            a.toast("Clock reset — reopen this tab to refresh the sliders")
        }, 8)
        p.add(size, 12)
        return a.scroll(p)
    }

    private fun colorPicker(parent: LinearLayout, key: String, def: Int, label: String) {
        val sp = a.prefs()
        var cur = sp.getInt(key, def) and 0xFFFFFF
        val swatch = View(a).apply { setBackgroundColor(cur or 0xFF000000.toInt()) }
        parent.add(a.tv(label, 12f, C.SUB), 8)
        parent.add(swatch, 4); swatch.layoutParams.height = a.dp(24)
        fun save() { swatch.setBackgroundColor(cur or 0xFF000000.toInt()); sp.edit().putInt(key, cur).apply() }
        val presets = listOf(0xFFFFFF, 0x000000, 0xFF3B30, 0xFF9500, 0xFFD60A, 0x34C759, 0x00C7BE, 0x4F8CFF, 0xAF52DE, 0xFF2D92, 0x101827)
        val pr = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        presets.forEach { c ->
            pr.addView(View(a).apply { setBackgroundColor(c or 0xFF000000.toInt()); setOnClickListener { cur = c; save() } },
                LinearLayout.LayoutParams(0, a.dp(28), 1f).apply { rightMargin = a.dp(3) })
        }
        parent.add(pr, 6)
        for ((i, n) in listOf("Red", "Green", "Blue").withIndex()) {
            val shift = 16 - 8 * i
            parent.add(a.slider(n, 255, (cur shr shift) and 0xFF) { v -> cur = (cur and (0xFF shl shift).inv()) or (v shl shift); save() }, 2)
        }
    }
}
