package com.toolkit.privacy

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val resolvers = listOf("Cloudflare" to "1.1.1.1", "Google" to "8.8.8.8", "Quad9" to "9.9.9.9")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShieldScreen() {
    val ctx = LocalContext.current
    val running by ShieldState.running.collectAsState()
    val total by ShieldState.total.collectAsState()
    val blocked by ShieldState.blocked.collectAsState()
    val entries by ShieldState.entries.collectAsState()
    val top by ShieldState.top.collectAsState()
    var upstream by remember { mutableStateOf("1.1.1.1") }
    var custom by remember { mutableStateOf(Blocklist.custom.toList().sorted()) }
    var newDomain by remember { mutableStateOf("") }
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    LaunchedEffect(Unit) {
        Blocklist.load(ctx)
        custom = Blocklist.custom.toList().sorted()
    }

    fun begin() {
        ctx.startService(Intent(ctx, DnsShieldService::class.java).putExtra("upstream", upstream))
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) begin()
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 14.dp)
    ) {
        item {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF1D4ED8), Color(0xFF1E3A8A), Color(0xFF0B1220))))
                    .padding(22.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusRing(if (running) "Connected" else "Disconnected")
                    Text(if (running) "DNS Shield ON" else "DNS Shield OFF", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                        Stat("Queries", total.toString())
                        Stat("Blocked", blocked.toString())
                        Stat("Rate", if (total > 0) "${blocked * 100 / total}%" else "0%")
                    }
                }
            }
        }
        item {
            Button(
                modifier = Modifier.fillMaxWidth().height(52.dp),
                onClick = {
                    if (running) {
                        ctx.startService(Intent(ctx, DnsShieldService::class.java).setAction(DnsShieldService.ACTION_STOP))
                    } else {
                        val i = VpnService.prepare(ctx)
                        if (i != null) permLauncher.launch(i) else begin()
                    }
                }
            ) { Text(if (running) "Turn off" else "Turn on") }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Upstream resolver", style = MaterialTheme.typography.titleMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(resolvers) { (name, ip) ->
                            FilterChip(selected = upstream == ip, enabled = !running, onClick = { upstream = ip }, label = { Text(name) })
                        }
                    }
                    Text(
                        "Blocks ${Blocklist.builtIn.size}+ ad, analytics and tracker domains system-wide. " +
                            "Turn off Private DNS in Android settings, and note that apps with built-in DNS-over-HTTPS bypass filtering. " +
                            "Only one VPN can run at a time, so this pauses the WireGuard tab.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Your block list", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(newDomain, { newDomain = it }, singleLine = true, label = { Text("domain.com") }, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = {
                            val d = newDomain.lowercase().trim().removePrefix("https://").removePrefix("http://").substringBefore("/")
                            if (d.contains('.')) {
                                Blocklist.setCustom(ctx, Blocklist.custom + d)
                                custom = Blocklist.custom.toList().sorted()
                                newDomain = ""
                            }
                        }) { Text("Block") }
                    }
                    if (custom.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(custom) { d ->
                                InputChip(selected = false, onClick = {}, label = { Text(d) },
                                    trailingIcon = {
                                        Text("✕", Modifier.clickable {
                                            Blocklist.setCustom(ctx, Blocklist.custom - d)
                                            custom = Blocklist.custom.toList().sorted()
                                        })
                                    })
                            }
                        }
                    }
                }
            }
        }
        if (top.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Top blocked", style = MaterialTheme.typography.titleMedium)
                        top.entries.sortedByDescending { it.value }.take(6).forEach { (d, c) ->
                            Row(Modifier.fillMaxWidth()) {
                                Text(d, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Text("×$c", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
        item { Text("Live DNS log", style = MaterialTheme.typography.titleMedium) }
        if (entries.isEmpty()) {
            item { Text(if (running) "Waiting for queries…" else "Turn the shield on to see every domain your phone looks up.", style = MaterialTheme.typography.bodySmall) }
        }
        items(entries.take(80)) { e ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(fmt.format(Date(e.time)), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(62.dp))
                Text(e.domain, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Text(
                    if (e.blocked) "BLOCKED·${e.category}" else "ok",
                    color = if (e.blocked) Color(0xFFE53935) else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (e.blocked) FontWeight.Bold else FontWeight.Normal,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Color(0xFFBFD0FF), fontSize = 12.sp)
    }
}
