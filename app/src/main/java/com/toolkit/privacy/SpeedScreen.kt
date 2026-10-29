package com.toolkit.privacy

import android.content.Context
import android.net.TrafficStats
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private class SpeedResult(val ping: Long, val jitter: Long, val down: Double, val up: Double)

private fun tcpPing(): Long = try {
    val t0 = System.nanoTime()
    Socket().use { it.connect(InetSocketAddress("1.1.1.1", 443), 1500) }
    (System.nanoTime() - t0) / 1_000_000
} catch (e: Exception) { -1L }

private fun runTest(report: (String) -> Unit): SpeedResult {
    report("Measuring ping…")
    val pings = (1..8).map { tcpPing().also { Thread.sleep(80) } }.filter { it >= 0 }
    val ping = if (pings.isEmpty()) -1 else pings.sorted()[pings.size / 2]
    val jitter = if (pings.size < 2) 0 else pings.zipWithNext { a, b -> Math.abs(a - b) }.average().toLong()

    report("Testing download…")
    val d0 = System.nanoTime()
    var total = 0L
    val dc = URL("https://speed.cloudflare.com/__down?bytes=15000000").openConnection().apply { connectTimeout = 8000; readTimeout = 12000 }
    dc.getInputStream().use { ins ->
        val buf = ByteArray(65536)
        while (true) { val n = ins.read(buf); if (n < 0) break; total += n }
    }
    val down = total * 8 / 1e6 / ((System.nanoTime() - d0) / 1e9)

    report("Testing upload…")
    val bytes = 6_000_000
    val chunk = ByteArray(65536) { (it * 31).toByte() }
    val u0 = System.nanoTime()
    val uc = URL("https://speed.cloudflare.com/__up").openConnection() as HttpURLConnection
    uc.requestMethod = "POST"; uc.doOutput = true; uc.connectTimeout = 8000; uc.readTimeout = 15000
    uc.setFixedLengthStreamingMode(bytes)
    uc.outputStream.use { o -> var sent = 0; while (sent < bytes) { val n = minOf(chunk.size, bytes - sent); o.write(chunk, 0, n); sent += n } }
    uc.responseCode
    val up = bytes * 8 / 1e6 / ((System.nanoTime() - u0) / 1e9)
    uc.disconnect()
    return SpeedResult(ping, jitter, down, up)
}

@Composable
fun SpeedScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("speed", Context.MODE_PRIVATE) }
    var down by remember { mutableDoubleStateOf(0.0) }
    var up by remember { mutableDoubleStateOf(0.0) }
    var peak by remember { mutableDoubleStateOf(10.0) }
    val dHist = remember { mutableStateListOf<Int>() }
    val uHist = remember { mutableStateListOf<Int>() }
    var status by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf((prefs.getString("hist", "") ?: "").lines().filter { it.isNotBlank() }) }
    var last by remember { mutableStateOf<SpeedResult?>(null) }

    LaunchedEffect(Unit) {
        var prevRx = TrafficStats.getTotalRxBytes()
        var prevTx = TrafficStats.getTotalTxBytes()
        while (true) {
            delay(1000)
            val rx = TrafficStats.getTotalRxBytes()
            val tx = TrafficStats.getTotalTxBytes()
            if (rx >= 0 && prevRx >= 0) down = (rx - prevRx) * 8 / 1e6
            if (tx >= 0 && prevTx >= 0) up = (tx - prevTx) * 8 / 1e6
            prevRx = rx; prevTx = tx
            peak = maxOf(10.0, peak * 0.97, down, up)
            dHist.add((down / peak * 100).toInt().coerceIn(0, 100)); if (dHist.size > 60) dHist.removeAt(0)
            uHist.add((up / peak * 100).toInt().coerceIn(0, 100)); if (uHist.size > 60) uHist.removeAt(0)
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 14.dp)
    ) {
        item { Text("Speed", style = MaterialTheme.typography.headlineSmall) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Live (all apps on this phone)", style = MaterialTheme.typography.titleMedium)
                    Text("↓ %.2f Mbps".format(down), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color(0xFF34D399))
                    PercentBar((down / peak * 100).toInt().coerceIn(0, 100), Color(0xFF34D399), 14)
                    Spark(dHist.toList(), Color(0xFF34D399))
                    Text("↑ %.2f Mbps".format(up), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color(0xFF60A5FA))
                    PercentBar((up / peak * 100).toInt().coerceIn(0, 100), Color(0xFF60A5FA), 14)
                    Spark(uHist.toList(), Color(0xFF60A5FA))
                    Text("Scale: ${"%.0f".format(peak)} Mbps (auto)", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Speed test", style = MaterialTheme.typography.titleMedium)
                    last?.let {
                        Text("↓ %.1f Mbps    ↑ %.1f Mbps".format(it.down, it.up), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("Ping ${it.ping} ms · Jitter ${it.jitter} ms")
                    }
                    if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
                    Button(enabled = !testing, onClick = {
                        testing = true; last = null
                        scope.launch {
                            try {
                                val r = withContext(Dispatchers.IO) { runTest { status = it } }
                                last = r; status = "Done"
                                val line = "${SimpleDateFormat("MMM d HH:mm", Locale.US).format(Date())}|${r.ping}|${r.jitter}|%.1f|%.1f".format(r.down, r.up)
                                history = (listOf(line) + history).take(15)
                                prefs.edit().putString("hist", history.joinToString("\n")).apply()
                            } catch (e: Exception) { status = "Test failed: ${e.message}" }
                            testing = false
                        }
                    }) { Text(if (testing) "Testing…" else "Run speed test") }
                }
            }
        }
        if (history.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("History", style = MaterialTheme.typography.titleMedium)
                        history.forEach { l ->
                            val p = l.split("|")
                            if (p.size == 5) Text("${p[0]}   ↓${p[3]}  ↑${p[4]} Mbps   ${p[1]} ms", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { history = emptyList(); prefs.edit().remove("hist").apply() }) { Text("Clear history") }
                    }
                }
            }
        }
    }
}
