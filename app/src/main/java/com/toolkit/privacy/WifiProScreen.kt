package com.toolkit.privacy

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun sigColor(rssi: Int) = apSigColor(rssi)

@Suppress("DEPRECATION")
@Composable
fun WifiProScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val wm = remember { ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager }
    var hasPerm by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPerm = it }
    var aps by remember { mutableStateOf<List<ApDetail>>(emptyList()) }
    var link by remember { mutableStateOf<WifiLink?>(null) }
    val hist = remember { mutableStateListOf<Float>() }
    var band by remember { mutableStateOf("2.4 GHz") }
    var expanded by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf<List<SavedWifi>>(emptyList()) }
    var rootNote by remember { mutableStateOf("") }
    var scanNote by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(setOf<String>()) }
    var detail by remember { mutableStateOf<String?>(null) }
    var sort by remember { mutableStateOf("Signal") }
    var scanAt by remember { mutableStateOf(0L) }
    var peers by remember { mutableStateOf<List<DirectPeer>>(emptyList()) }
    var directNote by remember { mutableStateOf("") }
    var directOn by remember { mutableStateOf(false) }
    var hosts by remember { mutableStateOf<List<LanHost>>(emptyList()) }
    var sweepPct by remember { mutableStateOf(-1) }
    var sweepDone by remember { mutableStateOf(false) }
    val nearbyPerm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
    var hasNearby by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, nearbyPerm) == PackageManager.PERMISSION_GRANTED) }
    val nearbyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasNearby = it }
    val direct = remember { WifiDirectScanner(ctx, { peers = it }, { directNote = it }) }
    DisposableEffect(directOn, hasNearby) {
        if (directOn && hasNearby) direct.start()
        onDispose { direct.stop() }
    }

    LaunchedEffect(hasPerm) {
        if (hasPerm) while (true) {
            try { wm.startScan() } catch (e: Exception) { }
            delay(2500)
            aps = withContext(Dispatchers.IO) { WifiPro.scan(ctx) }
            scanAt = System.currentTimeMillis()
            delay(27500)
        }
    }
    LaunchedEffect(hasPerm) {
        while (true) {
            link = withContext(Dispatchers.IO) { WifiPro.connection(ctx, aps) }
            link?.let { hist.add(it.rssi.toFloat()); if (hist.size > 90) hist.removeAt(0) }
            delay(1000)
        }
    }

    Page("Wi-Fi Pro", "Everything about the network you are on and every network and device around you. All measured on this phone.") {
        if (!hasPerm) {
            Sec("Location permission needed") {
                Text("Android hides Wi-Fi names and signal levels unless Location is allowed. Nothing is sent anywhere.")
                Button(onClick = { permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) { Text("Grant permission") }
            }
        }

        Sec("Scan overview") {
            val withIe = aps.count { it.ext.parsed }
            Text("${aps.size} networks · ${aps.map { it.band }.distinct().size} band(s) · ${aps.count { it.security == "Open" }} open · ${aps.count { it.ssid == "(hidden)" }} hidden", fontWeight = FontWeight.Bold)
            val busy = aps.filter { it.ext.stations >= 0 }
            if (busy.isNotEmpty()) Text("Devices announced by routers in range: ${busy.sumOf { it.ext.stations }} across ${busy.size} routers", style = MaterialTheme.typography.bodyMedium)
            Note(if (Build.VERSION.SDK_INT >= 30) "Beacon details read for $withIe of ${aps.size} networks." else "Android 10 and older do not expose beacon details; only basic data is shown.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        try { wm.startScan() } catch (e: Exception) { }
                        delay(2500)
                        aps = withContext(Dispatchers.IO) { WifiPro.scan(ctx) }
                        scanAt = System.currentTimeMillis()
                    }
                }, enabled = hasPerm) { Text("Scan now") }
                if (scanAt > 0) Text("updated ${(System.currentTimeMillis() - scanAt) / 1000}s ago", style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically))
            }
            Note("Honest limit: Android only lets a phone see the devices connected to the network it has joined. For other routers it shows how many devices they report (the 'devices' count) but never who they are. Android also limits scans to a few per two minutes.")
        }

        val l = link
        Sec("Connected network") {
            if (l == null) Note("Not connected to Wi-Fi.")
            else {
                Text(l.ssid, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${l.rssi} dBm · ${WifiPro.quality(l.rssi)}", color = sigColor(l.rssi), fontWeight = FontWeight.Bold)
                PercentBar(((l.rssi + 100) * 2).coerceIn(0, 100), sigColor(l.rssi), 10)
                KLineChart(hist.toList(), sigColor(l.rssi), 80, minY = -100f, maxY = -30f)
                Note("Signal history (last ${hist.size}s). Walk around to find the dead spots.")
                Kv("Link speed", "${l.linkSpeed} Mbps" + if (l.txSpeed > 0) "  (↑${l.txSpeed} / ↓${l.rxSpeed})" else "")
                Kv("Band · channel", "${l.band} · ch ${l.channel} (${l.freq} MHz)")
                Kv("Wi-Fi standard", l.standard)
                Kv("Security", l.security)
                Kv("Router BSSID", l.bssid, true)
                MacVendor.offlineLookup(l.bssid)?.let { Kv("Router maker", it) }
                Kv("Your IP", l.ip, true)
                Kv("Gateway", l.gateway, true)
                Kv("Subnet mask", l.netmask, true)
                Kv("DHCP server", l.dhcp, true)
                Kv("DNS", l.dns.joinToString(", "), true)
                if (l.leaseSec > 0) Kv("DHCP lease", Fmt.dur(l.leaseSec.toLong()))
                if (l.mtu > 0) Kv("MTU", "${l.mtu}")
                if (l.domain.isNotEmpty()) Kv("Search domain", l.domain)
                if (l.deviceMac.isNotEmpty() && l.deviceMac != "02:00:00:00:00:00") Kv("Your MAC", l.deviceMac, true)
                else Kv("Your MAC", "hidden by Android (private MAC)")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { openUrl(ctx, "http://${l.gateway}") }, enabled = l.gateway.isNotEmpty()) { Text("Router page") }
                    OutlinedButton(onClick = {
                        copyText(ctx, "SSID ${l.ssid}\nBSSID ${l.bssid}\nIP ${l.ip}\nGateway ${l.gateway}\nDNS ${l.dns.joinToString()}")
                    }) { Text("Copy details") }
                }
                Note("Router page opens the admin login. If it still uses the factory username and password, change them.", false)
            }
        }

        Sec("Interference & signal analysis") {
            WifiPro.interference(l, aps).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        }

        Sec("Channel congestion") {
            Chips(listOf("2.4 GHz", "5 GHz", "6 GHz"), band) { band = it }
            val sc = WifiPro.channelScores(aps, band)
            val inUse = sc.filter { it.aps > 0 }
            if (aps.none { it.band == band }) Note("No $band networks seen.")
            else {
                KBarChart(inUse.map { it.congestion.toFloat().coerceAtLeast(2f) }, inUse.map { it.channel.toString() }, Color(0xFF60A5FA), 120)
                Note("Taller bar = more crowded (0-100). Only channels with traffic are drawn.")
                val best = WifiPro.bestChannels(sc, band)
                Text("Best channels for your router: " + best.joinToString(" · ") { "ch ${it.channel} (${it.congestion}%)" }, fontWeight = FontWeight.Medium)
                if (band == "2.4 GHz") Note("On 2.4 GHz only 1, 6 and 11 avoid overlapping each other.")
            }
        }

        Sec("Security check of the air around you") {
            WifiPro.threats(aps).forEach {
                Text("• $it", style = MaterialTheme.typography.bodyMedium, color = if (it.startsWith("Nothing")) MaterialTheme.colorScheme.onSurface else Color(0xFFF59E0B))
            }
        }

        Sec("Saved & connected Wi-Fi passwords") {
            Text("Android does not let normal apps read stored passwords. These are the real ways to see them:", style = MaterialTheme.typography.bodySmall)
            Text("1. No root: open Wi-Fi settings, tap your network, tap Share (unlock with fingerprint), then scan that QR here. The password is inside the QR.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                    GmsBarcodeScanning.getClient(ctx, opts).startScan()
                        .addOnSuccessListener { b ->
                            val w = WifiPro.parseWifiQr(b.rawValue ?: "")
                            if (w == null) scanNote = "That QR is not a Wi-Fi code."
                            else { saved = (saved.filter { it.ssid != w.ssid } + w); shown = shown + w.ssid; scanNote = "Read ${w.ssid}" }
                        }
                        .addOnFailureListener { scanNote = "Scanner unavailable: ${it.message}" }
                }) { Text("Scan Wi-Fi QR") }
                OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Wi-Fi settings") }
            }
            if (scanNote.isNotEmpty()) Note(scanNote)
            Text("2. Rooted phone: read every saved network and password at once.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = {
                rootNote = "Asking for root..."
                scope.launch {
                    val r = withContext(Dispatchers.IO) { WifiPro.readSavedAsRoot() }
                    rootNote = if (r.isEmpty()) "No root access (or no saved networks found). Use the QR method." else "Found ${r.size} saved network(s)."
                    if (r.isNotEmpty()) saved = (saved.filter { s -> r.none { it.ssid == s.ssid } } + r)
                }
            }) { Text("Read all saved (root)") }
            if (rootNote.isNotEmpty()) Note(rootNote)
            val visible = WifiPro.configuredNetworks(ctx)
            if (visible.isNotEmpty()) Note("Networks Android lets this app see: " + visible.joinToString(", "))
            saved.forEach { s ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(s.ssid, fontWeight = FontWeight.Bold)
                        Note("${s.security} · ${s.source}")
                        val pw = s.password
                        if (pw == null) Note("Open network, no password.")
                        else {
                            Text(if (s.ssid in shown) pw else "•".repeat(pw.length.coerceIn(6, 16)), style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { shown = if (s.ssid in shown) shown - s.ssid else shown + s.ssid }) { Text(if (s.ssid in shown) "Hide" else "Show") }
                                OutlinedButton(onClick = { copyText(ctx, pw, sensitive = true) }) { Text("Copy") }
                            }
                        }
                    }
                }
            }
        }

        Sec("Wi-Fi Direct devices nearby") {
            Note("Phones, TVs, printers and cameras that are visible for direct connection, joined to any network or none.")
            if (!hasNearby) {
                Button(onClick = { nearbyLauncher.launch(nearbyPerm) }) { Text("Allow nearby devices") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { directOn = !directOn; if (!directOn) peers = emptyList() }) { Text(if (directOn) "Stop" else "Start discovery") }
                    if (directOn) OutlinedButton(onClick = { direct.discover() }) { Text("Rescan") }
                }
            }
            if (directNote.isNotEmpty()) Note(directNote, true)
            if (directOn && peers.isEmpty() && directNote.isEmpty()) Note("Searching...")
            peers.forEach { p ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(p.name, fontWeight = FontWeight.Bold)
                        Text("${p.kind} · ${p.status} · ${p.role}", style = MaterialTheme.typography.bodySmall)
                        Kv("Address", p.mac, true)
                        p.vendor?.let { Kv("Maker", it) }
                        if (p.services) Note("Supports service discovery (can advertise apps/services).")
                    }
                }
            }
        }

        Sec("Devices on the network you joined") {
            val gw = l?.gateway.orEmpty()
            if (l == null) Note("Join a Wi-Fi network to list its devices.")
            else {
                Note("Sweeps ${l.ip.substringBeforeLast('.')}.1-254 with ping and port probes. Devices that ignore both will not appear.")
                Button(onClick = {
                    sweepPct = 0; sweepDone = false; hosts = emptyList()
                    scope.launch { hosts = WifiLanSweep.sweep(l.ip, gw) { sweepPct = it }; sweepDone = true; sweepPct = -1 }
                }, enabled = sweepPct < 0 && l.ip.isNotEmpty()) { Text(if (sweepPct >= 0) "Scanning $sweepPct%" else "Scan this network") }
                if (sweepPct >= 0) LinearProgressIndicator(progress = { sweepPct / 100f }, modifier = Modifier.fillMaxWidth())
                if (sweepDone) Text("${hosts.size} device(s) found", fontWeight = FontWeight.Bold)
                if (sweepDone && hosts.none { it.mac.isNotEmpty() }) Note("Android 11+ hides hardware (MAC) addresses from apps, so makers can only be guessed from open services.")
                hosts.forEach { h ->
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(h.ip + if (h.host.isNotEmpty()) "  ·  ${h.host}" else "", fontWeight = FontWeight.Bold)
                            Text("${h.hint} · ${h.rttMs} ms", style = MaterialTheme.typography.bodySmall)
                            if (h.mac.isNotEmpty()) Kv("MAC", h.mac + (h.vendor?.let { "  ($it)" } ?: ""), true)
                            if (h.ports.isNotEmpty()) Kv("Open ports", h.ports.joinToString(", "), true)
                        }
                    }
                }
            }
        }

        Sec("All networks in range (${aps.size})") {
            Chips(listOf("Signal", "Name", "Busiest", "Devices", "Risky"), sort) { sort = it }
            OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), label = { Text("Filter by name, maker, security or band") }, singleLine = true)
            val matched = aps.filter { filter.isBlank() || it.ssid.contains(filter, true) || it.security.contains(filter, true) || (it.vendor ?: "").contains(filter, true) || it.bssid.contains(filter, true) || it.band.contains(filter, true) }
            val list = when (sort) {
                "Name" -> matched.sortedBy { it.ssid.lowercase() }
                "Busiest" -> matched.sortedByDescending { it.ext.utilPct }
                "Devices" -> matched.sortedByDescending { it.ext.stations }
                "Risky" -> matched.sortedByDescending { it.ext.risks.size }
                else -> matched.sortedByDescending { it.rssi }
            }
            list.forEach { ap ->
                Card(Modifier.fillMaxWidth(), onClick = { detail = ap.bssid }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row {
                            Text(ap.ssid + if (ap.connected) "  ✓" else "", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text("${ap.rssi} dBm", color = sigColor(ap.rssi), fontWeight = FontWeight.Medium)
                        }
                        PercentBar(((ap.rssi + 100) * 2).coerceIn(0, 100), sigColor(ap.rssi), 6)
                        Text("${ap.band} · ch ${ap.channel} · ${ap.widthMhz} MHz · ${ap.standard} · ${ap.security}", style = MaterialTheme.typography.bodySmall)
                        Text("≈ ${"%.1f".format(ap.distanceM)} m · ${ap.vendor ?: "unknown maker"}", style = MaterialTheme.typography.bodySmall)
                        val badges = apBadges(ap)
                        if (badges.isNotEmpty()) Text(badges, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        if (ap.ext.risks.isNotEmpty()) Text("⚠ " + ap.ext.risks.first(), style = MaterialTheme.typography.bodySmall, color = Color(0xFFF59E0B))
                        Note("Tap for full details", false)
                    }
                }
            }
        }
    }
    aps.firstOrNull { it.bssid == detail }?.let { ApDetailDialog(it, ctx) { detail = null } }
}
