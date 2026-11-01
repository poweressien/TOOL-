package com.inwekobong.ekpe

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanSettings
import android.bluetooth.le.ScanResult as BleResult
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Local-only scanning engine. Runs inside the foreground service so alerts keep firing in the background. */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
object RadarEngine {
    private lateinit var ctx: Context
    private var on = false
    private var bleOn = false
    private var ticks = 0
    private val warm: Boolean get() = ticks >= 2
    private val h = Handler(Looper.getMainLooper())

    private val ble = ConcurrentHashMap<String, JSONObject>()
    @Volatile private var wifiJ = JSONArray()
    @Volatile private var cellJ = JSONArray()
    @Volatile private var netJ = JSONObject()
    @Volatile private var meJ: JSONObject? = null
    @Volatile private var emf = 0.0
    private var emfAvg = 0.0
    private val log = ArrayList<JSONObject>()
    private val seen = HashSet<String>()
    private val ssidOpen = HashMap<String, Boolean>()
    private val lastAlert = HashMap<String, Long>()
    private var lastCell = ""
    private var lastRat = ""
    private var lastType = ""
    private var nid = 5000

    @Volatile var radius = 3
    @Volatile var emfDelta = 20

    fun cfg(r: Int, e: Int) { radius = r; emfDelta = e }

    fun mkChannel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("radar_svc", "Radar background scanning", NotificationManager.IMPORTANCE_LOW)
        )
    }

    fun start(c: Context) {
        if (on) return
        on = true
        ctx = c.applicationContext
        mkChannel(ctx)
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sm.registerListener(magL, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        startBle()
        h.post(tick)
    }

    /** True while the scanner loop is running. */
    val active: Boolean get() = on

    /** Stops the scan loop, the magnetometer and the BLE scan. start() can be called again later. */
    fun stop() {
        if (!on) return
        on = false
        runCatching { h.removeCallbacks(tick) }
        runCatching { (ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(magL) }
        runCatching { (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bluetoothLeScanner?.stopScan(cb) }
        bleOn = false
    }

    // ---------- alerts ----------
    private fun alert(key: String, title: String, text: String, gap: Long = 15000) {
        val now = SystemClock.elapsedRealtime()
        if (now - (lastAlert[key] ?: 0L) < gap) return
        lastAlert[key] = now
        notify(title, text)
    }

    fun notify(title: String, text: String) {
        synchronized(log) {
            log.add(0, JSONObject().put("t", System.currentTimeMillis()).put("title", title).put("text", text))
            if (log.size > 60) log.removeAt(log.size - 1)
        }
        val open = PendingIntent.getActivity(ctx, 41,
            Intent(ctx, MainActivity::class.java).putExtra("tab", 7).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, com.toolkit.privacy.SoundCatalog.alertChannel(ctx, "radar_alerts", "Radar detection alerts"))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(nid++, n)
    }

    private fun r1(x: Double) = (min(x, 99999.0) * 10).roundToInt() / 10.0

    // ---------- electromagnetic field ----------
    private val magL = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val m = sqrt((e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble())
            emf = m
            if (emfAvg == 0.0) emfAvg = m
            if (warm && abs(m - emfAvg) > emfDelta) {
                alert("emf", "Strong electromagnetic source", "Field ${m.roundToInt()} µT vs normal ${emfAvg.roundToInt()} µT", 20000)
            }
            emfAvg = emfAvg * 0.98 + m * 0.02
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    // ---------- bluetooth LE ----------
    private val cb = object : ScanCallback() {
        override fun onScanResult(t: Int, r: BleResult) {
            val a = r.device.address
            val rec = r.scanRecord
            val tx = rec?.txPowerLevel?.takeIf { it > -100 && it < 30 } ?: -59
            val name = (try { r.device.name } catch (e: Exception) { null }) ?: rec?.deviceName ?: ""
            var co = -1
            rec?.manufacturerSpecificData?.let { if (it.size() > 0) co = it.keyAt(0) }
            val uu = JSONArray()
            rec?.serviceUuids?.take(3)?.forEach { uu.put(it.uuid.toString().substring(4, 8)) }
            val d = 10.0.pow((tx - r.rssi) / 25.0)
            ble[a] = JSONObject().put("a", a).put("n", name).put("r", r.rssi).put("co", co)
                .put("u", uu).put("d", r1(d)).put("ts", SystemClock.elapsedRealtime())
            val fresh = seen.add("b$a")
            if (warm && fresh && d <= radius) {
                alert("ble", "Bluetooth device nearby", "${name.ifEmpty { "Unknown device" }} about ${r1(d)} m away ($a)", 8000)
            }
        }
    }

    private fun startBle() {
        if (bleOn) return
        try {
            val ad = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            if (ad?.isEnabled == true) {
                ad.bluetoothLeScanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
                bleOn = true
            }
        } catch (e: Exception) { }
    }

    // ---------- main loop ----------
    private val tick = object : Runnable {
        override fun run() {
            runCatching { startBle() }
            runCatching { wifi() }
            runCatching { cells() }
            runCatching { net() }
            ticks++
            h.postDelayed(this, 4000)
        }
    }

    private fun wifi() {
        val wm = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try { wm.startScan() } catch (e: Exception) { }
        val cur = try { wm.connectionInfo?.bssid } catch (e: Exception) { null }
        val arr = JSONArray()
        for (s in wm.scanResults) {
            val f = s.frequency
            val ch = when {
                f in 2400..2500 -> if (f == 2484) 14 else (f - 2407) / 5
                f >= 5925 -> (f - 5950) / 5
                else -> (f - 5000) / 5
            }
            val cap = s.capabilities ?: ""
            val sec = when {
                cap.contains("SAE") -> "WPA3"
                cap.contains("WPA2") || cap.contains("RSN") -> "WPA2"
                cap.contains("WPA") -> "WPA"
                cap.contains("WEP") -> "WEP"
                else -> "Open"
            }
            val wd = when (s.channelWidth) { 1 -> 40; 2 -> 80; 3, 4 -> 160; else -> 20 }
            val std = if (Build.VERSION.SDK_INT >= 30) s.wifiStandard else 0
            val ssid = s.SSID ?: ""
            val d = 10.0.pow((27.55 - 20 * log10(f.toDouble()) + abs(s.level)) / 20.0)
            arr.put(
                JSONObject().put("s", ssid).put("b", s.BSSID).put("r", s.level).put("f", f).put("ch", ch)
                    .put("sec", sec).put("w", wd).put("std", std).put("d", r1(d)).put("c", s.BSSID == cur)
            )
            val fresh = seen.add("w" + s.BSSID)
            if (warm && fresh && ssid.isNotEmpty()) {
                alert("wifi", "New Wi-Fi network", "$ssid · ${s.level} dBm · about ${r1(d)} m", 10000)
            }
            if (ssid.isNotEmpty()) {
                val open = sec == "Open"
                val old = ssidOpen.put(ssid, open)
                if (warm && old != null && old != open) {
                    alert("twin", "Possible rogue hotspot", "$ssid is broadcasting with different security settings. Avoid connecting.", 60000)
                }
            }
        }
        wifiJ = arr
    }

    private fun plmnOld(x: Int): String = if (x == Int.MAX_VALUE || x < 0) "" else x.toString()
    private fun has(x: Int) = x != Int.MAX_VALUE

    private val OPS = mapOf(
        "621-20" to "Airtel Nigeria", "621-30" to "MTN Nigeria", "621-50" to "Glo (Globacom)", "621-60" to "9mobile",
        "620-01" to "MTN Ghana", "620-02" to "Vodafone Ghana", "639-02" to "Safaricom", "655-01" to "Vodacom SA", "655-10" to "MTN South Africa",
        "310-410" to "AT&T", "310-260" to "T-Mobile US", "311-480" to "Verizon", "234-10" to "O2 UK", "234-15" to "Vodafone UK", "234-20" to "Three UK", "234-30" to "EE"
    )

    fun opName(mcc: String, mnc: String): String {
        val m = mnc.trimStart('0').ifEmpty { "0" }
        return OPS["$mcc-${m.padStart(2, '0')}"] ?: OPS["$mcc-$m"] ?: ""
    }

    private fun lteBand(e: Int): Int = when (e) {
        in 0..599 -> 1; in 600..1199 -> 2; in 1200..1949 -> 3; in 1950..2399 -> 4; in 2400..2649 -> 5; in 2750..3449 -> 7
        in 3450..3799 -> 8; in 5010..5179 -> 12; in 5180..5279 -> 13; in 5730..5849 -> 17; in 6150..6449 -> 20
        in 8040..8689 -> 25; in 8690..9039 -> 26; in 9210..9659 -> 28; in 37750..38249 -> 38; in 38250..38649 -> 39
        in 38650..39649 -> 40; in 39650..41589 -> 41; in 66436..67335 -> 66; else -> 0
    }
    private val LTE_MHZ = mapOf(1 to 2100, 2 to 1900, 3 to 1800, 4 to 1700, 5 to 850, 7 to 2600, 8 to 900, 12 to 700, 13 to 700, 17 to 700,
        20 to 800, 25 to 1900, 26 to 850, 28 to 700, 38 to 2600, 39 to 1900, 40 to 2300, 41 to 2500, 66 to 1700)

    private fun gsmBand(a: Int): String = when (a) {
        in 1..124, in 955..1023 -> "GSM 900"; in 128..251 -> "GSM 850"; in 512..885 -> "DCS 1800"; else -> ""
    }
    private fun umtsBand(a: Int): String = when (a) {
        in 10562..10838 -> "B1 2100"; in 9662..9938 -> "B2 1900"; in 1537..1738 -> "B4 AWS"; in 4357..4458 -> "B5 850"
        in 2937..3088 -> "B8 900"; else -> ""
    }
    private fun nrMhz(n: Int): Double = when {
        n < 0 -> 0.0
        n < 600000 -> n * 0.005
        n < 2016667 -> 3000 + (n - 600000) * 0.015
        else -> 24250 + (n - 2016667) * 0.06
    }
    private fun nrBand(f: Double): String = when {
        f in 3300.0..4200.0 -> "n78 3.5 GHz"; f in 2496.0..2690.0 -> "n41 2.5 GHz"; f in 1805.0..1880.0 -> "n3 1800"
        f in 2110.0..2170.0 -> "n1 2100"; f in 758.0..803.0 -> "n28 700"; f in 2300.0..2400.0 -> "n40 2300"; else -> ""
    }

    @SuppressLint("NewApi")
    private fun nr(c: CellInfoNr, o: JSONObject): Int {
        val i = c.cellIdentity as CellIdentityNr
        o.put("rat", "5G NR").put("mcc", i.mccString ?: "").put("mnc", i.mncString ?: "").put("id", i.nci)
            .put("area", i.tac).put("pci", i.pci).put("ch", i.nrarfcn)
        return c.cellSignalStrength.dbm
    }

    @SuppressLint("NewApi")
    private fun cells() {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val me = TowerDb.phone(ctx)
        val arr = JSONArray()
        var regCell = ""
        var regRat = ""
        for (c in (tm.allCellInfo ?: emptyList<CellInfo>())) {
            val o = JSONObject()
            var ta = Int.MAX_VALUE
            var unit = 78.12
            var dbm: Int
            var strong = -999
            if (c is CellInfoLte) {
                val i = c.cellIdentity
                val s = c.cellSignalStrength
                val band = (if (Build.VERSION.SDK_INT >= 28) i.bands.firstOrNull() else null) ?: lteBand(i.earfcn)
                o.put("rat", "LTE").put("id", i.ci).put("area", i.tac).put("pci", i.pci).put("ch", i.earfcn)
                if (has(i.ci) && i.ci > 255) o.put("enb", i.ci shr 8).put("sec", i.ci and 0xFF)
                if (band > 0) o.put("band", "B$band ${LTE_MHZ[band] ?: ""}".trim())
                if (has(s.rsrp)) o.put("rsrp", s.rsrp)
                if (has(s.rsrq)) o.put("rsrq", s.rsrq)
                if (has(s.rssnr)) o.put("snr", s.rssnr)
                if (Build.VERSION.SDK_INT >= 28) { o.put("mcc", i.mccString ?: "").put("mnc", i.mncString ?: ""); o.put("opn", i.operatorAlphaLong?.toString() ?: "") }
                else o.put("mcc", plmnOld(i.mcc)).put("mnc", plmnOld(i.mnc))
                ta = s.timingAdvance
                dbm = s.dbm
                strong = -70
            } else if (c is CellInfoGsm) {
                val i = c.cellIdentity
                val s = c.cellSignalStrength
                o.put("rat", "GSM").put("id", i.cid).put("area", i.lac).put("pci", i.bsic).put("ch", i.arfcn)
                gsmBand(i.arfcn).let { if (it.isNotEmpty()) o.put("band", it) }
                if (Build.VERSION.SDK_INT >= 28) { o.put("mcc", i.mccString ?: "").put("mnc", i.mncString ?: ""); o.put("opn", i.operatorAlphaLong?.toString() ?: "") }
                else o.put("mcc", plmnOld(i.mcc)).put("mnc", plmnOld(i.mnc))
                ta = s.timingAdvance
                unit = 553.85
                dbm = s.dbm
                strong = -60
            } else if (c is CellInfoWcdma) {
                val i = c.cellIdentity
                o.put("rat", "3G").put("id", i.cid).put("area", i.lac).put("pci", i.psc).put("ch", i.uarfcn)
                umtsBand(i.uarfcn).let { if (it.isNotEmpty()) o.put("band", it) }
                if (Build.VERSION.SDK_INT >= 28) { o.put("mcc", i.mccString ?: "").put("mnc", i.mncString ?: ""); o.put("opn", i.operatorAlphaLong?.toString() ?: "") }
                else o.put("mcc", plmnOld(i.mcc)).put("mnc", plmnOld(i.mnc))
                dbm = c.cellSignalStrength.dbm
                strong = -60
            } else if (Build.VERSION.SDK_INT >= 29 && c is CellInfoNr) {
                dbm = nr(c, o)
                val f = nrMhz(o.optInt("ch", -1))
                if (f > 0) o.put("band", nrBand(f).ifEmpty { "%.0f MHz".format(f) })
                val s = c.cellSignalStrength as android.telephony.CellSignalStrengthNr
                if (has(s.ssRsrp)) o.put("rsrp", s.ssRsrp) else if (has(s.csiRsrp)) o.put("rsrp", s.csiRsrp)
                if (has(s.ssRsrq)) o.put("rsrq", s.ssRsrq) else if (has(s.csiRsrq)) o.put("rsrq", s.csiRsrq)
                if (has(s.ssSinr)) o.put("snr", s.ssSinr) else if (has(s.csiSinr)) o.put("snr", s.csiSinr)
                strong = -70
            } else continue
            val valid = dbm in -150..-30
            if (!valid) dbm = -140
            val reg = c.isRegistered
            val mcc = o.optString("mcc"); val mnc = o.optString("mnc")
            o.put("op", opName(mcc, mnc).ifEmpty { o.optString("opn") })
            o.put("dbm", dbm).put("pct", if (valid) ((dbm + 140) * 100 / 96).coerceIn(0, 100) else 0).put("reg", reg).put("ok", valid)

            // ---- distance: timing advance (measured) > tower database (measured position) > path-loss (estimate) ----
            var d = 0.0
            var src = "n/a"
            var vc = 0
            if (has(ta) && ta >= 0 && reg) {
                d = ta * unit + unit / 2
                src = "TA"
                o.put("ta", ta).put("dmin", (ta * unit).roundToInt()).put("dmax", ((ta + 1) * unit).roundToInt())
            }
            val areaCode = o.optLong("area")
            val tw = try { TowerDb.resolve(ctx, mcc, mnc, areaCode, o.optLong("id")) } catch (e: Exception) { null }
            if (tw != null) {
                o.put("lat", tw.first).put("lon", tw.second).put("acc", tw.third)
                if (me != null) {
                    val dd = TowerDb.distance(me, tw.first, tw.second)
                    val b = TowerDb.bearing(me, tw.first, tw.second)
                    o.put("brg", "${b.second} ${b.first.roundToInt()}°").put("deg", b.first.roundToInt())
                    if (src != "TA") { d = dd; src = "DB" }
                    o.put("ddb", dd.roundToInt())
                }
            }
            if (src == "n/a" && valid) {
                d = 10.0.pow(((30 - dbm) - 128.1) / 37.6) * 1000
                src = "est"
            }
            if (src != "n/a") o.put("d", min(d, 99999.0).roundToInt()) else o.put("d", -1)
            o.put("src", src)
            val dm = if (o.has("ddb")) o.getInt("ddb") else if (src == "TA") o.getInt("dmin") else Int.MAX_VALUE
            vc = if ((src == "TA" || src == "DB") && dm <= 200) 2 else if (src == "est" && dbm >= strong) 1 else 0
            o.put("vc", vc)
            if (reg) {
                regCell = "${o.opt("mcc")}-${o.opt("mnc")}-${o.opt("id")}"
                regRat = o.getString("rat")
            }
            if (vc == 2) alert("vc" + o.opt("id"), "Cell tower very close", "${o.optString("op").ifEmpty { "Tower" }} ${o.getString("rat")} cell ${o.opt("id")} is within about ${dm.coerceAtLeast(0)}–${min(d.roundToInt() + 100, 200)} m", 120000)
            arr.put(o)
        }
        cellJ = arr
        if (me != null) meJ = JSONObject().put("lat", me.latitude).put("lon", me.longitude).put("acc", me.accuracy.roundToInt())
        if (regCell.isNotEmpty()) {
            if (warm && lastCell.isNotEmpty() && regCell != lastCell) {
                alert("cell", "Cell tower changed", "Now connected to $regRat tower $regCell", 20000)
            }
            val modern = lastRat == "LTE" || lastRat == "5G NR"
            if (warm && modern && (regRat == "GSM" || regRat == "3G")) {
                alert("down", "Network downgraded to $regRat", "Weak coverage, or a fake base station forcing an older network.", 60000)
            }
            lastCell = regCell
            lastRat = regRat
        }
    }

    private fun net() {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        val wm = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val type = when {
            caps == null -> "Offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Other"
        }
        val o = JSONObject().put("type", type)
            .put("dl", caps?.linkDownstreamBandwidthKbps ?: 0)
            .put("ul", caps?.linkUpstreamBandwidthKbps ?: 0)
            .put("net", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: false)
        if (type == "Wi-Fi") {
            val wi = wm.connectionInfo
            if (wi != null) {
                val pct = ((wi.rssi + 100) * 2).coerceIn(0, 100)
                val ssid = wi.ssid?.trim('"') ?: ""
                o.put("ssid", ssid).put("rssi", wi.rssi).put("link", wi.linkSpeed)
                    .put("freq", wi.frequency).put("pct", pct)
                if (warm && pct < 25) alert("weak", "Weak Wi-Fi signal", "$ssid is at $pct%", 60000)
            }
        }
        try {
            o.put("op", tm.networkOperatorName).put("gen", tm.dataNetworkType).put("roam", tm.isNetworkRoaming)
        } catch (e: Exception) { }
        o.put("rx", TrafficStats.getTotalRxBytes()).put("tx", TrafficStats.getTotalTxBytes())
        if (warm && type == "Offline" && lastType != "Offline" && lastType.isNotEmpty()) {
            alert("off", "Connection lost", "No active network", 30000)
        }
        lastType = type
        netJ = o
    }

    /** Called by the UI every ~1.5 s. */
    fun snapshot(): String {
        val now = SystemClock.elapsedRealtime()
        val b = JSONArray()
        for (v in ble.values) {
            val age = now - v.getLong("ts")
            if (age < 30000) b.put(JSONObject(v.toString()).put("age", age / 1000)) else ble.remove(v.getString("a"))
        }
        val l = JSONArray()
        synchronized(log) { log.forEach { l.put(it) } }
        return JSONObject().put("wifi", wifiJ).put("ble", b).put("cells", cellJ).put("net", netJ).put("me", meJ ?: JSONObject.NULL)
            .put("emf", emf).put("base", emfAvg).put("log", l).put("ts", now).toString()
    }
}
