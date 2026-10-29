package com.toolkit.privacy

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Process
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Calendar

data class UsageRow(val label: String, val wifi: Long, val mobile: Long) { val total get() = wifi + mobile }

@Suppress("DEPRECATION")
private fun hasUsageAccess(ctx: Context): Boolean {
    val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
}

private fun perUid(nsm: NetworkStatsManager, type: Int, start: Long, end: Long): Map<Int, Long> {
    val m = HashMap<Int, Long>()
    try {
        val st = nsm.querySummary(type, null, start, end)
        val b = NetworkStats.Bucket()
        while (st.hasNextBucket()) {
            st.getNextBucket(b)
            m[b.uid] = (m[b.uid] ?: 0L) + b.rxBytes + b.txBytes
        }
        st.close()
    } catch (e: Exception) {
    }
    return m
}

private fun deviceTotal(nsm: NetworkStatsManager, type: Int, start: Long, end: Long): Long = try {
    val b = nsm.querySummaryForDevice(type, null, start, end)
    b.rxBytes + b.txBytes
} catch (e: Exception) { 0L }

private fun uidLabel(ctx: Context, uid: Int): String = when (uid) {
    1000 -> "Android system"
    NetworkStats.Bucket.UID_REMOVED -> "Removed apps"
    NetworkStats.Bucket.UID_TETHERING -> "Hotspot / tethering"
    else -> {
        val pm = ctx.packageManager
        pm.getPackagesForUid(uid)?.firstOrNull()?.let {
            try { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() } catch (e: Exception) { it }
        } ?: "UID $uid"
    }
}

@Composable
fun DataUsageScreen() {
    val ctx = LocalContext.current
    var access by remember { mutableStateOf(hasUsageAccess(ctx)) }
    var range by remember { mutableStateOf("Today") }
    var wifiTotal by remember { mutableLongStateOf(0L) }
    var mobTotal by remember { mutableLongStateOf(0L) }
    var rows by remember { mutableStateOf<List<UsageRow>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(access) {
        while (!access) { delay(1500); access = hasUsageAccess(ctx) }
    }

    LaunchedEffect(access, range) {
        if (!access) return@LaunchedEffect
        loading = true
        val end = System.currentTimeMillis()
        val start = when (range) {
            "Today" -> Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
            "7 days" -> end - 7L * 86_400_000
            else -> end - 30L * 86_400_000
        }
        withContext(Dispatchers.IO) {
            val nsm = ctx.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
            wifiTotal = deviceTotal(nsm, ConnectivityManager.TYPE_WIFI, start, end)
            mobTotal = deviceTotal(nsm, ConnectivityManager.TYPE_MOBILE, start, end)
            val w = perUid(nsm, ConnectivityManager.TYPE_WIFI, start, end)
            val m = perUid(nsm, ConnectivityManager.TYPE_MOBILE, start, end)
            rows = (w.keys + m.keys).map { uid -> UsageRow(uidLabel(ctx, uid), w[uid] ?: 0L, m[uid] ?: 0L) }
                .groupBy { it.label }
                .map { (k, v) -> UsageRow(k, v.sumOf { it.wifi }, v.sumOf { it.mobile }) }
                .sortedByDescending { it.total }.take(30)
        }
        loading = false
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 14.dp)
    ) {
        item { Text("Data usage", style = MaterialTheme.typography.headlineSmall) }
        if (!access) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Android needs \"Usage access\" to show per-app data. Find Privacy Toolkit in the list and switch it on. Data stays on your phone.")
                        Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Open Usage access") }
                    }
                }
            }
            return@LazyColumn
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Today", "7 days", "30 days").forEach { r ->
                    FilterChip(selected = range == r, onClick = { range = r }, label = { Text(r) })
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Column {
                        Text("Wi-Fi", style = MaterialTheme.typography.bodySmall)
                        Text(fmtSize(wifiTotal), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color(0xFF34D399))
                    }
                    Spacer(Modifier.width(24.dp))
                    Column {
                        Text("Mobile data", style = MaterialTheme.typography.bodySmall)
                        Text(fmtSize(mobTotal), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color(0xFFFBBF24))
                    }
                }
            }
        }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        val maxT = rows.maxOfOrNull { it.total }?.coerceAtLeast(1L) ?: 1L
        items(rows, key = { it.label }) { r ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row {
                        Text(r.label, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1)
                        Text(fmtSize(r.total), fontWeight = FontWeight.Bold)
                    }
                    PercentBar((r.total * 100 / maxT).toInt(), Color(0xFF60A5FA), 8)
                    Text("Wi-Fi ${fmtSize(r.wifi)} · Mobile ${fmtSize(r.mobile)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
