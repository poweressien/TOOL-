package com.toolkit.privacy

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

object WifiMath {
    fun widthMhz(code: Int) = when (code) { 0 -> 20; 1 -> 40; 2 -> 80; 3 -> 160; 4 -> 160; 5 -> 320; else -> 20 }
    fun standardName(code: Int) = when (code) { 1 -> "legacy"; 4 -> "Wi-Fi 4 (802.11n)"; 5 -> "Wi-Fi 5 (802.11ac)"; 6 -> "Wi-Fi 6 (802.11ax)"; 7 -> "802.11ad"; 8 -> "Wi-Fi 7 (802.11be)"; else -> "unknown" }

    /** Free-space path-loss estimate in metres. Rough: walls make real distance smaller than this. */
    fun distanceM(rssi: Int, freqMhz: Int): Double = 10.0.pow((27.55 - 20 * log10(freqMhz.toDouble()) + abs(rssi)) / 20.0)

    /** Crowding of each 2.4 GHz channel 1..13: overlapping networks weighted by strength. */
    fun load24(aps: List<Ap>): Map<Int, Double> = (1..13).associateWith { ch ->
        aps.filter { it.band == "2.4 GHz" && it.channel in 1..14 }.sumOf { ap ->
            val d = abs(ap.channel - ch)
            if (d >= 5) 0.0 else (1.0 - d / 5.0) * ((ap.rssi + 100).coerceIn(0, 70) / 70.0)
        }
    }

    fun load5(aps: List<Ap>): Map<Int, Double> {
        val chans = listOf(36, 40, 44, 48, 52, 56, 60, 64, 100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 144, 149, 153, 157, 161, 165)
        return chans.associateWith { ch ->
            aps.filter { it.band == "5 GHz" }.sumOf { ap ->
                val span = ap.width.coerceAtLeast(20) / 2
                val covered = abs(ap.channel - ch) * 5 <= span
                if (covered) ((ap.rssi + 100).coerceIn(0, 70) / 70.0) else 0.0
            }
        }
    }

    fun twins(aps: List<Ap>): List<String> {
        val out = ArrayList<String>()
        aps.filter { it.ssid != "(hidden)" }.groupBy { it.ssid }.forEach { (name, g) ->
            val secs = g.map { it.security.replace(" ⚠", "") }.toSet()
            if (secs.size > 1) out += "\"$name\" appears with different security (${secs.joinToString()}). A fake copy of a network can steal passwords. Only connect to the one you know."
            if (g.any { it.security.startsWith("Open") } && g.size > 1) out += "\"$name\" has an OPEN access point next to protected ones: possible evil twin."
        }
        aps.filter { it.security.startsWith("WEP") }.forEach { out += "\"${it.ssid}\" uses WEP, which can be cracked in minutes." }
        return out
    }
}

@Suppress("DEPRECATION")
@Composable
fun ConnectedWifiCard() {
    val ctx = LocalContext.current
    val wm = remember { ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager }
    val hist = remember { mutableStateListOf<Float>() }
    var lines by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(Unit) {
        while (true) {
            try {
                val ci = wm.connectionInfo
                if (ci != null && ci.networkId != -1) {
                    hist.add(ci.rssi.toFloat()); if (hist.size > 90) hist.removeAt(0)
                    val l = ArrayList<Pair<String, String>>()
                    l += "Network" to (ci.ssid?.trim('"') ?: "?")
                    l += "BSSID (router MAC)" to (ci.bssid ?: "?")
                    l += "Signal" to "${ci.rssi} dBm  (~${"%.0f".format(WifiMath.distanceM(ci.rssi, ci.frequency.coerceAtLeast(2412)))} m from router in open air)"
                    l += "Link speed" to "${ci.linkSpeed} Mbps"
                    if (Build.VERSION.SDK_INT >= 29) { l += "Up / down link" to "${ci.txLinkSpeedMbps} / ${ci.rxLinkSpeedMbps} Mbps" }
                    l += "Frequency" to "${ci.frequency} MHz (${if (ci.frequency > 5900) "6" else if (ci.frequency > 4900) "5" else "2.4"} GHz)"
                    if (Build.VERSION.SDK_INT >= 30) l += "Standard" to WifiMath.standardName(ci.wifiStandard)
                    l += "Your IP" to NetIntel.intToIp(Integer.reverseBytes(ci.ipAddress))
                    val dhcp = wm.dhcpInfo
                    if (dhcp != null) {
                        l += "Router IP" to NetIntel.intToIp(Integer.reverseBytes(dhcp.gateway))
                        l += "DNS" to "${NetIntel.intToIp(Integer.reverseBytes(dhcp.dns1))} / ${NetIntel.intToIp(Integer.reverseBytes(dhcp.dns2))}"
                        l += "Netmask" to NetIntel.intToIp(Integer.reverseBytes(dhcp.netmask))
                        l += "DHCP lease" to "${dhcp.leaseDuration / 3600} h"
                    }
                    lines = l
                } else lines = emptyList()
            } catch (e: Exception) { }
            delay(1000)
        }
    }
    Section("Connected network (live)") {
        if (lines.isEmpty()) Text("Not connected to Wi-Fi.") else {
            lines.forEach { KV(it.first, it.second) }
            Hint("Signal history (last ${hist.size}s) · -30 dBm best, -90 dBm unusable")
            LineChart(hist.toList(), Info, 90, lo = -100f, hi = -30f)
        }
    }
}

@Composable
fun InterferenceCard(aps: List<Ap>) {
    val l24 = WifiMath.load24(aps)
    val l5 = WifiMath.load5(aps).filter { aps.any { a -> a.band == "5 GHz" } }
    val best24 = l24.minByOrNull { it.value }?.key
    Section("Interference & best channel", "Co-channel and adjacent-channel crowding, weighted by signal strength") {
        Text("2.4 GHz channels 1–13", fontWeight = FontWeight.Medium)
        BarChart(l24.values.map { it.toFloat() }, Warn, 90, highlightLast = false)
        Hint("Bars: higher = more interference. Quietest channel: $best24" + if (best24 !in listOf(1, 6, 11)) "  (tip: channels 1, 6, 11 do not overlap)" else "")
        val worst = l24.maxByOrNull { it.value }
        if (worst != null && worst.value > 2.0) Text("⚠ Channel ${worst.key} is heavily congested (${"%.1f".format(worst.value)} load). Move your router away from it.", color = Warn, style = MaterialTheme.typography.bodySmall)
        val same = aps.filter { it.band == "2.4 GHz" }.groupBy { it.channel }.filter { it.value.size >= 3 }
        same.forEach { (c, g) -> Text("• ${g.size} networks share channel $c (co-channel interference)", style = MaterialTheme.typography.bodySmall) }
        if (l5.isNotEmpty()) {
            Text("5 GHz channels", fontWeight = FontWeight.Medium)
            BarChart(l5.values.map { it.toFloat() }, Info, 80, highlightLast = false)
            Hint("Quietest 5 GHz channel: ${l5.minByOrNull { it.value }?.key}")
        }
        Hint("Non-Wi-Fi interference (microwaves, Bluetooth, baby monitors) cannot be measured by a phone; if signal is good but speed is poor and channels are quiet, suspect those.")
    }
}

@Composable
fun SecurityWatchCard(aps: List<Ap>) {
    val issues = WifiMath.twins(aps)
    val hidden = aps.count { it.ssid == "(hidden)" }
    val wps = aps.filter { it.wps }
    Section("Wi-Fi security watch") {
        if (issues.isEmpty()) Text("✅ No evil-twin or WEP networks spotted nearby.", color = Good)
        issues.forEach { Text("⚠ $it", color = Warn, style = MaterialTheme.typography.bodySmall) }
        if (hidden > 0) Text("$hidden hidden network(s) nearby", style = MaterialTheme.typography.bodySmall)
        if (wps.isNotEmpty()) Text("WPS (PIN-based setup) is on for: ${wps.take(6).joinToString { it.ssid }}. WPS PINs can be brute-forced; turn it off on your router.", style = MaterialTheme.typography.bodySmall)
        Text("${aps.count { it.security.contains("WPA3") }} WPA3 · ${aps.count { it.security == "WPA2" }} WPA2 · ${aps.count { it.security.contains("⚠") }} weak/open", style = MaterialTheme.typography.bodySmall)
    }
}

private fun wifiQr(text: String, size: Int = 640): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8"))
    val px = IntArray(size * size)
    for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (m.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

@Composable
fun WifiPasswordsCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(WifiVaultStore.load(ctx)) }
    var found by remember { mutableStateOf<WifiCred?>(null) }
    var msg by remember { mutableStateOf("") }
    val shown = remember { mutableStateListOf<String>() }
    var qrFor by remember { mutableStateOf<WifiCred?>(null) }
    var manualSsid by remember { mutableStateOf("") }
    var manualPw by remember { mutableStateOf("") }

    fun handle(raw: String?) {
        val c = raw?.let { WifiQr.parse(it) }
        if (c == null) { msg = "That QR is not a Wi-Fi QR code."; return }
        found = c; msg = "Password revealed. Save it to keep it in your encrypted list."
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) scope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) { loadBitmap(ctx, uri, 2000) } ?: error("Cannot open image")
                val client = BarcodeScanning.getClient()
                val list = client.process(InputImage.fromBitmap(bmp, 0)).await(); client.close()
                handle(list.firstOrNull()?.rawValue)
            } catch (e: Exception) { msg = "Scan failed: ${e.message}" }
        }
    }

    Section("Wi-Fi passwords", "Android never lets apps read saved Wi-Fi passwords directly. These are the legitimate ways to see one for a network you are on or own.") {
        Text("1. Reveal the password of the network you are connected to", fontWeight = FontWeight.Medium)
        Text("Open Wi-Fi settings → tap your network → Share → confirm with fingerprint/PIN. Android shows a QR code with the password inside. Take a screenshot of it and pick it here (or scan it from another phone's screen).", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Wi-Fi settings") }
            Button(onClick = { pick.launch("image/*") }) { Text("Pick QR screenshot") }
        }
        OutlinedButton(onClick = {
            val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
            GmsBarcodeScanning.getClient(ctx, opts).startScan().addOnSuccessListener { handle(it.rawValue) }.addOnFailureListener { msg = "Scanner unavailable: ${it.message}" }
        }) { Text("Scan QR with camera") }
        Text("2. Rooted phones", fontWeight = FontWeight.Medium)
        Text("If your phone is rooted, the app can read every saved network and password from the system Wi-Fi store.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = {
            scope.launch {
                msg = "Asking for root…"
                val list = withContext(Dispatchers.IO) { WifiQr.readSavedAsRoot() }
                if (list.isEmpty()) msg = "No root access (or no saved networks). Use method 1 instead."
                else { list.forEach { WifiVaultStore.add(ctx, it) }; saved = WifiVaultStore.load(ctx); msg = "Imported ${list.size} saved network(s) from the system." }
            }
        }) { Text("Read saved networks (root)") }
        if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        found?.let { c ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(c.ssid, fontWeight = FontWeight.Bold)
                    Text(if (c.password.isEmpty()) "Open network (no password)" else "Password: ${c.password}", fontWeight = FontWeight.Medium)
                    Text("Security: ${c.security}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { copyText(ctx, c.password, sensitive = true) }) { Text("Copy") }
                        OutlinedButton(onClick = { WifiVaultStore.add(ctx, c); saved = WifiVaultStore.load(ctx); found = null }) { Text("Save") }
                    }
                }
            }
        }
        Text("3. Add one yourself", fontWeight = FontWeight.Medium)
        OutlinedTextField(manualSsid, { manualSsid = it }, Modifier.fillMaxWidth(), label = { Text("Network name") }, singleLine = true)
        OutlinedTextField(manualPw, { manualPw = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true)
        Button(enabled = manualSsid.isNotBlank(), onClick = {
            WifiVaultStore.add(ctx, WifiCred(manualSsid.trim(), manualPw, if (manualPw.isEmpty()) "nopass" else "WPA", false))
            saved = WifiVaultStore.load(ctx); manualSsid = ""; manualPw = ""
        }) { Text("Save to encrypted list") }
        if (saved.isNotEmpty()) {
            HorizontalDivider()
            Text("Your saved networks (encrypted, fingerprint/PIN to view)", fontWeight = FontWeight.Medium)
            saved.forEach { c ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(c.ssid, fontWeight = FontWeight.Bold)
                        Text(if (c.ssid in shown) (c.password.ifEmpty { "(open)" }) else "••••••••", style = MonoStyle)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = {
                                if (c.ssid in shown) shown.remove(c.ssid) else AuthGate.confirm(ctx, "Show Wi-Fi password", { shown.add(c.ssid) }, { msg = it })
                            }) { Text(if (c.ssid in shown) "Hide" else "Show") }
                            TextButton(onClick = { AuthGate.confirm(ctx, "Copy Wi-Fi password", { copyText(ctx, c.password, sensitive = true) }, { msg = it }) }) { Text("Copy") }
                            TextButton(onClick = { AuthGate.confirm(ctx, "Show Wi-Fi QR", { qrFor = c }, { msg = it }) }) { Text("QR") }
                            TextButton(onClick = { WifiVaultStore.remove(ctx, c.ssid); saved = WifiVaultStore.load(ctx); if (qrFor?.ssid == c.ssid) qrFor = null }) { Text("Delete") }
                        }
                    }
                }
            }
        }
        qrFor?.let { c ->
            Text("Guests scan this to join ${c.ssid}", style = MaterialTheme.typography.bodySmall)
            Image(wifiQr(WifiQr.toQr(c)).asImageBitmap(), "Wi-Fi QR", Modifier.size(240.dp))
        }
        Hint("Router default logins are listed in the router's sticker or manual. This app never tries to guess or crack passwords.")
    }
}
