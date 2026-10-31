package com.toolkit.privacy

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.LocationManager
import android.net.wifi.ScanResult as WifiScan
import android.net.wifi.WifiManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

private fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
private fun fmtMhz(m: Double) = if (m >= 1000) "%.2f GHz".format(m / 1000) else "%.2f MHz".format(m)

// ------------------------------------------------------------------------------------------------ spectrum map

@SuppressLint("MissingPermission")
@Composable
fun RadioSpectrumScreen() {
    val ctx = LocalContext.current
    val pm = ctx.packageManager
    val wm = remember { ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager }
    var aps by remember { mutableStateOf<List<WifiScan>>(emptyList()) }
    var loc by remember { mutableStateOf(granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)) }
    val permL = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { loc = it }
    var sats by remember { mutableStateOf<List<Sat>>(emptyList()) }

    LaunchedEffect(loc) {
        while (loc) {
            try { wm.startScan(); aps = wm.scanResults ?: emptyList() } catch (e: Exception) { }
            delay(7000)
        }
    }
    DisposableEffect(loc) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val cb = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(s: GnssStatus) {
                sats = (0 until s.satelliteCount).map { Sat(s.getConstellationType(it), s.getSvid(it), s.getAzimuthDegrees(it), s.getElevationDegrees(it), s.getCn0DbHz(it), s.usedInFix(it)) }
            }
        }
        if (loc) try { lm.registerGnssStatusCallback(cb, android.os.Handler(android.os.Looper.getMainLooper())) } catch (e: Exception) { }
        onDispose { try { lm.unregisterGnssStatusCallback(cb) } catch (e: Exception) { } }
    }

    val has = remember {
        buildList {
            add("Wi-Fi" to pm.hasSystemFeature(PackageManager.FEATURE_WIFI))
            add("Wi-Fi 5 GHz" to (if (Build.VERSION.SDK_INT >= 21) wm.is5GHzBandSupported else false))
            add("Wi-Fi 6 GHz" to (if (Build.VERSION.SDK_INT >= 30) wm.is6GHzBandSupported else false))
            add("Wi-Fi Aware" to pm.hasSystemFeature("android.hardware.wifi.aware"))
            add("Wi-Fi RTT (ranging)" to pm.hasSystemFeature("android.hardware.wifi.rtt"))
            add("Bluetooth" to pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH))
            add("Bluetooth LE" to pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE))
            add("NFC" to pm.hasSystemFeature(PackageManager.FEATURE_NFC))
            add("UWB" to pm.hasSystemFeature("android.hardware.uwb"))
            add("Cellular" to pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY))
            add("GPS / GNSS" to pm.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS))
            add("Infrared blaster" to pm.hasSystemFeature(PackageManager.FEATURE_CONSUMER_IR))
            add("USB host (SDR dongles)" to pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST))
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
        item { Text("Radio spectrum", style = MaterialTheme.typography.headlineSmall) }
        item {
            Section("What this phone can hear", "Checked against the real hardware flags, not guessed.") {
                has.forEach { (n, ok) -> Row { Text(if (ok) "✅" else "⬜"); Spacer(Modifier.width(8.dp)); Text(n, color = if (ok) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant) } }
                Hint("The phone can only inspect signals its chips tune to. For everything else (FM, airband, 433 MHz…) use the SDR tab with an external dongle.")
            }
        }
        item {
            Section("Frequency map", "Log scale, 10 MHz to 8 GHz. Coloured bars are standard bands; spikes are Wi-Fi networks heard right now (height = strength).") {
                if (!loc) { Text("Location permission is needed by Android to list nearby Wi-Fi.", style = MaterialTheme.typography.bodySmall); Button(onClick = { permL.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) { Text("Allow") } }
                FreqMap(aps)
                Rf.bands.forEach { b -> Row(Modifier.fillMaxWidth()) {
                    Text(b.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    Text(if (b.lowMhz == b.highMhz) fmtMhz(b.lowMhz) else "${fmtMhz(b.lowMhz)} – ${fmtMhz(b.highMhz)}", style = MaterialTheme.typography.bodySmall)
                } }
            }
        }
        item {
            val bands = aps.groupBy { when { it.frequency > 5900 -> "6 GHz"; it.frequency > 4900 -> "5 GHz"; else -> "2.4 GHz" } }
            Section("Live Wi-Fi signals", "${aps.size} networks heard") {
                listOf("2.4 GHz", "5 GHz", "6 GHz").forEach { b ->
                    val l = bands[b].orEmpty()
                    if (l.isNotEmpty()) Text("$b: ${l.size} network(s), strongest ${l.maxOf { it.level }} dBm", style = MaterialTheme.typography.bodyMedium)
                }
                aps.sortedByDescending { it.level }.take(10).forEach { a ->
                    val name = a.SSID.ifEmpty { "(hidden)" }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, Modifier.weight(1f), maxLines = 1); Text("ch ${Rf.channelOf(a.frequency)} · ${a.level} dBm", style = MaterialTheme.typography.bodySmall)
                    }
                    PercentBar(((a.level + 100) * 100 / 60).coerceIn(2, 100), Color(SiteRules.colorFor(((a.level + 100) * 100 / 55).coerceIn(0, 100))), 4)
                }
            }
        }
        item {
            Section("Satellites (GNSS sky view)", if (!loc) "Allow location above to see satellites" else "${sats.size} visible · ${sats.count { it.used }} used in fix") {
                if (loc) {
                    SkyPlot(sats)
                    sats.groupBy { constName(it.type) }.forEach { (n, l) -> Text("$n: ${l.size} (${l.count { it.used }} used) · best ${"%.0f".format(l.maxOf { it.cn0 })} dB-Hz", style = MaterialTheme.typography.bodySmall) }
                    Hint("Signal ≥ 35 dB-Hz is strong. Used satellites are filled; hollow ones are seen but not in the fix. Go outdoors for more.")
                }
            }
        }
        item {
            var cells by remember { mutableStateOf<List<CellTower>>(emptyList()) }
            val ctxC = LocalContext.current
            LaunchedEffect(loc) { while (loc) { cells = withContext(Dispatchers.IO) { RadioCells.read(ctxC) }; delay(4000) } }
            Section("Cell towers", if (!loc) "Allow location above to read cell towers" else "${cells.size} visible · ${RadioCells.operator(ctxC)}") {
                if (loc && cells.isEmpty()) Hint("No cell data (no SIM, airplane mode or the phone hides it).")
                cells.forEach { c ->
                    Column {
                        Text("${if (c.serving) "● serving" else "○ neighbour"}  ${c.tech}  ${c.dbm} dBm  (${c.quality}%)", color = Color(SiteRules.colorFor(c.quality)), fontWeight = FontWeight.Medium)
                        Text(c.detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (cells.isNotEmpty()) Hint("Above -85 dBm is strong, below -105 dBm drops calls and slows data.")
            }
        }
    }
}

data class Sat(val type: Int, val svid: Int, val az: Float, val el: Float, val cn0: Float, val used: Boolean)
private fun constName(t: Int) = when (t) { 1 -> "GPS"; 2 -> "SBAS"; 3 -> "GLONASS"; 4 -> "QZSS"; 5 -> "BeiDou"; 6 -> "Galileo"; 7 -> "NavIC"; else -> "Other" }

@Composable
private fun FreqMap(aps: List<WifiScan>) {
    val lo = log10(10.0); val hi = log10(8000.0)
    val barColors = mapOf("Short range" to Info, "Broadcast" to Warn, "Cellular" to Color(0xFFA855F7), "Navigation" to Good, "Wireless LAN" to Color(0xFF22D3EE))
    val onSurface = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        fun x(mhz: Double) = ((log10(mhz) - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f) * size.width
        for (g in listOf(10.0, 100.0, 1000.0)) drawLine(onSurface.copy(alpha = 0.3f), Offset(x(g), 0f), Offset(x(g), size.height), 1f)
        val rowH = 10f
        Rf.bands.forEachIndexed { i, b ->
            val x0 = x(b.lowMhz); val x1 = maxOf(x(b.highMhz), x0 + 4f)
            drawRect((barColors[b.kind] ?: Info).copy(alpha = 0.75f), Offset(x0, size.height - 14f - (i % 6) * (rowH + 2f)), Size(x1 - x0, rowH))
        }
        aps.forEach { a ->
            val h = ((a.level + 100).coerceIn(0, 60) / 60f) * (size.height - 40f)
            val xx = x(a.frequency.toDouble())
            drawLine(Color(SiteRules.colorFor(((a.level + 100) * 100 / 55).coerceIn(0, 100))).copy(alpha = 0.8f), Offset(xx, size.height - 14f - (Rf.bands.size % 6) * 12f), Offset(xx, size.height - 14f - (Rf.bands.size % 6) * 12f - h), 3f)
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { listOf("10 MHz", "100 MHz", "1 GHz", "8 GHz").forEach { Text(it, style = MaterialTheme.typography.labelSmall) } }
}

@Composable
private fun SkyPlot(sats: List<Sat>) {
    val line = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    Canvas(Modifier.fillMaxWidth().height(260.dp)) {
        val c = Offset(size.width / 2, size.height / 2); val r = minOf(size.width, size.height) / 2 - 10f
        for (f in listOf(1f, 0.66f, 0.33f)) drawCircle(line, r * f, c, style = Stroke(1.5f))
        drawLine(line, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1f); drawLine(line, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1f)
        sats.forEach { s ->
            val rr = r * (1f - s.el.coerceIn(0f, 90f) / 90f)
            val a = Math.toRadians(s.az.toDouble())
            val p = Offset(c.x + (rr * sin(a)).toFloat(), c.y - (rr * cos(a)).toFloat())
            val col = when { s.cn0 >= 35 -> Good; s.cn0 >= 25 -> Warn; else -> Bad }
            if (s.used) drawCircle(col, 9f, p) else drawCircle(col, 9f, p, style = Stroke(2.5f))
        }
    }
    Text("N at top · centre = overhead · rings = 30°, 60°", style = MaterialTheme.typography.labelSmall)
}

// ------------------------------------------------------------------------------------------------ Wi-Fi strength + interference

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
@Composable
fun RadioWifiScreen() {
    val ctx = LocalContext.current
    val wm = remember { ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager }
    var loc by remember { mutableStateOf(granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)) }
    val permL = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { loc = it }
    var rssi by remember { mutableStateOf(0) }
    var link by remember { mutableStateOf(0) }
    var ssid by remember { mutableStateOf("") }
    val hist = remember { mutableStateListOf<Float>() }
    var aps by remember { mutableStateOf<List<Ap>>(emptyList()) }
    var test by remember { mutableStateOf<List<String>?>(null) }
    var testing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var beep by remember { mutableStateOf(false) }
    val tone = remember { android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 60) }
    DisposableEffect(Unit) { onDispose { tone.release() } }
    val rssiNow by rememberUpdatedState(rssi)
    LaunchedEffect(beep) {
        // Geiger counter: the stronger the signal, the faster the clicks. Walk around to find hot or dead spots.
        while (beep) {
            tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 30)
            delay((1100 + (rssiNow + 30) * 12L).coerceIn(80L, 1200L))
        }
    }

    LaunchedEffect(loc) {
        var tick = 0
        while (true) {
            try {
                val ci = wm.connectionInfo
                if (ci != null && ci.networkId != -1) { rssi = ci.rssi; link = ci.linkSpeed; ssid = ci.ssid.trim('"'); hist.add(ci.rssi.toFloat()); while (hist.size > 90) hist.removeAt(0) }
                if (loc && tick % 8 == 0) {
                    wm.startScan()
                    aps = (wm.scanResults ?: emptyList()).map { r ->
                        val f = r.frequency
                        Ap(r.SSID.ifEmpty { "(hidden)" }, r.BSSID ?: "", r.level, f, Rf.channelOf(f), when { f > 5900 -> "6 GHz"; f > 4900 -> "5 GHz"; else -> "2.4 GHz" }, "", false,
                            if (Build.VERSION.SDK_INT >= 23) WifiMath.widthMhz(r.channelWidth) else 20)
                    }
                }
            } catch (e: Exception) { }
            tick++; delay(1000)
        }
    }
    val pct = ((rssi + 100) * 100 / 60).coerceIn(0, 100)
    val l24 = remember(aps) { WifiMath.load24(aps) }
    val l5 = remember(aps) { WifiMath.load5(aps) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
        item { Text("Wi-Fi signal & interference", style = MaterialTheme.typography.headlineSmall) }
        if (!loc) item { Section("Allow location", "Android only shows Wi-Fi strength details to apps that may see location.") { Button(onClick = { permL.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) { Text("Allow") } } }
        item {
            Section(if (ssid.isNotEmpty()) "Connected: $ssid" else "Not connected to Wi-Fi", if (ssid.isNotEmpty()) "Live, updates every second" else null) {
                if (ssid.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) { BigStat("signal", "$rssi dBm", Color(SiteRules.colorFor(pct))); BigStat("quality", "$pct%"); BigStat("link speed", "$link Mbps") }
                    PercentBar(pct, Color(SiteRules.colorFor(pct)), 10)
                    Text(when { rssi >= -50 -> "Excellent: right next to the router"; rssi >= -60 -> "Very good"; rssi >= -70 -> "Fair: fine for browsing, video may stutter"; rssi >= -80 -> "Weak: expect drops and slow speed"; else -> "Very weak: move closer to the router" }, fontWeight = FontWeight.Medium)
                    if (hist.size > 2) LineChart(hist.toList(), Info, 90, lo = -100f, hi = -30f)
                    FilterChip(selected = beep, onClick = { beep = !beep }, label = { Text("Geiger beeps (faster = stronger)") })
                    Hint("Walk around while watching this graph (or listening to the beeps) to find dead zones and the best spot for your router.")
                }
            }
        }
        if (aps.isNotEmpty()) {
            item {
                val best24 = listOf(1, 6, 11).minByOrNull { l24[it] ?: 0.0 } ?: 6
                val best5 = l5.minByOrNull { it.value }?.key
                Section("Channel congestion", "How many networks crowd each channel, weighted by strength. Lower is better.") {
                    Text("2.4 GHz", fontWeight = FontWeight.Bold)
                    HBars((1..13).map { "ch $it" to (l24[it] ?: 0.0) }) { "%.1f".format(it) }
                    Text("Best 2.4 GHz channel for your router: $best24 (use only 1, 6 or 11)", color = Good, fontWeight = FontWeight.Medium)
                    if (aps.any { it.band != "2.4 GHz" }) {
                        Text("5 GHz", fontWeight = FontWeight.Bold)
                        HBars(l5.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(8).map { "ch ${it.key}" to it.value }) { "%.1f".format(it) }
                        if (best5 != null) Text("Quietest 5 GHz channel: $best5", color = Good, fontWeight = FontWeight.Medium)
                    }
                }
            }
            item {
                Section("Strongest networks") {
                    aps.sortedByDescending { it.rssi }.take(12).forEach { a ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(a.ssid, Modifier.weight(1f), maxLines = 1)
                            Text("${a.band} ch${a.channel} · ${a.width} MHz · ${a.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        item {
            Section("Interference test (30 s)", "Measures how steady your connection is: signal jitter plus packet loss and latency spikes to the internet. Needs a connection.") {
                Button(enabled = !testing && ssid.isNotEmpty(), onClick = {
                    testing = true; test = null; progress = 0
                }) { Text(if (testing) "Testing… ${progress}s" else "Run test") }
                if (testing) LinearProgressIndicator({ progress / 30f }, Modifier.fillMaxWidth())
                test?.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
    LaunchedEffect(testing) {
        if (!testing) return@LaunchedEffect
        val rs = ArrayList<Int>(); val lat = ArrayList<Long>(); var lost = 0
        withContext(Dispatchers.IO) {
            for (i in 1..30) {
                progress = i
                try { rs.add(wm.connectionInfo.rssi) } catch (e: Exception) { }
                val t0 = System.nanoTime()
                try { Socket().use { it.connect(InetSocketAddress("1.1.1.1", 443), 900) }; lat.add((System.nanoTime() - t0) / 1_000_000) } catch (e: Exception) { lost++ }
                delay(900)
            }
        }
        val mean = rs.average(); val sd = sqrt(rs.map { (it - mean) * (it - mean) }.average())
        val avgL = if (lat.isEmpty()) 0.0 else lat.average(); val jit = if (lat.size < 2) 0.0 else lat.zipWithNext { a, b -> abs(a - b).toDouble() }.average()
        val maxL = lat.maxOrNull() ?: 0L; val lossPct = lost * 100 / 30
        val out = ArrayList<String>()
        out += "Signal: avg ${"%.0f".format(mean)} dBm, wobble ±${"%.1f".format(sd)} dB"
        out += "Internet latency: avg ${"%.0f".format(avgL)} ms, jitter ${"%.0f".format(jit)} ms, worst $maxL ms, lost $lossPct%"
        val sameCh = aps.count { a -> a.ssid != ssid && aps.firstOrNull { it.ssid == ssid }?.let { me -> a.band == me.band && abs(a.channel - me.channel) <= (if (me.band == "2.4 GHz") 4 else 0) } == true && a.rssi > -80 }
        val bad = (sd > 4 && mean > -70) || lossPct >= 5 || jit > 60
        out += if (bad && mean > -70) "Verdict: INTERFERENCE or congestion likely. Strong signal but unstable link${if (sameCh > 0) "; $sameCh nearby network(s) share your channel" else ""}. Change channel, switch to 5 GHz, or move away from microwaves/baby monitors/Bluetooth speakers."
        else if (bad) "Verdict: weak signal is the main problem. Move closer or add an access point."
        else "Verdict: stable. No interference pattern detected."
        test = out; testing = false
    }
}

// ------------------------------------------------------------------------------------------------ BLE inspector

private class BleSeen(val addr: String, var name: String?, val first: Long) {
    var last = first; var count = 0; var rssi = 0; var raw = ByteArray(0); var adv: Ble.Adv? = null
    val rs = ArrayList<Int>()
}

@SuppressLint("MissingPermission")
@Composable
fun RadioBleScreen() {
    val ctx = LocalContext.current
    val perms = remember { if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION) }
    var ok by remember { mutableStateOf(perms.all { granted(ctx, it) }) }
    val permL = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { ok = perms.all { p -> granted(ctx, p) } }
    val seen = remember { HashMap<String, BleSeen>() }
    var tick by remember { mutableStateOf(0) }
    var scanning by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("All") }
    var open by remember { mutableStateOf("") }
    val adapter = remember { (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter }

    DisposableEffect(scanning, ok) {
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                val addr = r.device.address ?: return
                val now = System.currentTimeMillis()
                val s = synchronized(seen) { seen.getOrPut(addr) { BleSeen(addr, null, now) } }
                s.last = now; s.count++; s.rssi = r.rssi; s.rs.add(r.rssi); if (s.rs.size > 60) s.rs.removeAt(0)
                r.scanRecord?.let { rec -> s.name = rec.deviceName ?: s.name; rec.bytes?.let { b -> s.raw = b; s.adv = Ble.parse(b) } }
                tick++
            }
        }
        var started = false
        if (scanning && ok && adapter?.isEnabled == true) try {
            adapter.bluetoothLeScanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb); started = true
        } catch (e: Exception) { }
        onDispose { if (started) try { adapter?.bluetoothLeScanner?.stopScan(cb) } catch (e: Exception) { } }
    }
    val list = remember(tick, filter) {
        synchronized(seen) {
            seen.values.filter { s -> val a = s.adv; when (filter) {
                "Trackers" -> a?.tracker != null; "Beacons" -> a?.beacon != null; "Sensors" -> a?.env != null || a?.services?.contains("181A") == true
                "Named" -> !s.name.isNullOrBlank(); else -> true } }.sortedByDescending { it.rssi }.take(80)
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
        item { Text("Bluetooth advertisements", style = MaterialTheme.typography.headlineSmall) }
        item {
            Section("Inspect what nearby devices broadcast", "Every Bluetooth LE device shouts a small packet many times a second. This decodes it: maker, services, beacons, sensors and possible trackers.") {
                if (!ok) Button(onClick = { permL.launch(perms) }) { Text("Allow Bluetooth scanning") }
                else if (adapter == null || !adapter.isEnabled) Text("Turn Bluetooth on first.", color = Warn)
                else Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { scanning = !scanning }) { Text(if (scanning) "■ Stop" else "▶ Start scanning") }
                    OutlinedButton(onClick = { synchronized(seen) { seen.clear() }; tick++ }) { Text("Clear") }
                    Text("${seen.size} devices", style = MaterialTheme.typography.bodySmall)
                }
                ChipRow(listOf("All", "Trackers", "Beacons", "Sensors", "Named"), filter) { filter = it }
            }
        }
        items(list, key = { it.addr }) { s ->
            val a = s.adv
            val dist = Ble.distanceM(s.rs.takeLast(8).average().toInt(), a?.txPower?.takeIf { it < 0 })
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name ?: a?.mfr?.keys?.firstOrNull()?.let { Ble.companies[it] }?.let { "$it device" } ?: "Unnamed device", fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(s.addr, style = MonoStyle)
                        }
                        Column(horizontalAlignment = Alignment.End) { Text("${s.rssi} dBm", fontWeight = FontWeight.Medium); Text("~${"%.1f".format(dist)} m", style = MaterialTheme.typography.bodySmall) }
                        TextButton(onClick = { open = if (open == s.addr) "" else s.addr }) { Text(if (open == s.addr) "▲" else "▼") }
                    }
                    PercentBar(((s.rssi + 100) * 100 / 60).coerceIn(2, 100), Info, 3)
                    a?.tracker?.let { Text("📍 $it", color = Warn, style = MaterialTheme.typography.bodySmall) }
                    a?.beacon?.let { Text("📡 $it", style = MaterialTheme.typography.bodySmall) }
                    a?.env?.let { Text("🌡 $it", color = Good, style = MaterialTheme.typography.bodySmall) }
                    val mins = (s.last - s.first) / 60000
                    if (mins >= 10) Text("Has been around for $mins min", style = MaterialTheme.typography.bodySmall, color = Warn)
                    if (open == s.addr && a != null) {
                        Text(Ble.proximity(dist) + " · seen ${s.count}× · Tx power ${a.txPower ?: "n/a"}", style = MaterialTheme.typography.bodySmall)
                        a.facts.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        MonoBox(s.raw.joinToString(" ") { "%02X".format(it) })
                        OutlinedButton(onClick = { copyText(ctx, s.raw.joinToString("") { "%02X".format(it) }) }) { Text("Copy raw packet") }
                    }
                }
            }
        }
        item { Hint("Random addresses change every ~15 minutes on phones and AirTag-class trackers, so one object can appear as several entries. A tracker that stays with you for a long time is worth a look.") }
    }
}
