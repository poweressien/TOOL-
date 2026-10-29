package com.toolkit.privacy

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.CellSignalStrength
import android.telephony.TelephonyManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

data class Sig(val label: String, val dbm: Int?, val pct: Int, val level: Int, val detail: String)

private fun pctFromDbm(dbm: Int, lo: Int, hi: Int) = ((dbm - lo) * 100f / (hi - lo)).toInt().coerceIn(0, 100)
private fun levelOf(p: Int) = when { p >= 75 -> 4; p >= 55 -> 3; p >= 35 -> 2; p >= 15 -> 1; else -> 0 }
private fun qualityOf(p: Int) = when { p >= 75 -> "Excellent"; p >= 55 -> "Good"; p >= 35 -> "Fair"; p >= 15 -> "Weak"; else -> "Very weak" }
private fun colorOf(p: Int) = when { p >= 60 -> Color(0xFF34D399); p >= 35 -> Color(0xFFFBBF24); else -> Color(0xFFEF4444) }

@Suppress("DEPRECATION")
private fun readWifi(ctx: Context): Sig? {
    return try {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wm.isWifiEnabled) return null
        val info = wm.connectionInfo ?: return null
        val rssi = info.rssi
        if (rssi !in -126..0) return null
        val pct = pctFromDbm(rssi, -100, -50)
        val f = info.frequency
        val band = when { f > 5900 -> "6 GHz"; f > 4900 -> "5 GHz"; f > 0 -> "2.4 GHz"; else -> "" }
        Sig("Wi-Fi", rssi, pct, levelOf(pct), "${info.linkSpeed} Mbps link · $band")
    } catch (e: Exception) {
        null
    }
}

private fun readCell(ctx: Context): Sig? {
    if (Build.VERSION.SDK_INT < 28) return null
    return try {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        if (tm.simState != TelephonyManager.SIM_STATE_READY) return null
        val ss = tm.signalStrength ?: return null
        val cs: CellSignalStrength? = if (Build.VERSION.SDK_INT >= 29) ss.cellSignalStrengths.firstOrNull() else null
        val gen = cs?.javaClass?.simpleName.orEmpty().let {
            when {
                it.contains("Nr") -> "5G"
                it.contains("Lte") -> "4G"
                it.contains("Wcdma") || it.contains("Tdscdma") -> "3G"
                it.contains("Gsm") || it.contains("Cdma") -> "2G"
                else -> "Mobile"
            }
        }
        val raw = cs?.dbm
        val dbm = if (raw != null && raw != Int.MAX_VALUE && raw < 0) raw else null
        val pct = if (dbm != null) {
            if (gen == "5G" || gen == "4G") pctFromDbm(dbm, -120, -70) else pctFromDbm(dbm, -115, -65)
        } else ss.level * 25
        Sig("Cellular $gen", dbm, pct, levelOf(pct), tm.networkOperatorName.ifBlank { "Carrier" })
    } catch (e: Exception) {
        null
    }
}

private fun pingMs(): Long = try {
    val t0 = System.nanoTime()
    Socket().use { it.connect(InetSocketAddress("1.1.1.1", 443), 1500) }
    (System.nanoTime() - t0) / 1_000_000
} catch (e: Exception) {
    -1L
}

@Composable
fun SignalBars(level: Int, color: Color, modifier: Modifier = Modifier.size(52.dp, 42.dp)) {
    Canvas(modifier) {
        val unit = size.width / 7f
        for (i in 0 until 4) {
            val h = size.height * (i + 1) / 4f
            drawRoundRect(
                color = if (i < level) color else color.copy(alpha = 0.2f),
                topLeft = Offset(i * 2 * unit, size.height - h),
                size = Size(unit, h),
                cornerRadius = CornerRadius(unit / 3f)
            )
        }
    }
}

@Composable
fun PercentBar(pct: Int, color: Color, height: Int = 14) {
    val anim by animateFloatAsState(pct / 100f, label = "pct")
    val c by animateColorAsState(color, label = "c")
    LinearProgressIndicator(
        progress = { anim },
        color = c,
        trackColor = c.copy(alpha = 0.18f),
        modifier = Modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(10.dp))
    )
}

@Composable
fun Spark(values: List<Int>, color: Color) {
    Canvas(Modifier.fillMaxWidth().height(64.dp)) {
        if (values.size < 2) return@Canvas
        val dx = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * dx
            val y = size.height * (1 - v / 100f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 3.dp.toPx()))
    }
}

@Composable
private fun SignalCard(title: String, sig: Sig?, emptyText: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (sig == null) {
                Text(emptyText, style = MaterialTheme.typography.bodySmall)
            } else {
                val c = colorOf(sig.pct)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SignalBars(sig.level, c, Modifier.size(36.dp, 30.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("${sig.pct}%", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = c)
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(if (sig.dbm != null) "${sig.dbm} dBm" else "dBm n/a", fontWeight = FontWeight.Medium)
                        Text(qualityOf(sig.pct), style = MaterialTheme.typography.bodySmall, color = c)
                    }
                }
                PercentBar(sig.pct, c, 10)
                Text(sig.detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun SignalScreen() {
    val ctx = LocalContext.current
    var wifi by remember { mutableStateOf<Sig?>(null) }
    var cell by remember { mutableStateOf<Sig?>(null) }
    var latency by remember { mutableLongStateOf(-1L) }
    val history = remember { mutableStateListOf<Int>() }

    LaunchedEffect(Unit) {
        var tick = 0
        while (true) {
            wifi = readWifi(ctx)
            cell = readCell(ctx)
            (wifi ?: cell)?.let {
                history.add(it.pct)
                if (history.size > 60) history.removeAt(0)
            }
            if (tick % 3 == 0) latency = withContext(Dispatchers.IO) { pingMs() }
            tick++
            delay(1000)
        }
    }

    val hero = wifi ?: cell
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 14.dp)
    ) {
        item {
            Text("Live signal strength", style = MaterialTheme.typography.headlineSmall)
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (hero == null) {
                        Text("No Wi-Fi or mobile signal detected", style = MaterialTheme.typography.titleMedium)
                    } else {
                        val c = colorOf(hero.pct)
                        Text(hero.label, style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${hero.pct}%", fontSize = 56.sp, fontWeight = FontWeight.Bold, color = c)
                            Spacer(Modifier.weight(1f))
                            SignalBars(hero.level, c, Modifier.size(64.dp, 52.dp))
                        }
                        PercentBar(hero.pct, c, 18)
                        Row {
                            Text(qualityOf(hero.pct), color = c, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text(if (hero.dbm != null) "${hero.dbm} dBm" else "")
                        }
                        Spark(history.toList(), c)
                        Text("Last ${history.size}s", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        if (latency >= 0) "Latency: $latency ms (to 1.1.1.1)" else "Latency: no internet",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        item { SignalCard("Wi-Fi", wifi, "Not connected to Wi-Fi") }
        item { SignalCard("Mobile network", cell, "No SIM / no mobile signal") }
        item {
            Text(
                "Tip: walk around slowly and watch the bar to find the spot with the best signal. Percentages are estimated from dBm.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
