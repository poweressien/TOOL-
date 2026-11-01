package com.inwekobong.ekpe

import android.annotation.SuppressLint
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Offline OpenCelliD tower database (SQLite). Radar uses it automatically once it is downloaded. */
@SuppressLint("MissingPermission")
object TowerDb {
    private var db: SQLiteDatabase? = null
    private val cache = HashMap<String, Triple<Double, Double, Int>?>()
    private var loc: Location? = null
    private var locAt = 0L

    private fun file(c: Context) = File(c.filesDir, "towers.db")

    @Synchronized private fun open(c: Context): SQLiteDatabase? {
        db?.let { if (it.isOpen) return it }
        if (!file(c).exists()) return null
        return try { SQLiteDatabase.openDatabase(file(c).path, null, SQLiteDatabase.OPEN_READWRITE).also { db = it } } catch (e: Exception) { null }
    }

    fun rows(c: Context): Long = try { open(c)?.let { android.database.DatabaseUtils.queryNumEntries(it, "cells") } ?: 0L } catch (e: Exception) { 0L }

    fun download(c: Context, key: String, mcc: String, say: (String) -> Unit): Long {
        synchronized(this) { db?.close(); db = null }
        val d = SQLiteDatabase.openOrCreateDatabase(file(c), null)
        d.execSQL("CREATE TABLE IF NOT EXISTS cells(radio TEXT, mcc INT, net INT, area INT, cell INTEGER, lat REAL, lon REAL, range INT)")
        d.execSQL("CREATE INDEX IF NOT EXISTS ix ON cells(mcc, net, cell)")
        d.execSQL("DELETE FROM cells WHERE mcc = ?", arrayOf(mcc.toInt()))
        val con = URL("https://opencellid.org/ocid/downloads?token=$key&type=mcc&file=$mcc.csv.gz").openConnection() as HttpURLConnection
        con.connectTimeout = 15000; con.readTimeout = 60000
        if (con.responseCode != 200) { val m = runCatching { con.errorStream?.bufferedReader()?.readText()?.take(160) }.getOrNull(); d.close(); throw Exception("Server said ${con.responseCode} ${m ?: ""}") }
        val st = d.compileStatement("INSERT INTO cells VALUES(?,?,?,?,?,?,?,?)")
        var n = 0L
        d.beginTransaction()
        try {
            GZIPInputStream(con.inputStream).bufferedReader().useLines { seq ->
                for (line in seq) {
                    val f = line.split(','); if (f.size < 9 || f[0] == "radio") continue
                    val cell = f[4].toLongOrNull() ?: continue
                    st.clearBindings(); st.bindString(1, f[0]); st.bindLong(2, f[1].toLongOrNull() ?: continue); st.bindLong(3, f[2].toLongOrNull() ?: continue)
                    st.bindLong(4, f[3].toLongOrNull() ?: 0); st.bindLong(5, cell); st.bindDouble(6, f[7].toDoubleOrNull() ?: continue)
                    st.bindDouble(7, f[6].toDoubleOrNull() ?: continue); st.bindLong(8, f[8].toLongOrNull() ?: 0)
                    st.executeInsert(); n++
                    if (n % 20000 == 0L) say("Imported $n towers…")
                }
            }
            d.setTransactionSuccessful()
        } finally { d.endTransaction(); d.close(); cache.clear() }
        return n
    }

    fun find(c: Context, mcc: Int, mnc: Int, cell: Long): Triple<Double, Double, Int>? {
        val k = "$mcc-$mnc-$cell"
        synchronized(cache) { if (cache.containsKey(k)) return cache[k] }
        val d = open(c) ?: return null
        val r = d.rawQuery("SELECT lat, lon, range FROM cells WHERE mcc=? AND net=? AND cell=? LIMIT 1", arrayOf("$mcc", "$mnc", "$cell")).use {
            if (it.moveToFirst()) Triple(it.getDouble(0), it.getDouble(1), it.getInt(2)) else null
        }
        synchronized(cache) { cache[k] = r }
        return r
    }

    fun phone(c: Context): Location? {
        val now = SystemClock.elapsedRealtime()
        if (now - locAt < 5000) return loc
        locAt = now
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        loc = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        return loc
    }

    fun bearing(a: Location, lat: Double, lon: Double): Pair<Double, String> {
        val p1 = Math.toRadians(a.latitude); val p2 = Math.toRadians(lat); val dl = Math.toRadians(lon - a.longitude)
        val b = (Math.toDegrees(atan2(sin(dl) * cos(p2), cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl))) + 360) % 360
        val dirs = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return Pair(b, dirs[((b + 22.5) / 45).toInt() % 8])
    }

    fun distance(a: Location, lat: Double, lon: Double): Double {
        val r = FloatArray(1); Location.distanceBetween(a.latitude, a.longitude, lat, lon, r); return r[0].toDouble()
    }

    /** Offline database first, then the OpenCelliD online lookup (cached) when the user saved a key. */
    fun resolve(c: Context, mcc: String, mnc: String, lac: Long, cell: Long): Triple<Double, Double, Int>? {
        val m = mcc.toIntOrNull() ?: return null
        val n = mnc.toIntOrNull() ?: return null
        return find(c, m, n, cell) ?: TowerOnline.get(c, m, n, lac, cell)
    }

    /** Used by RadarEngine: true distance in metres + a bearing label, or null if unknown. */
    fun locate(c: Context, mcc: String, mnc: String, cell: Long): Pair<Double, String>? {
        val t = find(c, mcc.toIntOrNull() ?: return null, mnc.toIntOrNull() ?: return null, cell) ?: return null
        val me = phone(c) ?: return null
        val b = bearing(me, t.first, t.second)
        return Pair(distance(me, t.first, t.second), "${b.second} ${b.first.roundToInt()}°")
    }
}

/** Per-tower lookup against api.opencellid.org with a persistent cache. Needs the key saved in Tower Locator. */
object TowerOnline {
    private val mem = HashMap<String, Triple<Double, Double, Int>?>()
    private val tried = HashMap<String, Long>()
    private val busy = HashSet<String>()
    private var loaded = false

    private fun cacheFile(c: Context) = File(c.filesDir, "tower_cache.json")

    @Synchronized private fun load(c: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val o = JSONObject(cacheFile(c).readText())
            for (k in o.keys()) { val a = o.getJSONArray(k); mem[k] = Triple(a.getDouble(0), a.getDouble(1), a.getInt(2)) }
        }
    }

    @Synchronized private fun save(c: Context) {
        runCatching {
            val o = JSONObject()
            for ((k, v) in mem) if (v != null) o.put(k, org.json.JSONArray().put(v.first).put(v.second).put(v.third))
            cacheFile(c).writeText(o.toString())
        }
    }

    /** Returns the cached position now; starts a background fetch when it is unknown. */
    fun get(c: Context, mcc: Int, mnc: Int, lac: Long, cell: Long): Triple<Double, Double, Int>? {
        val ctx = c.applicationContext
        val key = "$mcc-$mnc-$cell"
        load(ctx)
        synchronized(this) {
            mem[key]?.let { return it }
            val k = ctx.prefs().getString("ocid_key", "").orEmpty()
            if (k.isEmpty() || cell <= 0L) return null
            val now = SystemClock.elapsedRealtime()
            if (key in busy || now - (tried[key] ?: -1_000_000L) < 600_000L) return null
            tried[key] = now; busy.add(key)
        }
        Thread {
            try {
                val k = ctx.prefs().getString("ocid_key", "").orEmpty()
                val u = "https://opencellid.org/cell/get?key=$k&mcc=$mcc&mnc=$mnc&lac=$lac&cellid=$cell&format=json"
                val con = URL(u).openConnection() as HttpURLConnection
                con.connectTimeout = 8000; con.readTimeout = 8000
                if (con.responseCode == 200) {
                    val j = JSONObject(con.inputStream.bufferedReader().readText())
                    if (j.has("lat") && j.has("lon")) {
                        synchronized(this) { mem[key] = Triple(j.getDouble("lat"), j.getDouble("lon"), j.optInt("range", 0)) }
                        save(ctx)
                    }
                }
            } catch (e: Exception) { }
            synchronized(this) { busy.remove(key) }
        }.start()
        return null
    }
}

class TowerLocatorSection(private val a: MainActivity) : Tool {
    private lateinit var key: EditText
    private lateinit var mcc: EditText
    private lateinit var status: TextView
    private lateinit var out: LinearLayout

    @SuppressLint("MissingPermission")
    override fun view(): View {
        val p = a.page()
        p.add(a.tv("Tower Locator", 20f, C.TXT, true), 0)
        p.add(a.tv("Download the tower database for your country once (free OpenCelliD key from opencellid.org). After that Radar shows true tower distance and bearing instead of an estimate. Positions come from crowd-sourced data and can be off by hundreds of metres.", 12f, C.SUB), 4)
        key = a.edit("OpenCelliD API key").apply { setText(a.prefs().getString("ocid_key", "")) }
        p.add(key, 10)
        val guess = runCatching { a.getSystemService(TelephonyManager::class.java).networkOperator.take(3) }.getOrNull().orEmpty().ifEmpty { "621" }
        mcc = a.edit("Country code (MCC), Nigeria = 621", true).apply { setText(a.prefs().getString("ocid_mcc", guess)) }
        p.add(mcc, 6)
        val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        r.addView(a.button("Download database") { download() }, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(a.button("Locate my towers") { locate() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = a.dp(6) })
        p.add(r, 8)
        status = a.tv("Database: ${TowerDb.rows(a)} towers stored.", 12f, C.SUB); p.add(status, 8)
        out = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }; p.add(out, 8)
        return p
    }

    private fun download() {
        val k = key.text.toString().trim(); val m = mcc.text.toString().trim()
        if (k.isEmpty() || m.toIntOrNull() == null) { a.toast("Enter your key and a numeric MCC"); return }
        a.prefs().edit().putString("ocid_key", k).putString("ocid_mcc", m).apply()
        status.text = "Downloading…"
        Thread {
            val r = runCatching { TowerDb.download(a, k, m) { s -> a.runOnUiThread { status.text = s } } }
            a.runOnUiThread { status.text = if (r.isSuccess) "Done: ${r.getOrNull()} towers imported. Radar now uses real positions." else "Failed: ${r.exceptionOrNull()?.message}" }
        }.start()
    }

    private fun locate() {
        GuardPerm.startRadar(a) {
            val cells = JSONObject(RadarEngine.snapshot()).optJSONArray("cells")
            val me = TowerDb.phone(a)
            val rows = ArrayList<Row>()
            if (me == null) rows += Row("Location", "No phone location yet. Switch Location on and wait for a fix.")
            if (cells == null || cells.length() == 0) rows += Row("Cells", "No cell info yet (Radar needs a few seconds, plus phone permission).")
            else for (i in 0 until cells.length()) {
                val c = cells.getJSONObject(i)
                val t = TowerDb.resolve(a, c.optString("mcc"), c.optString("mnc"), c.optLong("area"), c.optLong("id"))
                val title = (if (c.optBoolean("reg")) "Serving " else "Neighbor ") + c.optString("rat")
                if (t == null) rows += Row(title, "Not in database (cell ${c.optLong("id")})")
                else {
                    val extra = if (me != null) { val b = TowerDb.bearing(me, t.first, t.second); "\n${(TowerDb.distance(me, t.first, t.second) / 100).roundToInt() / 10.0} km towards ${b.second} (${b.first.roundToInt()}°)" } else ""
                    rows += Row(title, "%.5f, %.5f".format(t.first, t.second) + extra + "\nrange ±${t.third} m · tap for map", "https://maps.google.com/?q=${t.first},${t.second}")
                }
            }
            out.removeAllViews(); out.addView(a.table(rows)); out.applyTypeface(a.tf)
        }
    }
}
