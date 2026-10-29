package com.toolkit.privacy

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

data class Ap(
    val ssid: String, val bssid: String, val rssi: Int, val freq: Int,
    val channel: Int, val band: String, val security: String, val connected: Boolean
)

private fun channelOf(f: Int) = when {
    f == 2484 -> 14
    f in 2412..2472 -> (f - 2407) / 5
    f in 5000..5900 -> (f - 5000) / 5
    f > 5900 -> (f - 5950) / 5
    else -> 0
}

private fun bandOf(f: Int) = when { f > 5900 -> "6 GHz"; f > 4900 -> "5 GHz"; else -> "2.4 GHz" }

private fun secOf(c: String) = when {
    c.contains("SAE") -> "WPA3"
    c.contains("EAP") -> "WPA2-Enterprise"
    c.contains("WPA2") || c.contains("RSN") -> "WPA2"
    c.contains("WPA") -> "WPA"
    c.contains("WEP") -> "WEP ⚠"
    else -> "Open ⚠"
}

@Composable
private fun ChannelChart(aps: List<Ap>) {
    val txt = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    Canvas(Modifier.fillMaxWidth().height(170.dp)) {
        val w = size.width
        val base = size.height - 22.dp.toPx()
        val top = 8.dp.toPx()
        val unit = w / 17f
        fun x(c: Float) = (c + 1f) * unit
        drawLine(Color.Gray.copy(alpha = 0.4f), Offset(0f, base), Offset(w, base), 1.dp.toPx())
        val paint = android.graphics.Paint().apply {
            color = txt; textSize = 10.sp.toPx(); textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true
        }
        for (c in 1..13) drawContext.canvas.nativeCanvas.drawText("$c", x(c.toFloat()), size.height - 5.dp.toPx(), paint)
        aps.filter { it.band == "2.4 GHz" && it.channel in 1..14 }.forEach { ap ->
            val h = (base - top) * ((ap.rssi + 100).coerceIn(0, 60) / 60f)
            val p = Path().apply {
                moveTo(x(ap.channel - 2f), base)
                quadraticBezierTo(x(ap.channel.toFloat()), base - 2 * h, x(ap.channel + 2f), base)
                close()
            }
            val col = Color.hsv(((ap.bssid.hashCode() and 0x7fffffff) % 360).toFloat(), 0.65f, 0.95f)
            drawPath(p, col.copy(alpha = 0.22f))
            drawPath(p, col, style = Stroke(2.dp.toPx()))
        }
    }
}

@Suppress("DEPRECATION")
@Composable
fun WifiAnalyzerScreen() {
    val ctx = LocalContext.current
    val wm = remember { ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager }
    var aps by remember { mutableStateOf<List<Ap>>(emptyList()) }
    var hasPerm by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPerm = it }

    fun load() {
        val cur = try { wm.connectionInfo?.bssid } catch (e: Exception) { null }
        aps = try {
            wm.scanResults.map { r ->
                Ap(
                    ssid = (r.SSID ?: "").ifBlank { "(hidden)" }, bssid = r.BSSID ?: "", rssi = r.level,
                    freq = r.frequency, channel = channelOf(r.frequency), band = bandOf(r.frequency),
                    security = secOf(r.capabilities ?: ""), connected = r.BSSID == cur
                )
            }.distinctBy { it.bssid }.sortedByDescending { it.rssi }
        } catch (e: SecurityException) { emptyList() }
    }

    LaunchedEffect(hasPerm) {
        if (hasPerm) {
            while (true) {
                try { wm.startScan() } catch (e: Exception) {}
                delay(2500)
                load()
                delay(7500)
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 14.dp)
    ) {
        item { Text("Wi-Fi analyzer", style = MaterialTheme.typography.headlineSmall) }
        if (!hasPerm) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Android requires Location permission to list nearby Wi-Fi networks. Nothing is sent anywhere.")
                        Button(onClick = { permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) { Text("Grant permission") }
                    }
                }
            }
            return@LazyColumn
        }
        item {
            val g24 = aps.filter { it.band == "2.4 GHz" }
            val scores = listOf(1, 6, 11).associateWith { ch ->
                g24.filter { Math.abs(it.channel - ch) <= 4 }.sumOf { (it.rssi + 100).coerceAtLeast(0) }
            }
            val best = scores.minByOrNull { it.value }?.key
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("2.4 GHz channels", style = MaterialTheme.typography.titleMedium)
                    ChannelChart(aps)
                    Text(
                        if (g24.isEmpty()) "Scanning…" else "Least crowded channel for your router: $best (1, 6 and 11 don't overlap).",
                        fontWeight = FontWeight.Medium
                    )
                    Text("${aps.size} networks · ${aps.count { it.band != "2.4 GHz" }} on 5/6 GHz · ${aps.count { it.security.contains("⚠") }} insecure", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (aps.isEmpty()) {
            item { Text("No results yet. Make sure Wi-Fi and Location are on. Android limits scans to a few per minute.", style = MaterialTheme.typography.bodySmall) }
        }
        items(aps, key = { it.bssid }) { ap ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row {
                        Text(ap.ssid + if (ap.connected) "  ✓ connected" else "", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("${ap.rssi} dBm", fontWeight = FontWeight.Medium)
                    }
                    PercentBar(((ap.rssi + 100) * 2).coerceIn(0, 100), if (ap.rssi >= -60) Color(0xFF34D399) else if (ap.rssi >= -75) Color(0xFFFBBF24) else Color(0xFFEF4444), 8)
                    Text("ch ${ap.channel} · ${ap.band} (${ap.freq} MHz) · ${ap.security}", style = MaterialTheme.typography.bodySmall)
                    Text(ap.bssid, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
