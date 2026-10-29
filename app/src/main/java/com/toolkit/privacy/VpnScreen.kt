package com.toolkit.privacy

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun fmtB(b: Long): String = when {
    b >= 1_073_741_824 -> "%.2f GB".format(b / 1_073_741_824.0)
    b >= 1_048_576 -> "%.1f MB".format(b / 1_048_576.0)
    b >= 1024 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}

private fun hms(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
}

@Composable
fun StatusRing(status: String) {
    val connected = status == "Connected"
    val connecting = status == "Connecting"
    val t = rememberInfiniteTransition(label = "ring")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "a")
    val pulse by t.animateFloat(0.93f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "p")
    val color = when {
        connected -> Color(0xFF34D399)
        connecting -> Color(0xFFFBBF24)
        else -> Color(0xFF94A3B8)
    }
    Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 10.dp.toPx()
            val inset = Offset(stroke / 2, stroke / 2)
            val sz = Size(size.width - stroke, size.height - stroke)
            val k = if (connected) pulse else 1f
            drawCircle(color.copy(alpha = 0.18f), radius = (size.minDimension / 2 - stroke / 2) * k, style = Stroke(stroke))
            if (connecting) {
                drawArc(color = color, startAngle = angle, sweepAngle = 100f, useCenter = false,
                    topLeft = inset, size = sz, style = Stroke(stroke, cap = StrokeCap.Round))
            } else {
                drawArc(color = color, startAngle = 0f, sweepAngle = 360f, useCenter = false,
                    topLeft = inset, size = sz, style = Stroke(stroke))
            }
        }
        Text(if (connected) "🔒" else "🔓", fontSize = 40.sp)
    }
}

@Composable
fun VpnScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var names by remember { mutableStateOf(VpnManager.names(ctx)) }
    var selected by remember { mutableStateOf(VpnManager.selected(ctx)) }
    var status by remember { mutableStateOf(if (VpnManager.isUp(ctx)) "Connected" else "Disconnected") }
    var since by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var rx by remember { mutableLongStateOf(0L) }
    var tx by remember { mutableLongStateOf(0L) }
    var ip by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }

    LaunchedEffect(status) {
        while (status == "Connected") {
            val (r, t) = withContext(Dispatchers.IO) { VpnManager.stats(ctx) }
            rx = r; tx = t; now = System.currentTimeMillis()
            delay(1000)
        }
    }

    fun connect() {
        val name = selected ?: return
        scope.launch {
            status = "Connecting"
            msg = ""
            try {
                withContext(Dispatchers.IO) { VpnManager.connect(ctx, name) }
                since = System.currentTimeMillis()
                status = "Connected"
            } catch (e: Exception) {
                status = "Disconnected"
                msg = e.message ?: "Connection failed"
            }
        }
    }

    fun disconnect() {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { VpnManager.disconnect(ctx) }
                status = "Disconnected"
                ip = ""
                rx = 0; tx = 0
            } catch (e: Exception) {
                msg = e.message ?: "Disconnect failed"
            }
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) connect() else msg = "VPN permission denied"
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val text = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                val display = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (c.moveToFirst() && i >= 0) c.getString(i) else null
                } ?: "config"
                val saved = VpnManager.saveConfig(ctx, display.removeSuffix(".conf"), text)
                names = VpnManager.names(ctx)
                selected = saved
                msg = "Imported $saved"
            } catch (e: Exception) {
                msg = "Invalid config: ${e.message}"
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF0F766E), Color(0xFF134E4A), Color(0xFF0B1F2A))))
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusRing(status)
                Text(status, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (status == "Connected") hms(now - since) else (selected ?: "No config imported"),
                    color = Color(0xFFB6E3DC)
                )
                if (status == "Connected") {
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        Text("↓ ${fmtB(rx)}", color = Color.White)
                        Text("↑ ${fmtB(tx)}", color = Color.White)
                    }
                }
            }
        }

        if (status == "Connected") {
            Button(onClick = { disconnect() }, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Disconnect") }
        } else {
            Button(
                enabled = selected != null && status == "Disconnected",
                modifier = Modifier.fillMaxWidth().height(54.dp),
                onClick = {
                    val i = VpnService.prepare(ctx)
                    if (i != null) permLauncher.launch(i) else connect()
                }
            ) { Text(if (status == "Connecting") "Connecting…" else "Connect") }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Server profiles", style = MaterialTheme.typography.titleMedium)
                if (names.isEmpty()) Text("No profiles yet. Import a WireGuard .conf file.", style = MaterialTheme.typography.bodySmall)
                names.forEach { n ->
                    val ep = remember(n) { VpnManager.endpoint(ctx, n) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = n == selected,
                            enabled = status == "Disconnected",
                            onClick = { selected = n; VpnManager.select(ctx, n) }
                        )
                        Column(Modifier.weight(1f)) {
                            Text(n, fontWeight = FontWeight.Medium)
                            Text(ep, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(
                            enabled = status == "Disconnected",
                            onClick = {
                                VpnManager.delete(ctx, n)
                                names = VpnManager.names(ctx)
                                selected = VpnManager.selected(ctx)
                            }
                        ) { Text("Delete") }
                    }
                }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("＋ Import .conf") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tools", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        scope.launch { ip = "…"; ip = withContext(Dispatchers.IO) { VpnManager.publicIp() } }
                    }) { Text("Check public IP") }
                    OutlinedButton(onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text("Kill switch") }
                }
                if (ip.isNotEmpty()) Text("Public IP: $ip", fontWeight = FontWeight.Medium)
                Text(
                    "Kill switch: open VPN settings, tap the gear next to this app, enable Always-on VPN and Block connections without VPN.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.error)
    }
}
