package com.inwekobong.ekpe

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** Signal reading per SIM, shared by Coverage Mapper. */
object SimSignal {
    data class S(val carrier: String, val dbm: Int, val pct: Int)

    @SuppressLint("MissingPermission")
    fun read(c: Context): List<S> {
        val out = ArrayList<S>()
        val tm = c.getSystemService(TelephonyManager::class.java)
        fun dbmOf(t: TelephonyManager): Int? {
            val ss = t.signalStrength ?: return null
            val d = if (Build.VERSION.SDK_INT >= 29) ss.cellSignalStrengths.map { it.dbm }.filter { it in -140..-30 }.maxOrNull()
            else listOf(-120, -105, -95, -85, -75)[ss.level.coerceIn(0, 4)]
            return d
        }
        val subs = try { c.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList } catch (e: Exception) { null } ?: emptyList()
        if (subs.isEmpty()) {
            dbmOf(tm)?.let { out.add(S(tm.networkOperatorName.ifEmpty { "Carrier" }, it, ((it + 140) * 100 / 96).coerceIn(0, 100))) }
        } else for (s in subs) {
            val t = tm.createForSubscriptionId(s.subscriptionId)
            dbmOf(t)?.let { out.add(S(s.carrierName?.toString().orEmpty().ifEmpty { "SIM ${s.simSlotIndex + 1}" }, it, ((it + 140) * 100 / 96).coerceIn(0, 100))) }
        }
        return out
    }
}

class CoverView(c: Context) : View(c) {
    class P(val carrier: String, val pct: Int, val x: Double, val y: Double)
    var pts: List<P> = emptyList()
    var gps = true
    var winners = false
    var colors: Map<String, Int> = emptyMap()
    var best: Pair<Double, Double>? = null
    var onTap: ((Double, Double) -> Unit)? = null
    private val pn = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun heat(p: Int): Int {
        val f = p.coerceIn(0, 100) / 100f
        val r = if (f < 0.5f) 255 else (255 * (1 - f) * 2).toInt()
        val g = if (f < 0.5f) (255 * f * 2).toInt() else 255
        return 0xFF000000.toInt() or (r shl 16) or (g shl 8)
    }

    /** Maps data coordinates to screen coordinates. */
    private fun proj(): (Double, Double) -> Pair<Float, Float> {
        val pad = 30f; val w = width - 2 * pad; val h = height - 2 * pad
        if (!gps || pts.isEmpty()) return { x, y -> Pair((pad + x * w).toFloat(), (pad + y * h).toFloat()) }
        val lat0 = pts.map { it.y }.average(); val k = cos(Math.toRadians(lat0))
        val xs = pts.map { it.x * k }; val ys = pts.map { it.y }
        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
        val sx = max(maxX - minX, 0.00005); val sy = max(maxY - minY, 0.00005)
        val sc = min(w / sx, h / sy)
        return { x, y -> Pair((pad + (x * k - minX) * sc).toFloat(), (pad + (maxY - y) * sc).toFloat()) }
    }

    override fun onDraw(cv: Canvas) {
        cv.drawColor(0xFF0B0F1A.toInt())
        pn.style = Paint.Style.STROKE; pn.color = 0x33FFFFFF; pn.strokeWidth = 2f
        cv.drawRect(30f, 30f, width - 30f, height - 30f, pn)
        if (pts.isEmpty()) { pn.style = Paint.Style.FILL; pn.color = C.SUB; pn.textSize = 34f; cv.drawText(if (gps) "Walk around with logging on" else "Tap where you are standing", 50f, height / 2f, pn); return }
        val pr = proj(); pn.style = Paint.Style.FILL
        if (winners) {
            val cell = if (gps) 0.00007 else 0.08
            val grid = HashMap<Pair<Long, Long>, HashMap<String, MutableList<Int>>>()
            for (p in pts) grid.getOrPut(Pair(Math.floor(p.x / cell).toLong(), Math.floor(p.y / cell).toLong())) { HashMap() }.getOrPut(p.carrier) { ArrayList() }.add(p.pct)
            for ((k, m) in grid) {
                val w = m.maxByOrNull { it.value.average() } ?: continue
                val a1 = pr(k.first * cell, k.second * cell); val a2 = pr((k.first + 1) * cell, (k.second + 1) * cell)
                pn.color = (colors[w.key] ?: 0xFFFFFFFF.toInt()); pn.alpha = (80 + w.value.average() * 1.5).toInt().coerceIn(80, 230)
                cv.drawRect(min(a1.first, a2.first), min(a1.second, a2.second), max(a1.first, a2.first), max(a1.second, a2.second), pn)
            }
        } else for (p in pts) {
            val q = pr(p.x, p.y); pn.color = heat(p.pct); pn.alpha = 120
            cv.drawCircle(q.first, q.second, 26f, pn)
        }
        best?.let { val q = pr(it.first, it.second); pn.alpha = 255; pn.color = 0xFFFFFFFF.toInt(); pn.textSize = 54f; cv.drawText("★", q.first - 20f, q.second + 18f, pn) }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!gps && e.action == MotionEvent.ACTION_UP) {
            val x = ((e.x - 30f) / (width - 60f)).toDouble().coerceIn(0.0, 1.0); val y = ((e.y - 30f) / (height - 60f)).toDouble().coerceIn(0.0, 1.0)
            onTap?.invoke(x, y)
        }
        return !gps || super.onTouchEvent(e)
    }
}

@SuppressLint("MissingPermission")
class CoverageSection(private val a: MainActivity) : Tool {
    private class Pt(val c: String, val dbm: Int, val pct: Int, val x: Double, val y: Double, val gps: Boolean)
    private val file get() = File(a.filesDir, "coverage.json")
    private var data = ArrayList<Pt>()
    private var gpsMode = true
    private var sel = "ALL"      // carrier name, "ALL" or "WIN"
    private var logging = false
    private lateinit var cv: CoverView
    private lateinit var chips: LinearLayout
    private lateinit var stats: LinearLayout
    private lateinit var status: TextView
    private val palette = listOf(0xFF4F8CFF.toInt(), 0xFFFF9500.toInt(), 0xFF34C759.toInt(), 0xFFFF3B30.toInt(), 0xFFAF52DE.toInt())
    private var lastKey = ""

    private val ll = object : LocationListener {
        override fun onLocationChanged(l: Location) { if (logging && gpsMode) { if (l.accuracy <= 40f) logAt(l.longitude, l.latitude) else status.text = "Waiting for a better GPS fix (±${l.accuracy.toInt()} m)…" } }
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
        @Deprecated("old API") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
    }

    override fun view(): View {
        load()
        val p = a.page()
        p.add(a.tv("Coverage Mapper", 20f, C.TXT, true), 0)
        p.add(a.tv("Outdoors: switch on logging and walk (GPS). Indoors: choose Room mode and tap the map where you stand; GPS is too coarse inside a house or shop. Colours go red (weak) to green (strong).", 12f, C.SUB), 4)
        val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        r.addView(a.button("Outdoor (GPS)") { gpsMode = true; stopLog(); refresh() }, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(a.button("Room (tap)") { gpsMode = false; stopLog(); refresh() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = a.dp(6) })
        p.add(r, 10)
        p.add(a.button("Start / stop logging") { if (logging) stopLog() else startLog() }, 6)
        status = a.tv("", 12f, C.SUB); p.add(status, 6)
        chips = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        p.add(android.widget.HorizontalScrollView(a).apply { addView(chips) }, 6)
        cv = CoverView(a); cv.onTap = { x, y -> logRoom(x, y) }
        p.addView(cv, LinearLayout.LayoutParams(-1, a.dp(320)).apply { topMargin = a.dp(8) })
        stats = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        p.add(stats, 10)
        p.add(a.button("Clear this mode's data") { data.removeAll { it.gps == gpsMode }; save(); refresh() }, 10)
        refresh()
        return p
    }

    private fun load() {
        data.clear()
        runCatching {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); data.add(Pt(o.getString("c"), o.getInt("d"), o.getInt("p"), o.getDouble("x"), o.getDouble("y"), o.getBoolean("g"))) }
        }
    }
    private fun save() { val arr = JSONArray(); data.forEach { arr.put(JSONObject().put("c", it.c).put("d", it.dbm).put("p", it.pct).put("x", it.x).put("y", it.y).put("g", it.gps)) }; file.writeText(arr.toString()) }

    private fun startLog() {
        val need = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE).filter { a.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) { a.ask(need.toTypedArray()) { if (need.all { a.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) startLog() else a.toast("Location and phone permission are needed") }; return }
        logging = true
        if (gpsMode) {
            val lm = a.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 3f, ll) }
            runCatching { lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 3f, ll) }
            status.text = "Logging… walk slowly. Needs Location switched on."
        } else status.text = "Room mode: tap the map where you are standing."
    }

    private fun stopLog() {
        logging = false
        runCatching { (a.getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(ll) }
        if (::status.isInitialized) status.text = "Stopped. ${data.count { it.gps == gpsMode }} points."
    }

    private fun logAt(lon: Double, lat: Double) {
        val k = "%.5f,%.5f".format(lon, lat); if (k == lastKey) return; lastKey = k
        val s = SimSignal.read(a); s.forEach { data.add(Pt(it.carrier, it.dbm, it.pct, lon, lat, true)) }
        save(); a.runOnUiThread { status.text = "Logged ${data.count { it.gps }} points · " + s.joinToString { "${it.carrier} ${it.pct}%" }; refresh() }
    }

    private fun logRoom(x: Double, y: Double) {
        if (!logging) { a.toast("Start logging first"); return }
        val s = SimSignal.read(a); if (s.isEmpty()) { a.toast("No signal reading available"); return }
        s.forEach { data.add(Pt(it.carrier, it.dbm, it.pct, x, y, false)) }
        save(); status.text = s.joinToString { "${it.carrier} ${it.pct}%" }; refresh()
    }

    private fun refresh() {
        val mine = data.filter { it.gps == gpsMode }
        val carriers = mine.map { it.c }.distinct().sorted()
        val colors = carriers.mapIndexed { i, c -> c to palette[i % palette.size] }.toMap()
        if (sel != "ALL" && sel != "WIN" && sel !in carriers) sel = "ALL"
        chips.removeAllViews()
        fun chip(label: String, key: String) = chips.addView(a.button(if (sel == key) "● $label" else label) { sel = key; refresh() }.apply { setPadding(a.dp(12), 0, a.dp(12), 0) })
        chip("All", "ALL"); if (carriers.size > 1) chip("Who wins", "WIN"); carriers.forEach { chip(it, it) }
        val shown = if (sel == "ALL" || sel == "WIN") mine else mine.filter { it.c == sel }
        cv.gps = gpsMode; cv.winners = sel == "WIN"; cv.colors = colors
        cv.pts = shown.map { CoverView.P(it.c, it.pct, it.x, it.y) }
        // best spot: grid cell with best average signal
        val cell = if (gpsMode) 0.00005 else 0.1
        val cells = shown.groupBy { Pair(Math.floor(it.x / cell).toLong(), Math.floor(it.y / cell).toLong()) }
        val bestCell = cells.maxByOrNull { e -> e.value.map { it.pct }.average() }
        cv.best = bestCell?.let { Pair(it.value.map { p -> p.x }.average(), it.value.map { p -> p.y }.average()) }
        cv.invalidate()
        val rows = ArrayList<Row>()
        rows += Row("RESULTS", header = true)
        if (bestCell == null) rows += Row("Data", "No points yet")
        else {
            val avg = bestCell.value.map { it.pct }.average().toInt()
            val who = bestCell.value.groupBy { it.c }.maxByOrNull { e -> e.value.map { it.pct }.average() }?.key ?: ""
            val bx = bestCell.value.map { it.x }.average(); val by = bestCell.value.map { it.y }.average()
            rows += if (gpsMode) Row("Best spot ★", "$who · $avg% signal\n%.5f, %.5f (tap to open map)".format(by, bx), "https://maps.google.com/?q=$by,$bx")
                    else Row("Best spot ★", "$who · $avg% signal\nstarred on the map")
        }
        carriers.forEach { c ->
            val l = mine.filter { it.c == c }
            rows += Row(c, "avg ${l.map { it.pct }.average().toInt()}% · best ${l.maxOf { it.pct }}% · worst ${l.minOf { it.pct }}% · ${l.size} pts")
        }
        if (carriers.size > 1) {
            val g = mine.groupBy { Pair(Math.floor(it.x / cell).toLong(), Math.floor(it.y / cell).toLong()) }
            val wins = HashMap<String, Int>()
            g.values.forEach { l -> l.groupBy { it.c }.maxByOrNull { e -> e.value.map { it.pct }.average() }?.let { wins[it.key] = (wins[it.key] ?: 0) + 1 } }
            val total = max(1, wins.values.sum())
            rows += Row("WHO WINS WHERE YOU LIVE", header = true)
            wins.entries.sortedByDescending { it.value }.forEach { rows += Row(it.key, "best in ${it.value * 100 / total}% of visited spots") }
        }
        stats.removeAllViews(); stats.addView(a.table(rows)); stats.applyTypeface(a.tf); chips.applyTypeface(a.tf)
    }

    override fun stop() { stopLog() }
}
