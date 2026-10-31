package com.inwekobong.ekpe

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject
import android.content.res.ColorStateList

/** Hot/cold tracker built on Radar's Bluetooth scan. */
class DeviceFinderSection(private val a: MainActivity) : Tool {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var list: LinearLayout
    private lateinit var track: LinearLayout
    private lateinit var name: TextView
    private lateinit var heat: TextView
    private lateinit var bar: ProgressBar
    private var target: String? = null
    private var targetName = ""
    private var smooth = -100.0
    private var sound = true
    private var hideUnnamed = true
    private var tone: ToneGenerator? = null
    private var sinceBeep = 0L
    private var sinceList = 99999L
    private var running = false

    override fun view(): View {
        val p = a.page()
        p.add(a.tv("Device Finder", 20f, C.TXT, true), 0)
        p.add(a.tv("Pick your lost earbuds, watch or tracker, then walk around. Warmer = closer. Many devices change their Bluetooth address often, so put earbuds in pairing mode or case-open for best results.", 12f, C.SUB), 4)
        p.add(a.sw("Beep (faster when closer)", true) { sound = it }, 8)
        p.add(a.sw("Hide unnamed devices", true) { hideUnnamed = it; sinceList = 99999L }, 4)
        track = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; background = round(C.CARD, a.dp(12)); setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10)) }
        name = a.tv("", 16f, C.TXT, true); heat = a.tv("", 28f, C.ACC, true)
        bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        track.addView(name); track.addView(heat); track.addView(bar, LinearLayout.LayoutParams(-1, a.dp(18)).apply { topMargin = a.dp(8) })
        track.addView(a.button("Stop tracking / choose another") { target = null; track.visibility = View.GONE; list.visibility = View.VISIBLE; sinceList = 99999L }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(10) })
        p.add(track, 10)
        list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        p.add(list, 10)
        running = true
        tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull()
        GuardPerm.startRadar(a)
        ui.post(loop)
        return p
    }

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            try { tick(200) } catch (e: Exception) { }
            ui.postDelayed(this, 200)
        }
    }

    private fun pct(r: Double) = ((r + 100) * 100 / 60).toInt().coerceIn(0, 100)

    private fun tick(dt: Long) {
        val j = JSONObject(RadarEngine.snapshot())
        val b = j.optJSONArray("ble") ?: return
        val t = target
        if (t == null) {
            sinceList += dt
            if (sinceList < 2000) return
            sinceList = 0
            val items = (0 until b.length()).map { b.getJSONObject(it) }
                .filter { !hideUnnamed || it.optString("n").isNotEmpty() }.sortedByDescending { it.optInt("r") }.take(25)
            list.removeAllViews()
            if (items.isEmpty()) list.add(a.tv("Scanning… make sure Bluetooth, Location and Nearby-devices permission are on.", 12f, C.SUB), 0)
            items.forEach { o ->
                val nm = o.optString("n").ifEmpty { "Unnamed (${o.optString("a").takeLast(8)})" }
                val row = a.card()
                row.addView(a.tv(nm, 14f, C.TXT, true))
                row.addView(a.tv("${o.optInt("r")} dBm · about ${o.optDouble("d")} m · ${o.optString("a")}", 11f, C.SUB))
                row.setOnClickListener { pick(o.optString("a"), nm) }
                list.add(row, 6)
            }
            list.applyTypeface(a.tf)
            return
        }
        var rssi: Int? = null
        for (i in 0 until b.length()) { val o = b.getJSONObject(i); if (o.optString("a") == t) rssi = o.optInt("r") }
        if (rssi == null) { heat.text = "Signal lost"; bar.progress = 0; return }
        smooth = if (smooth <= -100.0) rssi.toDouble() else smooth * 0.6 + rssi * 0.4
        val pc = pct(smooth)
        val (txt, col) = when {
            pc >= 80 -> "VERY HOT 🔥" to 0xFFFF3B30.toInt()
            pc >= 60 -> "HOT" to 0xFFFF9500.toInt()
            pc >= 40 -> "WARM" to 0xFFFFCC00.toInt()
            pc >= 20 -> "COLD" to 0xFF4F8CFF.toInt()
            else -> "FREEZING ❄" to 0xFF5AC8FA.toInt()
        }
        heat.text = "$txt  $pc%"; heat.setTextColor(col)
        bar.progress = pc; bar.progressTintList = ColorStateList.valueOf(col)
        name.text = "$targetName · ${smooth.toInt()} dBm"
        sinceBeep += dt
        if (sound && sinceBeep >= 1300 - pc * 11) { sinceBeep = 0; runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 60) } }
    }

    private fun pick(addr: String, nm: String) {
        target = addr; targetName = nm; smooth = -100.0
        list.visibility = View.GONE; track.visibility = View.VISIBLE
        name.text = nm; heat.text = "Searching…"; heat.setTextColor(C.ACC)
    }

    override fun stop() { running = false; ui.removeCallbacksAndMessages(null); runCatching { tone?.release() }; tone = null }
}
