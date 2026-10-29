package com.toolkit.privacy

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun BluetoothScreen() {
    val ctx = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val scanner = remember { BtScanner(ctx) { version++ } }
    DisposableEffect(Unit) { onDispose { scanner.destroy() } }
    var permOk by remember { mutableStateOf(BtScanner.hasPermissions(ctx)) }
    var btOn by remember { mutableStateOf(BtScanner.adapter(ctx)?.isEnabled == true) }
    val hasBt = remember { BtScanner.adapter(ctx) != null }
    var filter by remember { mutableStateOf("All") }
    var open by remember { mutableStateOf<String?>(null) }
    val probes = remember { mutableStateMapOf<String, List<String>>() }
    val probing = remember { mutableStateMapOf<String, Boolean>() }
    var locOn by remember { mutableStateOf(BtScanner.locationOn(ctx)) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permOk = BtScanner.hasPermissions(ctx) }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { btOn = BtScanner.adapter(ctx)?.isEnabled == true }

    LaunchedEffect(Unit) { while (true) { permOk = BtScanner.hasPermissions(ctx); btOn = BtScanner.adapter(ctx)?.isEnabled == true; locOn = BtScanner.locationOn(ctx); delay(1500) } }
    LaunchedEffect(permOk, btOn) { if (permOk && btOn) { scanner.loadPaired(); scanner.start() } }

    @Suppress("UNUSED_VARIABLE") val v = version
    val all = scanner.devices.values.toList()
    val trackers = all.filter { BtLogic.tracker(it) != null }
    val list = all.filter {
        when (filter) {
            "Connected" -> it.connected
            "Paired" -> it.bonded
            "Trackers" -> BtLogic.tracker(it) != null
            "Named" -> !it.name.isNullOrBlank()
            else -> true
        }
    }.sortedWith(compareByDescending<BtDevice> { it.connected }.thenByDescending { it.rssi ?: -200 })

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
        item { Text("Bluetooth Scanner", style = MaterialTheme.typography.headlineSmall) }
        if (!hasBt) { item { Text("This phone has no Bluetooth.") }; return@LazyColumn }
        if (!permOk) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Needs permission to find nearby devices.")
                    Button(onClick = { permLauncher.launch(BtScanner.neededPermissions()) }) { Text("Allow") }
                }
            }
        } else if (!btOn) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Bluetooth is off.")
                    Button(onClick = { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }) { Text("Turn on") }
                }
            }
        } else {
            if (!locOn) item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Android 11 and older only show nearby devices when Location is on.", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Open Location settings") }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { if (scanner.scanning) scanner.stop() else scanner.start() }) { Text(if (scanner.scanning) "Stop" else "Scan again") }
                    if (scanner.scanning) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("${all.size} found · ${all.count { it.connected }} connected · ${all.count { it.bonded }} paired", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (trackers.isNotEmpty()) item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0x33F59E0B))) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("📍 ${trackers.size} item tracker${if (trackers.size > 1) "s" else ""} nearby", fontWeight = FontWeight.Bold)
                        Text("AirTag, Tile and SmartTag style trackers: ${trackers.joinToString(", ") { BtLogic.tracker(it)!! }}. If one is yours, fine. If one keeps showing up wherever you go and you don't own it, check your bag, car and clothes.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("All", "Connected", "Paired", "Trackers", "Named")) { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f) }) }
                }
            }
            if (list.isEmpty()) item { Text(if (scanner.scanning) "Looking around..." else "Nothing here. Try another filter or scan again.", style = MaterialTheme.typography.bodySmall) }
            items(list, key = { it.address }) { d ->
                Card(Modifier.fillMaxWidth().clickable { open = if (open == d.address) null else d.address }) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(BtLogic.icon(d), fontSize = 24.sp)
                            Column(Modifier.weight(1f)) {
                                Text(BtLogic.title(d), fontWeight = FontWeight.Medium, maxLines = 1)
                                Text(BtLogic.kind(d) + (if (d.connected) " · connected" else if (d.bonded) " · paired" else ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            d.rssi?.let { Column(horizontalAlignment = Alignment.End) { Text("$it dBm", style = MaterialTheme.typography.labelMedium); Text(BtLogic.distanceLabel(it, d.txPower).substringBefore(" ("), style = MaterialTheme.typography.labelSmall) } }
                        }
                        d.rssi?.let { PercentBar(BtLogic.signalPct(it), Color(SiteRules.colorFor(BtLogic.signalPct(it))), 6) }
                        if (open == d.address) {
                            HorizontalDivider()
                            BtLogic.describe(d).forEach { (k, v2) ->
                                Row { Text(k, Modifier.width(120.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(v2, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall) }
                            }
                            BtLogic.addressNote(d.address)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            probes[d.address]?.let { ls -> ls.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) } }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (d.connectable != false) OutlinedButton(enabled = probing[d.address] != true, onClick = {
                                    probing[d.address] = true
                                    scanner.stop()
                                    scanner.probe(d.address) { res -> probes[d.address] = res; probing[d.address] = false }
                                }) { Text(if (probing[d.address] == true) "Reading..." else "Read device info") }
                                OutlinedButton(onClick = { copyText(ctx, d.address) }) { Text("Copy MAC") }
                            }
                            Text("Reading info connects to the device briefly. Many devices only answer if they're not already in use.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
