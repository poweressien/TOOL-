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
            NotificationChannel("radar_alerts", "Radar detection alerts", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) }
        )
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
        val n = Notification.Builder(ctx, "radar_alerts")
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

    @SuppressLint("NewApi")
    private fun nr(c: CellInfoNr, o: JSONObject): Int {
        val i = c.cellIdentity as CellIdentityNr
        o.put("rat", "5G NR").put("mcc", i.mccString ?: "").put("mnc", i.mncString ?: "").put("id", i.nci)
            .put("area", i.tac).put("pci", i.pci).put("ch", i.nrarfcn)
        return c.cellSignalStrength.dbm
    }

    private fun cells() {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val arr = JSONArray()
        var regCell = ""
        var regRat = ""
        for (c in (tm.allCellInfo ?: emptyList<CellInfo>())) {
            val o = JSONObject()
            var ta = Int.MAX_VALUE
            var dbm: Int
            if (c is CellInfoLte) {
                val i = c.cellIdentity
                o.put("rat", "LTE").put("id", i.ci).put("area", i.tac).put("pci", i.pci).put("ch", i.earfcn)
                if (Build.VERSION.SDK_INT >= 28) o.put("mcc", i.mccString ?: "").put("mnc", i.mncString ?: "")
                else o.put("mcc", plmnOld(i.mcc)).put("mnc", plmnOld(i.mnc))
                ta = c.cellSignalStrength.timingAdvance
                dbm = c.cellSignalStrength.dbm
            } else if (c is CellInfoGsm) {
                val i = c.cellIdentity
                o.put("rat", "GSM").put("id", i.cid).put("area", i.lac).put("pci", i.bsic).put("ch", i.arfcn)
                if (Build.VERSION.SDK_INT >= 28) o.put("mcc", i.mccString ?: "").put("mnc", i.mncString ?: "")
                else o.put("mcc", plmnOld(i.mcc)).put("mnc", plmnOld(i.mnc))
                dbm = c.cellSignalStrength.dbm
            } else if (c is CellInfoWcdma) {
                val i = c.cellIdentity
                o.put("rat", "3G").put("id", i.cid).put("area", i.lac).put("pci", i.psc).put("ch", i.uarfcn)
                if (Build.VERSION.SDK_INT >= 28) o.put("mcc", i.mccString ?: "").put("mnc", i.mncString ?: "")
                else o.put("mcc", plmnOld(i.mcc)).put("mnc", plmnOld(i.mnc))
                dbm = c.cellSignalStrength.dbm
            } else if (Build.VERSION.SDK_INT >= 29 && c is CellInfoNr) {
                dbm = nr(c, o)
            } else continue
            if (dbm > -30 || dbm < -150) dbm = -140
            val reg = c.isRegistered
            var d = 10.0.pow(((30 - dbm) - 128.1) / 37.6) * 1000
            var src = "est"
            if (ta != Int.MAX_VALUE && ta > 0) { d = ta * 78.12; src = "TA" }
            try {
                val tw = TowerDb.locate(ctx, o.optString("mcc"), o.optString("mnc"), o.optLong("id"))
                if (tw != null) { d = tw.first; src = "DB"; o.put("brg", tw.second) }
            } catch (e: Exception) { }
            o.put("dbm", dbm).put("pct", ((dbm + 140) * 100 / 96).coerceIn(0, 100))
                .put("reg", reg).put("d", min(d, 99999.0).roundToInt()).put("src", src)
            if (reg) {
                regCell = "${o.opt("mcc")}-${o.opt("mnc")}-${o.opt("id")}"
                regRat = o.getString("rat")
            }
            arr.put(o)
        }
        cellJ = arr
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
        return JSONObject().put("wifi", wifiJ).put("ble", b).put("cells", cellJ).put("net", netJ)
            .put("emf", emf).put("base", emfAvg).put("log", l).put("ts", now).toString()
    }
}
