package com.toolkit.privacy

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MonitorScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sp = remember { Prefs.sp(ctx) }
    var tick by remember { mutableIntStateOf(0) }
    var on by remember { mutableStateOf(sp.getBoolean(Prefs.MON_ON, false)) }
    var interval by remember { mutableIntStateOf(sp.getInt(Prefs.MON_INTERVAL, 15)) }
    var dataMb by remember { mutableIntStateOf(sp.getInt(Prefs.MON_DATA_MB, 100)) }
    var lead by remember { mutableIntStateOf(sp.getInt(Prefs.MON_CAL_LEAD, 30)) }
    var report by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }

    LaunchedEffect(Unit) { while (true) { delay(1500); tick++ } }
    @Suppress("UNUSED_VARIABLE") val t = tick
    val usage = MonitorChecks.hasUsageAccess(ctx)
    val notif = Notify.canPost(ctx)
    val calls = MonitorChecks.granted(ctx, Manifest.permission.READ_CALL_LOG)
    val cal = MonitorChecks.granted(ctx, Manifest.permission.READ_CALENDAR)
    val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
    val battOk = pm.isIgnoringBatteryOptimizations(ctx.packageName)

    val flags = remember {
        mutableStateMapOf<String, Boolean>().apply {
            listOf(Prefs.MON_BG, Prefs.MON_DATA, Prefs.MON_NET, Prefs.MON_CALLS, Prefs.MON_CAL, Prefs.MON_BATT, Prefs.MON_STORAGE).forEach { put(it, sp.getBoolean(it, true)) }
        }
    }
    fun flag(key: String) = flags[key] ?: true
    fun toggle(key: String, v: Boolean) { flags[key] = v; sp.edit().putBoolean(key, v).apply() }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
        item { Text("Device Monitor", style = MaterialTheme.typography.headlineSmall) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SwitchRow("Watch my phone and pop up alerts", "Heads-up notifications for the things below. All checks run on the phone.", on) { v ->
                        on = v
                        if (v) {
                            if (Build.VERSION.SDK_INT >= 33 && !notif) launcher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                            MonitorService.start(ctx)
                        } else MonitorService.stop(ctx)
                    }
                    Text("Check every", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(5, 15, 30, 60).forEach { m ->
                            FilterChip(selected = interval == m, onClick = { interval = m; sp.edit().putInt(Prefs.MON_INTERVAL, m).apply() }, label = { Text("$m min") })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            Notify.alert(ctx, 4999, "Test alert", "If you can see this pop up, alerts are working.", urgent = false)
                        }) { Text("Send test pop-up") }
                        OutlinedButton(enabled = !busy, onClick = {
                            busy = true
                            scope.launch { report = withContext(Dispatchers.IO) { MonitorService.pass(ctx, post = false) }; busy = false }
                        }) { Text(if (busy) "Checking..." else "Run check now") }
                    }
                }
            }
        }
        if (report.isNotEmpty()) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Right now", fontWeight = FontWeight.Bold)
                    report.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Permissions", fontWeight = FontWeight.Bold)
                    if (Build.VERSION.SDK_INT >= 33) PermRow("Notifications", "To show the pop-ups", notif) { launcher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }
                    PermRow("Usage access", "Background apps and per-app data use", usage, "Open settings") {
                        ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    PermRow("Call log", "Only to spot new missed calls", calls) { launcher.launch(arrayOf(Manifest.permission.READ_CALL_LOG)) }
                    PermRow("Calendar", "Only to remind you about events starting soon", cal) { launcher.launch(arrayOf(Manifest.permission.READ_CALENDAR)) }
                    PermRow("Keep running in background", "Tecno, Infinix, itel, Xiaomi and Samsung can kill background apps. Allow this, and also lock the app in Recents / allow Autostart.", battOk, "Allow") {
                        ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("What to watch", fontWeight = FontWeight.Bold)
                    SwitchRow("Background apps", "3 or more apps running services in the background", flag(Prefs.MON_BG)) { toggle(Prefs.MON_BG, it) }
                    SwitchRow("Data drainers", "An app using lots of data in the last hour", flag(Prefs.MON_DATA)) { toggle(Prefs.MON_DATA, it) }
                    if (flag(Prefs.MON_DATA)) {
                        Text("Alert when one app uses more than", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(50, 100, 250, 500).forEach { m ->
                                FilterChip(selected = dataMb == m, onClick = { dataMb = m; sp.edit().putInt(Prefs.MON_DATA_MB, m).apply() }, label = { Text("$m MB") })
                            }
                        }
                    }
                    SwitchRow("Poor network", "Weak signal, slow or dead internet (needs 2 bad checks in a row)", flag(Prefs.MON_NET)) { toggle(Prefs.MON_NET, it) }
                    SwitchRow("Missed calls", "A pop-up when you miss a call", flag(Prefs.MON_CALLS)) { toggle(Prefs.MON_CALLS, it) }
                    SwitchRow("Calendar events", "Heads-up before an event starts", flag(Prefs.MON_CAL)) { toggle(Prefs.MON_CAL, it) }
                    if (flag(Prefs.MON_CAL)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(15, 30, 60).forEach { m ->
                                FilterChip(selected = lead == m, onClick = { lead = m; sp.edit().putInt(Prefs.MON_CAL_LEAD, m).apply() }, label = { Text("$m min before") })
                            }
                        }
                    }
                    SwitchRow("Low battery", "At 15% and below when not charging", flag(Prefs.MON_BATT)) { toggle(Prefs.MON_BATT, it) }
                    SwitchRow("Low storage", "When free space runs low", flag(Prefs.MON_STORAGE)) { toggle(Prefs.MON_STORAGE, it) }
                }
            }
        }
    }
}
