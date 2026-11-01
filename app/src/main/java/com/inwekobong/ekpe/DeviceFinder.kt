package com.inwekobong.ekpe

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.toolkit.privacy.MacVendor
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.pow
import android.bluetooth.le.ScanResult as BleResult
import android.net.wifi.ScanResult as WifiResult

/** One thing found nearby, merged from every scanner that sees it. */
class NearDev(val id: String) {
    var name = ""
    var addr = ""
    var type = "Unknown"
    var vendor = ""
    val src = linkedSetOf<String>()
    var rssi = -127.0
    var last = 0L
    var first = 0L
    var bonded = false
    var connected = false
    var seenBefore = false
    var freq = 0
    var txp = Int.MIN_VALUE
    var rtt = -1
    var ip = ""
    val info = linkedMapOf<String, String>()
    var vendorTried = false
    val hasSignal get() = rssi > -126.0
    val known get() = bonded || connected || seenBefore || src.contains("LAN")
    val title get() = name.ifEmpty { vendor.ifEmpty { "$type ${addr.takeLast(8)}" } }
}

/**
 * Device Finder: a real multi-protocol nearby scanner.
 * Bluetooth LE, Bluetooth Classic, Wi-Fi access points, Wi-Fi Direct peers, mDNS/Bonjour services and the local
 * network (ping + port probe) all feed one list. Tap a device to track it hot / cold with beep and vibration.
 */
@SuppressLint("MissingPermission", "HardwareIds")
class DeviceFinderSection(private val a: MainActivity) : Tool {
    private val ui = Handler(Looper.getMainLooper())
    private val devs = HashMap<String, NearDev>()
    private val allSources = listOf("BLE", "BT", "WIFI", "P2P", "MDNS", "LAN")
    private val srcLabel = mapOf("BLE" to "Bluetooth LE", "BT" to "Bluetooth Classic", "WIFI" to "Wi-Fi", "P2P" to "Wi-Fi Direct", "MDNS" to "mDNS", "LAN" to "Network")
    private val srcOn = linkedSetOf("BLE", "BT", "WIFI", "P2P", "MDNS", "LAN")
    private val active = HashSet<String>()

    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var main: LinearLayout
    private lateinit var track: LinearLayout
    private lateinit var tName: TextView
    private lateinit var tHeat: TextView
    private lateinit var tTrend: TextView
    private lateinit var tBar: ProgressBar
    private lateinit var tInfo: LinearLayout
    private lateinit var typeChip: TextView
    private lateinit var knownChip: TextView
    private lateinit var sortChip: TextView

    private var query = ""
    private var typeFilter: String? = null
    private var knownMode = 0   // 0 all, 1 known / paired, 2 new
    private var sortMode = 0    // 0 signal, 1 name, 2 newest
    private var target: String? = null
    private var smooth = -127.0
    private var sound = true
    private var vibrate = true
    private var tone: ToneGenerator? = null
    private var sinceBeep = 0L
    private var sinceList = 99999L
    private var sinceTrend = 0L
    private var trendRef = -127.0
    private var running = false
    private var warn = ""

    private val bm get() = a.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val wm get() = a.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val seenPrefs get() = a.getSharedPreferences("devfinder", Context.MODE_PRIVATE)
    private var seenOld = emptySet<String>()
    private var bleCb: ScanCallback? = null
    private var btRx: BroadcastReceiver? = null
    private var wifiRx: BroadcastReceiver? = null
    private var p2p: WifiP2pManager? = null
    private var p2pCh: WifiP2pManager.Channel? = null
    private var p2pRx: BroadcastReceiver? = null
    private var nsd: NsdManager? = null
    private val nsdListeners = ArrayList<NsdManager.DiscoveryListener>()
    private val nsdQueue = java.util.ArrayDeque<NsdServiceInfo>()
    private var nsdBusy = false
    private var mcLock: WifiManager.MulticastLock? = null
    private var pool: java.util.concurrent.ExecutorService? = null
    private var lanThread: Thread? = null
    private var pingThread: Thread? = null
    private var vendorBusy = false
    private var lastWifiScan = 0L

    // ---------------------------------------------------------------- UI

    override fun view(): View {
        val p = a.page()
        p.add(a.tv("Device Finder", 20f, C.TXT, true), 0)
        p.add(a.tv("Scans everything around you at once: Bluetooth LE and Classic, Wi-Fi networks, Wi-Fi Direct, mDNS services and devices on your local network. Paired or not, connected or not. Tap a device to track it with hot / cold.", 12f, C.SUB), 4)
        status = a.tv("Starting…", 12f, C.ACC, true)
        p.add(status, 8)

        val chips = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        allSources.forEach { s -> chips.addView(chip(srcLabel[s] ?: s, true) { v ->
            if (srcOn.contains(s)) { srcOn.remove(s); stopSource(s) } else { srcOn.add(s); startSource(s) }
            styleChip(v, srcOn.contains(s)); sinceList = 99999L
        }.also { styleChip(it, true) }) }
        p.add(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, 8)

        val f = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        typeChip = chip("Type: All", false) { pickType() }
        knownChip = chip("Show: All", false) { knownMode = (knownMode + 1) % 3; knownChip.text = listOf("Show: All", "Show: Known / paired", "Show: New only")[knownMode]; sinceList = 99999L }
        sortChip = chip("Sort: Signal", false) { sortMode = (sortMode + 1) % 3; sortChip.text = listOf("Sort: Signal", "Sort: Name", "Sort: Newest")[sortMode]; sinceList = 99999L }
        f.addView(typeChip); f.addView(knownChip); f.addView(sortChip)
        p.add(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(f) }, 6)

        val search = a.edit("Search name, address, vendor, type")
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { query = s?.toString()?.trim()?.lowercase() ?: ""; sinceList = 99999L }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        main = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        main.add(search, 0)
        list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        main.add(list, 8)
        p.add(main, 6)

        track = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val tc = a.card()
        tName = a.tv("", 16f, C.TXT, true); tHeat = a.tv("", 28f, C.ACC, true); tTrend = a.tv("", 12f, C.SUB)
        tBar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        tc.addView(tName); tc.addView(tHeat); tc.addView(tTrend)
        tc.addView(tBar, LinearLayout.LayoutParams(-1, a.dp(18)).apply { topMargin = a.dp(8) })
        track.addView(tc)
        track.add(a.sw("Beep (faster when closer)", true) { sound = it }, 8)
        track.add(a.sw("Vibrate", true) { vibrate = it }, 4)
        tInfo = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        track.add(tInfo, 10)
        track.add(a.button("Stop tracking / back to list") { stopTracking() }, 10)
        p.add(track, 6)

        seenOld = seenPrefs.getStringSet("seen", emptySet()) ?: emptySet()
        running = true
        tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull()
        p.applyTypeface(a.tf)

        val need = GuardPerm.radar().filter { a.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) startAll() else a.ask(need.toTypedArray()) { startAll() }
        ui.post(loop)
        return p
    }

    private fun chip(text: String, toggle: Boolean, onClick: (TextView) -> Unit): TextView = TextView(a).apply {
        this.text = text; textSize = 12f; setTextColor(C.TXT)
        background = round(C.CARD, a.dp(16)); setPadding(a.dp(12), a.dp(7), a.dp(12), a.dp(7))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = a.dp(6) }
        setOnClickListener { onClick(this) }
    }

    private fun styleChip(v: TextView, on: Boolean) {
        v.background = round(if (on) C.ACC else C.CARD, a.dp(16)); v.setTextColor(if (on) C.ONACC else C.SUB)
    }

    private fun pickType() {
        val types = synchronized(devs) { devs.values.map { it.type } }.distinct().sorted()
        val labels = (listOf("All types") + types).toTypedArray()
        AlertDialog.Builder(a).setTitle("Device type").setItems(labels) { _, i ->
            typeFilter = if (i == 0) null else types[i - 1]
            typeChip.text = "Type: " + (typeFilter ?: "All"); sinceList = 99999L
        }.show()
    }

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            try { tick(200) } catch (e: Exception) { }
            ui.postDelayed(this, 200)
        }
    }

    private fun tick(dt: Long) {
        pollWifi()
        if (target == null) {
            sinceList += dt
            if (sinceList < 1500) return
            sinceList = 0
            prune(); lookupVendors(); renderList()
        } else trackTick(dt)
    }

    private fun prune() {
        val now = SystemClock.elapsedRealtime()
        synchronized(devs) { devs.values.removeAll { !it.bonded && !it.connected && now - it.last > 120_000 && it.id != target } }
    }

    private fun visible(): List<NearDev> {
        val all = synchronized(devs) { devs.values.toList() }
        val f = all.filter { d ->
            d.src.any { srcOn.contains(it) } &&
                (typeFilter == null || d.type == typeFilter) &&
                (knownMode == 0 || (knownMode == 1) == d.known) &&
                (query.isEmpty() || (d.name + " " + d.addr + " " + d.vendor + " " + d.type + " " + d.ip).lowercase().contains(query))
        }
        return when (sortMode) {
            1 -> f.sortedBy { it.title.lowercase() }
            2 -> f.sortedByDescending { it.first }
            else -> f.sortedByDescending { it.rssi }
        }
    }

    private fun renderList() {
        val items = visible()
        val all = synchronized(devs) { devs.values.toList() }
        val counts = allSources.filter { srcOn.contains(it) }.joinToString(" · ") { s -> "${srcLabel[s]} ${all.count { it.src.contains(s) }}" }
        status.text = "${items.size} shown of ${all.size} found\n$counts" + if (warn.isNotEmpty()) "\n$warn" else ""
        list.removeAllViews()
        if (items.isEmpty()) list.add(a.tv("Scanning… keep Bluetooth, Wi-Fi and Location on and allow Nearby devices permission.", 12f, C.SUB), 0)
        val now = SystemClock.elapsedRealtime()
        items.take(120).forEach { d ->
            val row = a.card()
            row.addView(a.tv(d.title, 14f, C.TXT, true))
            val sig = if (d.hasSignal) "${d.rssi.toInt()} dBm · ~${fmtDist(distOf(d))}" else if (d.rtt >= 0) "ping ${d.rtt} ms" else "no signal reading"
            row.addView(a.tv("${d.type} · $sig", 12f, C.ACC))
            val tags = d.src.joinToString("/") + (if (d.bonded) " · paired" else "") + (if (d.connected) " · connected" else "") +
                (if (!d.known) " · new" else "") + " · ${(now - d.last) / 1000}s ago"
            row.addView(a.tv(listOf(d.addr, d.ip, d.vendor).filter { it.isNotEmpty() }.distinct().joinToString(" · ") + "\n" + tags, 11f, C.SUB))
            row.setOnClickListener { pick(d) }
            list.add(row, 6)
        }
        list.applyTypeface(a.tf)
    }

    private fun fmtDist(m: Double) = if (m < 1) "<1 m" else if (m < 10) "%.1f m".format(m) else "${m.toInt()} m"

    /** Rough distance from signal strength. Walls, bodies and antennas change it a lot, so it is only a guide. */
    private fun distOf(d: NearDev): Double {
        if (d.freq > 0) {
            val fspl = 27.55
            return 10.0.pow((fspl - 20 * Math.log10(d.freq.toDouble()) + Math.abs(d.rssi)) / 20.0)
        }
        val tx = if (d.txp != Int.MIN_VALUE) d.txp.toDouble() else -59.0
        return 10.0.pow((tx - d.rssi) / 25.0)
    }

    // ---------------------------------------------------------------- tracking

    private fun pick(d: NearDev) {
        target = d.id; smooth = if (d.hasSignal) d.rssi else -127.0; trendRef = smooth; sinceTrend = 0
        main.visibility = View.GONE; track.visibility = View.VISIBLE
        tName.text = d.title; tHeat.text = "Searching…"; tHeat.setTextColor(C.ACC); tTrend.text = ""
        if (!d.hasSignal) startPing(d)
        showInfo(d)
    }

    private fun stopTracking() {
        target = null; pingThread?.interrupt(); pingThread = null
        track.visibility = View.GONE; main.visibility = View.VISIBLE; sinceList = 99999L
    }

    private fun showInfo(d: NearDev) {
        tInfo.removeAllViews()
        val rows = ArrayList<Row>()
        rows.add(Row("Details", header = true))
        rows.add(Row("Type", d.type))
        if (d.name.isNotEmpty()) rows.add(Row("Name", d.name))
        if (d.addr.isNotEmpty()) rows.add(Row("Address", d.addr))
        if (d.ip.isNotEmpty()) rows.add(Row("IP", d.ip))
        if (d.vendor.isNotEmpty()) rows.add(Row("Maker", d.vendor))
        rows.add(Row("Found by", d.src.joinToString(", ") { srcLabel[it] ?: it }))
        rows.add(Row("Status", listOfNotNull(if (d.bonded) "paired" else null, if (d.connected) "connected" else null, if (d.seenBefore) "seen before" else null).ifEmpty { listOf("new") }.joinToString(", ")))
        d.info.forEach { (k, v) -> rows.add(Row(k, v)) }
        tInfo.addView(a.table(rows)); tInfo.applyTypeface(a.tf)
    }

    private fun trackTick(dt: Long) {
        val d = synchronized(devs) { devs[target ?: ""] } ?: run { tHeat.text = "Device gone"; return }
        if (!d.hasSignal) {
            tHeat.text = if (d.rtt >= 0) "${d.rtt} ms" else "…"; tTrend.text = "This device has no radio signal reading, so the live ping time is shown instead."
            tBar.progress = if (d.rtt < 0) 0 else (100 - d.rtt.coerceIn(0, 100)); return
        }
        val stale = SystemClock.elapsedRealtime() - d.last > 6000
        smooth = if (smooth <= -126.0) d.rssi else smooth * 0.6 + d.rssi * 0.4
        val pc = ((smooth + 100) * 100 / 60).toInt().coerceIn(0, 100)
        val (txt, col) = when {
            stale -> "SIGNAL LOST" to C.SUB
            pc >= 80 -> "VERY HOT" to 0xFFFF3B30.toInt()
            pc >= 60 -> "HOT" to 0xFFFF9500.toInt()
            pc >= 40 -> "WARM" to 0xFFFFCC00.toInt()
            pc >= 20 -> "COLD" to 0xFF4F8CFF.toInt()
            else -> "FREEZING" to 0xFF5AC8FA.toInt()
        }
        tHeat.text = "$txt  $pc%"; tHeat.setTextColor(col)
        tBar.progress = pc; tBar.progressTintList = ColorStateList.valueOf(col)
        tName.text = "${d.title} · ${smooth.toInt()} dBm · ~${fmtDist(distOf(d))}"
        sinceTrend += dt
        if (sinceTrend >= 1500) {
            sinceTrend = 0
            val diff = smooth - trendRef; trendRef = smooth
            tTrend.text = if (stale) "Not seen for a few seconds" else if (diff > 1.5) "Getting warmer" else if (diff < -1.5) "Getting colder" else "Holding steady"
        }
        sinceBeep += dt
        if (!stale && sinceBeep >= 1300 - pc * 11) {
            sinceBeep = 0
            if (sound) runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 60) }
            if (vibrate) runCatching {
                @Suppress("DEPRECATION") val v = a.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                v?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }

    private fun startPing(d: NearDev) {
        pingThread?.interrupt()
        val ip = d.ip
        pingThread = Thread {
            while (!Thread.currentThread().isInterrupted && running) {
                val r = probe(ip)
                if (r >= 0) synchronized(devs) { devs[d.id]?.rtt = r }
                try { Thread.sleep(800) } catch (e: InterruptedException) { break }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    // ---------------------------------------------------------------- shared helpers

    private inline fun upsert(id: String, source: String, block: (NearDev) -> Unit) {
        synchronized(devs) {
            val d = devs.getOrPut(id) { NearDev(id).also { it.first = SystemClock.elapsedRealtime(); it.seenBefore = seenOld.contains(id) } }
            d.src.add(source); d.last = SystemClock.elapsedRealtime()
            block(d)
        }
    }

    private fun setRssi(d: NearDev, r: Int) { d.rssi = if (d.rssi < -126.0) r.toDouble() else d.rssi * 0.6 + r * 0.4 }

    private fun lookupVendors() {
        if (vendorBusy) return
        val d = synchronized(devs) {
            devs.values.firstOrNull { !it.vendorTried && it.vendor.isEmpty() && it.addr.length == 17 && it.addr[2] == ':' && !it.src.contains("BLE") && !MacVendor.isRandom(it.addr) }
        } ?: return
        d.vendorTried = true; vendorBusy = true
        Thread {
            val v = runCatching { MacVendor.lookup(d.addr) }.getOrNull()
            if (v != null) synchronized(devs) { d.vendor = v }
            try { Thread.sleep(1100) } catch (e: Exception) { }
            vendorBusy = false
        }.also { it.isDaemon = true; it.start() }
    }

    // ---------------------------------------------------------------- start / stop

    private fun startAll() {
        val lm = a.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        warn = if (lm != null && Build.VERSION.SDK_INT >= 28 && !lm.isLocationEnabled) "Location is off: Android hides Bluetooth and Wi-Fi results until you turn it on." else ""
        runCatching {
            mcLock = wm.createMulticastLock("devfinder").apply { setReferenceCounted(false); acquire() }
        }
        allSources.filter { srcOn.contains(it) }.forEach { startSource(it) }
    }

    private fun startSource(s: String) {
        if (!running || active.contains(s)) return
        active.add(s)
        try {
            when (s) {
                "BLE" -> startBle()
                "BT" -> startClassic()
                "WIFI" -> startWifi()
                "P2P" -> startP2p()
                "MDNS" -> startNsd()
                "LAN" -> startLan()
            }
        } catch (e: Exception) { active.remove(s); warn = "$s could not start: ${e.javaClass.simpleName}" }
    }

    private fun stopSource(s: String) {
        active.remove(s)
        try {
            when (s) {
                "BLE" -> { bleCb?.let { bm?.adapter?.bluetoothLeScanner?.stopScan(it) }; bleCb = null }
                "BT" -> { btRx?.let { a.unregisterReceiver(it) }; btRx = null; bm?.adapter?.cancelDiscovery() }
                "WIFI" -> { wifiRx?.let { a.unregisterReceiver(it) }; wifiRx = null }
                "P2P" -> { p2p?.stopPeerDiscovery(p2pCh, null); p2pRx?.let { a.unregisterReceiver(it) }; p2pRx = null }
                "MDNS" -> { nsdListeners.forEach { runCatching { nsd?.stopServiceDiscovery(it) } }; nsdListeners.clear(); nsdQueue.clear() }
                "LAN" -> { lanThread?.interrupt(); lanThread = null; pool?.shutdownNow(); pool = null }
            }
        } catch (e: Exception) { }
    }

    // ---------------------------------------------------------------- Bluetooth LE

    private fun startBle() {
        val ad = bm?.adapter
        if (ad == null || !ad.isEnabled) { warn = "Bluetooth is off, turn it on to find Bluetooth devices."; active.remove("BLE"); return }
        val sc = ad.bluetoothLeScanner ?: return
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: BleResult) { onBle(r) }
            override fun onBatchScanResults(results: MutableList<BleResult>) { results.forEach { onBle(it) } }
            override fun onScanFailed(errorCode: Int) { warn = "Bluetooth LE scan failed (code $errorCode)" }
        }
        bleCb = cb
        sc.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
    }

    private fun onBle(r: BleResult) {
        val dev = r.device ?: return
        val rec = r.scanRecord
        val (t, maker, extra) = bleClassify(rec)
        val bonded = dev.bondState == BluetoothDevice.BOND_BONDED
        val nm = runCatching { rec?.deviceName ?: dev.name }.getOrNull() ?: ""
        upsert("bt:" + dev.address, "BLE") { d ->
            if (nm.isNotEmpty()) d.name = nm
            d.addr = dev.address; d.bonded = bonded || d.bonded
            if (d.type == "Unknown" || t != "Unknown") d.type = if (t != "Unknown") t else d.type
            if (maker.isNotEmpty()) d.vendor = maker
            setRssi(d, r.rssi)
            if (rec != null && rec.txPowerLevel != Int.MIN_VALUE && rec.txPowerLevel != 127) d.txp = rec.txPowerLevel - 41
            d.info["Connectable"] = if (Build.VERSION.SDK_INT >= 26) (if (r.isConnectable) "yes" else "no") else "?"
            if (extra.isNotEmpty()) d.info["Advertises"] = extra
            if (rec != null && rec.manufacturerSpecificData.size() > 0) d.info["Manufacturer ID"] = "0x%04X".format(rec.manufacturerSpecificData.keyAt(0))
        }
    }

    private val companies = mapOf(
        0x004C to "Apple", 0x0006 to "Microsoft", 0x0075 to "Samsung", 0x00E0 to "Google", 0x0087 to "Garmin",
        0x012D to "Sony", 0x009E to "Bose", 0x0171 to "Amazon", 0x038F to "Xiaomi", 0x0157 to "Huami (Amazfit / Xiaomi band)",
        0x0059 to "Nordic Semiconductor", 0x000F to "Broadcom", 0x0002 to "Intel", 0x000A to "Qualcomm (CSR)", 0x027D to "Huawei",
        0x0057 to "Harman (JBL / AKG)", 0x0499 to "Ruuvi", 0x0001 to "Nokia", 0x001D to "Qualcomm", 0x0003 to "IBM", 0x0008 to "Motorola"
    )

    private val services16 = mapOf(
        "fd5a" to ("Tracker" to "Samsung SmartTag"), "feed" to ("Tracker" to "Tile"), "fd6f" to ("Phone" to "Exposure notification"),
        "fe2c" to ("Earbuds / headphones" to "Google Fast Pair"), "feaa" to ("Beacon" to "Eddystone beacon"), "fe9f" to ("Phone" to "Google"),
        "180d" to ("Wearable / fitness" to "Heart rate"), "1812" to ("Keyboard / mouse" to "HID input"), "1802" to ("Tracker" to "Immediate alert"),
        "1826" to ("Wearable / fitness" to "Fitness machine"), "1809" to ("Health device" to "Thermometer"), "181d" to ("Health device" to "Weight scale"),
        "1816" to ("Wearable / fitness" to "Cycling sensor"), "1814" to ("Wearable / fitness" to "Running sensor"), "fe95" to ("Smart device" to "Xiaomi"),
        "181b" to ("Health device" to "Body composition"), "1808" to ("Health device" to "Glucose"), "1810" to ("Health device" to "Blood pressure"),
        "fd44" to ("Phone" to "Apple"), "fe07" to ("Speaker" to "Sonos"), "fdf7" to ("Smart device" to "HP"), "fe59" to ("Smart device" to "Nordic DFU")
    )

    /** Returns (type, maker, what it advertises). */
    private fun bleClassify(rec: ScanRecord?): Triple<String, String, String> {
        if (rec == null) return Triple("Unknown", "", "")
        var type = "Unknown"; var maker = ""; val adv = ArrayList<String>()
        val m = rec.manufacturerSpecificData
        for (i in 0 until m.size()) {
            val id = m.keyAt(i); val data = m.valueAt(i)
            companies[id]?.let { if (maker.isEmpty()) maker = it }
            if (id == 0x004C && data != null && data.isNotEmpty()) {
                when (data[0].toInt() and 0xFF) {
                    0x02 -> { type = "Beacon"; adv.add("iBeacon") }
                    0x07 -> { type = "Earbuds / headphones"; adv.add("Apple proximity pairing (AirPods / Beats)") }
                    0x09 -> { type = "TV / media"; adv.add("AirPlay target") }
                    0x0A -> adv.add("AirPlay source")
                    0x0C -> { if (type == "Unknown") type = "Phone / computer"; adv.add("Handoff") }
                    0x0F, 0x10 -> { if (type == "Unknown") type = "Phone / computer"; adv.add("Apple Nearby") }
                    0x12 -> { type = "Tracker"; adv.add("Find My network (AirTag or accessory)") }
                }
            } else if (id == 0x0075 && type == "Unknown") { type = "Samsung device" }
            else if (id == 0x0006 && type == "Unknown") { type = "Computer / Windows" }
            else if (id == 0x0157 && type == "Unknown") { type = "Wearable / fitness" }
        }
        for (u in rec.serviceUuids ?: emptyList()) {
            val s = u.uuid.toString().lowercase()
            if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb")) {
                services16[s.substring(4, 8)]?.let { (t, label) -> if (type == "Unknown" || type == "Samsung device") type = t; adv.add(label) }
            }
        }
        if (type == "Unknown") {
            val nm = (rec.deviceName ?: "").lowercase()
            type = when {
                Regex("airpod|buds|earbud|headphone|headset|wh-|wf-|jbl|beats|pods").containsMatchIn(nm) -> "Earbuds / headphones"
                Regex("watch|band|fit|mi band|amazfit").containsMatchIn(nm) -> "Wearable / fitness"
                Regex("tag|tile|tracker|airtag").containsMatchIn(nm) -> "Tracker"
                Regex("tv|bravia|cast|roku").containsMatchIn(nm) -> "TV / media"
                Regex("speaker|soundbar|echo|boom|flip|charge").containsMatchIn(nm) -> "Speaker"
                Regex("keyboard|mouse|trackpad|mx ").containsMatchIn(nm) -> "Keyboard / mouse"
                else -> "Unknown"
            }
        }
        return Triple(type, maker, adv.distinct().joinToString(", "))
    }

    // ---------------------------------------------------------------- Bluetooth Classic

    private fun classicType(c: BluetoothClass?): String {
        if (c == null) return "Unknown"
        val dc = c.deviceClass
        return when (c.majorDeviceClass) {
            0x0100 -> "Computer"
            0x0200 -> "Phone"
            0x0400 -> when (dc) {
                0x0418, 0x0404, 0x0408 -> "Earbuds / headphones"
                0x0414, 0x041C, 0x0428 -> "Speaker"
                0x0420 -> "Car audio"
                0x0424, 0x0438, 0x043C -> "TV / media"
                0x0430, 0x0434 -> "Camera"
                else -> "Audio / video"
            }
            0x0500 -> "Keyboard / mouse"
            0x0600 -> "Printer / scanner / camera"
            0x0700 -> "Wearable / fitness"
            0x0800 -> "Toy"
            0x0900 -> "Health device"
            else -> "Unknown"
        }
    }

    private fun onClassic(dev: BluetoothDevice, rssi: Int?, bondedOnly: Boolean) {
        upsert("bt:" + dev.address, "BT") { d ->
            d.addr = dev.address
            val n = runCatching { dev.name }.getOrNull()
            if (!n.isNullOrEmpty()) d.name = n
            val t = classicType(runCatching { dev.bluetoothClass }.getOrNull())
            if (t != "Unknown" || d.type == "Unknown") d.type = if (t != "Unknown") t else d.type
            d.bonded = dev.bondState == BluetoothDevice.BOND_BONDED
            if (rssi != null) setRssi(d, rssi)
            if (bondedOnly) d.info["Note"] = "Paired device. Signal appears only while it is in range and discoverable."
        }
    }

    private fun startClassic() {
        val ad = bm?.adapter
        if (ad == null || !ad.isEnabled) { active.remove("BT"); return }
        ad.bondedDevices?.forEach { onClassic(it, null, true) }
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        @Suppress("DEPRECATION") val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                        val r = i.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                        onClassic(d, if (r == Short.MIN_VALUE) null else r.toInt(), false)
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> if (running && active.contains("BT")) ui.postDelayed({ if (running && active.contains("BT")) runCatching { bm?.adapter?.startDiscovery() } }, 1500)
                }
            }
        }
        btRx = rx
        a.registerReceiver(rx, IntentFilter().apply { addAction(BluetoothDevice.ACTION_FOUND); addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED) })
        ad.startDiscovery()
    }

    // ---------------------------------------------------------------- Wi-Fi

    private fun startWifi() {
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                readWifi()
            }
        }
        wifiRx = rx
        val f = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        a.registerReceiver(rx, f)
        readWifi()
        kickWifiScan()
    }

    @Suppress("DEPRECATION")
    private fun startScanCompat() { wm.startScan() }

    private fun kickWifiScan() {
        lastWifiScan = SystemClock.elapsedRealtime()
        runCatching { startScanCompat() }
    }

    private fun pollWifi() {
        if (active.contains("WIFI") && SystemClock.elapsedRealtime() - lastWifiScan > 20000L) kickWifiScan()
    }

    private fun wifiStd(r: WifiResult): String {
        if (Build.VERSION.SDK_INT < 30) return ""
        return when (r.wifiStandard) { 4 -> "Wi-Fi 4 (n)"; 5 -> "Wi-Fi 5 (ac)"; 6 -> "Wi-Fi 6 (ax)"; 7 -> "Wi-Fi 7 (be)"; 1 -> "legacy (a/b/g)"; else -> "" }
    }

    @Suppress("DEPRECATION")
    private fun readBssid(): String? = runCatching { wm.connectionInfo?.bssid }.getOrNull()?.lowercase()

    private fun readWifi() {
        val res = runCatching { wm.scanResults }.getOrNull()
        if (res == null) return
        val cur = readBssid()
        val nowUs = SystemClock.elapsedRealtime()
        for (r in res) {
            val bssid = (r.BSSID ?: continue).lowercase()
            val ageMs = (nowUs * 1000 - r.timestamp) / 1000
            if (ageMs > 60_000) continue
            @Suppress("DEPRECATION") val ssid = r.SSID ?: ""
            upsert("mac:$bssid", "WIFI") { d ->
                d.addr = bssid.uppercase(); d.name = ssid.ifEmpty { "(hidden network)" }
                d.type = "Wi-Fi network"; d.freq = r.frequency; setRssi(d, r.level)
                d.connected = bssid == cur
                MacVendor.offlineLookup(d.addr)?.let { d.vendor = it }
                d.info["Channel"] = chanOf(r.frequency).toString() + " (${r.frequency} MHz)"
                d.info["Security"] = secOf(r.capabilities)
                wifiStd(r).takeIf { it.isNotEmpty() }?.let { d.info["Generation"] = it }
                d.info["Random MAC"] = if (MacVendor.isRandom(d.addr)) "yes (hotspot / phone)" else "no"
                if (MacVendor.isRandom(d.addr)) d.type = "Wi-Fi hotspot / phone"
            }
        }
    }

    private fun chanOf(f: Int) = when {
        f == 2484 -> 14
        f in 2412..2472 -> (f - 2407) / 5
        f in 5000..5900 -> (f - 5000) / 5
        f in 5955..7115 -> (f - 5950) / 5
        else -> 0
    }

    private fun secOf(c: String): String = when {
        c.contains("SAE") -> "WPA3"
        c.contains("WPA2") -> "WPA2"
        c.contains("WPA") -> "WPA"
        c.contains("WEP") -> "WEP"
        else -> "Open"
    }

    // ---------------------------------------------------------------- Wi-Fi Direct

    private fun p2pType(t: String?): String {
        val cat = t?.substringBefore('-')?.toIntOrNull()
        return when (cat) {
            1 -> "Computer"; 2 -> "Keyboard / mouse"; 3 -> "Printer / scanner"; 4 -> "Camera"; 5 -> "Storage"; 6 -> "Network device"
            7 -> "TV / display"; 8 -> "TV / media"; 9 -> "Game console"; 10 -> "Phone"; 11 -> "Speaker"; else -> "Wi-Fi Direct device"
        }
    }

    private fun startP2p() {
        val m = a.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager ?: run { active.remove("P2P"); return }
        p2p = m
        val ch = m.initialize(a, Looper.getMainLooper(), null); p2pCh = ch
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action == WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION) m.requestPeers(ch) { list -> list.deviceList.forEach { onPeer(it) } }
            }
        }
        p2pRx = rx
        a.registerReceiver(rx, IntentFilter(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION))
        m.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) { warn = "Wi-Fi Direct discovery refused (code $reason). Wi-Fi must be on." }
        })
        ui.postDelayed(object : Runnable {
            override fun run() {
                if (!running || !active.contains("P2P")) return
                runCatching { m.discoverPeers(ch, null) }
                ui.postDelayed(this, 45_000)
            }
        }, 45_000)
    }

    private fun onPeer(p: WifiP2pDevice) {
        upsert("mac:" + p.deviceAddress.lowercase(), "P2P") { d ->
            d.addr = p.deviceAddress.uppercase(); d.name = p.deviceName ?: ""
            d.type = p2pType(p.primaryDeviceType)
            MacVendor.offlineLookup(d.addr)?.let { d.vendor = it }
            d.info["Direct status"] = when (p.status) { 0 -> "connected"; 1 -> "invited"; 2 -> "failed"; 3 -> "available"; else -> "unavailable" }
            p.primaryDeviceType?.let { d.info["Device type code"] = it }
            d.info["Group owner"] = if (p.isGroupOwner) "yes" else "no"
        }
    }

    // ---------------------------------------------------------------- mDNS / NSD

    private val nsdTypes = listOf(
        "_googlecast._tcp", "_airplay._tcp", "_raop._tcp", "_ipp._tcp", "_printer._tcp", "_pdl-datastream._tcp", "_hap._tcp",
        "_spotify-connect._tcp", "_smb._tcp", "_ssh._tcp", "_workstation._tcp", "_androidtvremote2._tcp", "_companion-link._tcp",
        "_http._tcp", "_afpovertcp._tcp", "_adb._tcp", "_sonos._tcp", "_hue._tcp", "_matter._tcp", "_esphomelib._tcp"
    )

    private fun nsdLabel(t: String) = when {
        t.contains("googlecast") -> "TV / media (Chromecast)"
        t.contains("airplay") -> "TV / media (AirPlay)"
        t.contains("raop") || t.contains("spotify") || t.contains("sonos") -> "Speaker"
        t.contains("ipp") || t.contains("printer") || t.contains("pdl") -> "Printer"
        t.contains("hap") || t.contains("hue") || t.contains("matter") || t.contains("esphome") -> "Smart home device"
        t.contains("smb") || t.contains("afp") -> "File server / computer"
        t.contains("ssh") || t.contains("workstation") -> "Computer"
        t.contains("androidtv") -> "TV / media (Android TV)"
        t.contains("companion") -> "Apple device"
        t.contains("adb") -> "Phone / Android (debug)"
        else -> "Network service"
    }

    private fun startNsd() {
        val m = a.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: run { active.remove("MDNS"); return }
        nsd = m
        for (t in nsdTypes) {
            val l = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(s: String) {}
                override fun onStartDiscoveryFailed(s: String, e: Int) {}
                override fun onStopDiscoveryFailed(s: String, e: Int) {}
                override fun onDiscoveryStopped(s: String) {}
                override fun onServiceLost(i: NsdServiceInfo) {}
                override fun onServiceFound(i: NsdServiceInfo) { synchronized(nsdQueue) { nsdQueue.add(i) }; nextResolve() }
            }
            nsdListeners.add(l)
            runCatching { m.discoverServices(t, NsdManager.PROTOCOL_DNS_SD, l) }
        }
    }

    private fun nextResolve() {
        val m = nsd ?: return
        val info = synchronized(nsdQueue) { if (nsdBusy) return; nsdQueue.poll()?.also { nsdBusy = true } } ?: return
        @Suppress("DEPRECATION")
        m.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(i: NsdServiceInfo, e: Int) { synchronized(nsdQueue) { nsdBusy = false }; if (running) ui.post { nextResolve() } }
            override fun onServiceResolved(i: NsdServiceInfo) {
                @Suppress("DEPRECATION") val host = i.host?.hostAddress
                if (host != null && !host.contains(':')) {
                    upsert("ip:$host", "MDNS") { d ->
                        d.ip = host; d.type = nsdLabel(i.serviceType ?: "")
                        if (d.name.isEmpty() || d.name == d.ip) d.name = i.serviceName ?: host
                        d.info["Service ${(i.serviceType ?: "").trim('.')}"] = "${i.serviceName} :${i.port}"
                        i.attributes?.forEach { (k, v) -> if (k in listOf("md", "fn", "model", "am", "manufacturer", "ty", "product")) d.info[k] = String(v ?: ByteArray(0)) }
                        i.attributes?.get("md")?.let { d.vendor = String(it) }
                    }
                }
                synchronized(nsdQueue) { nsdBusy = false }; if (running) ui.post { nextResolve() }
            }
        })
    }

    // ---------------------------------------------------------------- local network sweep

    private fun probe(ip: String): Int {
        val t0 = SystemClock.elapsedRealtime()
        val addr = runCatching { InetAddress.getByName(ip) }.getOrNull() ?: return -1
        if (runCatching { addr.isReachable(250) }.getOrDefault(false)) return (SystemClock.elapsedRealtime() - t0).toInt()
        for (port in listOf(80, 443, 445, 22, 8008, 62078)) {
            try {
                Socket().use { it.connect(InetSocketAddress(addr, port), 140) }
                return (SystemClock.elapsedRealtime() - t0).toInt()
            } catch (e: java.net.ConnectException) {
                return (SystemClock.elapsedRealtime() - t0).toInt()   // refused = host is alive
            } catch (e: Exception) {
            }
        }
        return -1
    }

    private fun readArp(): Map<String, String> {
        val out = HashMap<String, String>()
        runCatching {
            File("/proc/net/arp").readLines().drop(1).forEach { l ->
                val p = l.trim().split(Regex("\\s+"))
                if (p.size >= 4 && p[3] != "00:00:00:00:00:00") out[p[0]] = p[3].uppercase()
            }
        }
        return out
    }

    private fun startLan() {
        val cm = a.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val lp = cm.getLinkProperties(cm.activeNetwork)
        val la = lp?.linkAddresses?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
        if (la == null) { warn = "Not on a Wi-Fi network, so the local network sweep is idle. Join a network to list its devices."; active.remove("LAN"); return }
        val me = la.address.hostAddress ?: return
        val gw = lp?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
        val base = me.substringBeforeLast('.')
        upsert("ip:$me", "LAN") { d -> d.ip = me; d.name = "This phone"; d.type = "Phone"; d.connected = true }
        val ex = Executors.newFixedThreadPool(40).also { pool = it }
        lanThread = Thread {
            while (!Thread.currentThread().isInterrupted && running && active.contains("LAN")) {
                val jobs = ArrayList<java.util.concurrent.Future<*>>()
                for (n in 1..254) {
                    val ip = "$base.$n"
                    if (ip == me) continue
                    jobs.add(ex.submit(Runnable {
                        val rtt = probe(ip)
                        if (rtt >= 0) {
                            val host = runCatching { InetAddress.getByName(ip).canonicalHostName }.getOrNull()?.takeIf { it != ip } ?: ""
                            upsert("ip:$ip", "LAN") { d ->
                                d.ip = ip; d.rtt = rtt
                                if (host.isNotEmpty() && d.name.isEmpty()) d.name = host
                                if (ip == gw) { d.type = "Router / gateway"; d.name = d.name.ifEmpty { "Router" } }
                                else if (d.type == "Unknown") d.type = "Network device"
                                d.info["Ping"] = "$rtt ms"
                            }
                        }
                    }))
                }
                jobs.forEach { runCatching { it.get(20, TimeUnit.SECONDS) } }
                val arp = readArp()
                arp.forEach { (ip, mac) ->
                    synchronized(devs) { devs["ip:$ip"] }?.let { d -> synchronized(devs) { d.addr = mac; MacVendor.offlineLookup(mac)?.let { d.vendor = it }; if (MacVendor.isRandom(mac) && d.type == "Network device") d.type = "Phone / laptop (private MAC)" } }
                }
                try { Thread.sleep(40_000) } catch (e: InterruptedException) { break }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun stop() {
        running = false
        ui.removeCallbacksAndMessages(null)
        allSources.toList().forEach { stopSource(it) }
        pingThread?.interrupt(); pingThread = null
        runCatching { mcLock?.release() }; mcLock = null
        runCatching { tone?.release() }; tone = null
        runCatching { p2p?.let { m -> p2pCh?.let { c -> if (Build.VERSION.SDK_INT >= 27) c.close() } } }
        val seen = synchronized(devs) { devs.keys.filter { it.startsWith("bt:") || it.startsWith("mac:") || it.startsWith("ip:") }.take(600).toSet() }
        runCatching { seenPrefs.edit().putStringSet("seen", seenOld + seen).apply() }
    }
}
