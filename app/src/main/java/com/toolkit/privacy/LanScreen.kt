package com.toolkit.privacy

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

data class Host(
    val ip: String, val ms: Long, val name: String?, val ports: List<Int>,
    val tags: List<String>, val warnings: List<String>, val me: Boolean, val gateway: Boolean
)

private val ALIVE_PORTS = listOf(80, 443, 22, 445, 62078, 8080, 53, 139, 5555, 554, 9100, 23)
private val SCAN_PORTS = listOf(21, 22, 23, 25, 53, 80, 110, 139, 143, 443, 445, 554, 631, 1883, 3389, 5555, 5900, 8000, 8080, 8443, 9100, 62078)
private val PORT_NAMES = mapOf(
    21 to "FTP", 22 to "SSH", 23 to "Telnet", 53 to "DNS", 80 to "HTTP", 139 to "NetBIOS", 443 to "HTTPS",
    445 to "SMB", 554 to "RTSP", 631 to "IPP", 1883 to "MQTT", 3389 to "RDP", 5555 to "ADB", 5900 to "VNC",
    8080 to "HTTP-alt", 8443 to "HTTPS-alt", 9100 to "Printer", 62078 to "iOS-sync"
)

/** 1 = open, 0 = refused (host is up), -1 = no answer */
private fun probe(ip: String, port: Int, timeout: Int): Int = try {
    Socket().use { it.connect(InetSocketAddress(ip, port), timeout) }
    1
} catch (e: ConnectException) {
    if (e.message?.contains("ECONNREFUSED") == true) 0 else -1
} catch (e: Exception) {
    -1
}

private class LocalNet(val ip: String, val base: String, val gateway: String?)

private fun localNet(ctx: Context): LocalNet? {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    @Suppress("DEPRECATION")
    val wifi = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        ?: cm.activeNetwork ?: return null
    val lp = cm.getLinkProperties(wifi) ?: return null
    val addr = lp.linkAddresses.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress } ?: return null
    val ip = addr.address.hostAddress ?: return null
    val base = ip.substringBeforeLast('.')
    val gw = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
    return LocalNet(ip, base, gw)
}

suspend fun lanScan(
    ctx: Context,
    onProgress: (Float) -> Unit,
    onHost: (Host) -> Unit
): String? = withContext(Dispatchers.IO) {
    val net = localNet(ctx) ?: return@withContext "Not connected to Wi-Fi"
    val gate = Semaphore(60)
    val done = AtomicInteger(0)

    // Stage 1: find live hosts (open OR refused = alive)
    val alive = coroutineScope {
        (1..254).map { n ->
            async {
                val ip = "${net.base}.$n"
                var found: Pair<String, Long>? = null
                gate.withPermit {
                    for (port in ALIVE_PORTS) {
                        val t0 = System.nanoTime()
                        if (probe(ip, port, 350) >= 0) {
                            found = ip to (System.nanoTime() - t0) / 1_000_000
                            break
                        }
                    }
                }
                val d = done.incrementAndGet()
                withContext(Dispatchers.Main) { onProgress(d / 254f * 0.5f) }
                found
            }
        }.awaitAll().filterNotNull()
    }
    if (alive.isEmpty()) return@withContext "No devices answered"

    // Stage 2: port scan + hostname for each live host
    val doneHosts = AtomicInteger(0)
    coroutineScope {
        alive.map { (ip, ms) ->
            async {
                val open = coroutineScope {
                    SCAN_PORTS.map { p -> async { gate.withPermit { if (probe(ip, p, 400) == 1) p else null } } }.awaitAll().filterNotNull()
                }
                val name = withTimeoutOrNull(1500) { InetAddress.getByName(ip).canonicalHostName }?.takeIf { it != ip }
                val tags = mutableListOf<String>()
                val warn = mutableListOf<String>()
                if (ip == net.gateway) tags += "Router / gateway"
                if (62078 in open) tags += "iPhone / iPad"
                if (554 in open) tags += "Camera / stream"
                if (9100 in open || 631 in open) tags += "Printer"
                if (445 in open || 139 in open) tags += "Windows / file share"
                if (1883 in open) tags += "IoT (MQTT)"
                if (22 in open) tags += "SSH server"
                if (open.any { it in listOf(80, 443, 8080, 8443, 8000) }) tags += "Web interface"
                if (23 in open) warn += "Telnet open (unencrypted)"
                if (5555 in open) warn += "ADB debugging open"
                if (3389 in open) warn += "Remote Desktop open"
                if (5900 in open) warn += "VNC open"
                if (21 in open) warn += "FTP open (unencrypted)"
                val host = Host(ip, ms, name, open, tags, warn, ip == net.ip, ip == net.gateway)
                val d = doneHosts.incrementAndGet()
                withContext(Dispatchers.Main) {
                    onProgress(0.5f + d / alive.size.toFloat() * 0.5f)
                    onHost(host)
                }
            }
        }.awaitAll()
    }
    null
}

@Composable
fun LanScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var hosts by remember { mutableStateOf<List<Host>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var prog by remember { mutableFloatStateOf(0f) }
    var msg by remember { mutableStateOf("") }
    val net = remember { localNet(ctx) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 14.dp)
    ) {
        item {
            Text("LAN scanner", style = MaterialTheme.typography.headlineSmall)
            Text(
                if (net != null) "Your Wi-Fi: ${net.ip}  ·  scanning ${net.base}.1–254" else "Connect to Wi-Fi to scan your network.",
                style = MaterialTheme.typography.bodySmall
            )
            Text("Only scan networks you own or have permission to test.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Button(enabled = !scanning && net != null, onClick = {
                hosts = emptyList(); msg = ""; prog = 0f; scanning = true
                scope.launch {
                    val err = lanScan(ctx, { prog = it }) { h -> hosts = (hosts + h).sortedBy { it.ip.substringAfterLast('.').toInt() } }
                    if (err != null) msg = err
                    scanning = false
                }
            }) { Text(if (scanning) "Scanning…" else "Scan network") }
            if (scanning) LinearProgressIndicator(progress = { prog }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.error)
        }
        if (hosts.isNotEmpty()) {
            item {
                val risky = hosts.count { it.warnings.isNotEmpty() }
                Text("${hosts.size} devices found · $risky with risky open services", fontWeight = FontWeight.Medium)
            }
        }
        items(hosts, key = { it.ip }) { h ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row {
                        Text(h.ip, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(
                            when {
                                h.me -> "This phone"
                                h.gateway -> "Router"
                                else -> "${h.ms} ms"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (h.name != null) Text(h.name, style = MaterialTheme.typography.bodyMedium)
                    if (h.tags.isNotEmpty()) Text(h.tags.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    if (h.ports.isNotEmpty()) Text(
                        "Open: " + h.ports.joinToString(", ") { p -> PORT_NAMES[p]?.let { "$p/$it" } ?: "$p" },
                        style = MaterialTheme.typography.bodySmall
                    )
                    h.warnings.forEach { Text("⚠ $it", color = Color(0xFFE53935), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
