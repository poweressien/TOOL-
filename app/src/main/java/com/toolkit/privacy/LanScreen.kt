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

/** Original quick scanner UI, kept as-is and available under "Classic quick scan". */
@Composable
fun ClassicLanScan() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var hosts by remember { mutableStateOf<List<Host>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var prog by remember { mutableFloatStateOf(0f) }
    var msg by remember { mutableStateOf("") }
    val net = remember { localNet(ctx) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (net != null) "Your Wi-Fi: ${net.ip}  ·  scanning ${net.base}.1–254" else "Connect to Wi-Fi to scan your network.",
            style = MaterialTheme.typography.bodySmall
        )
        Button(enabled = !scanning && net != null, onClick = {
            hosts = emptyList(); msg = ""; prog = 0f; scanning = true
            scope.launch {
                val err = lanScan(ctx, { prog = it }) { h -> hosts = (hosts + h).sortedBy { it.ip.substringAfterLast('.').toInt() } }
                if (err != null) msg = err
                scanning = false
            }
        }) { Text(if (scanning) "Scanning…" else "Classic scan") }
        if (scanning) LinearProgressIndicator(progress = { prog }, modifier = Modifier.fillMaxWidth())
        if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.error)
        hosts.forEach { h ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row {
                        Text(h.ip, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(if (h.me) "This phone" else if (h.gateway) "Router" else "${h.ms} ms", style = MaterialTheme.typography.bodySmall)
                    }
                    if (h.name != null) Text(h.name, style = MaterialTheme.typography.bodyMedium)
                    if (h.tags.isNotEmpty()) Text(h.tags.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    if (h.ports.isNotEmpty()) Text("Open: " + h.ports.joinToString(", ") { p -> PORT_NAMES[p]?.let { "$p/$it" } ?: "$p" }, style = MaterialTheme.typography.bodySmall)
                    h.warnings.forEach { Text("⚠ $it", color = Color(0xFFE53935), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

private fun lanReport(net: LocalInfo?, devs: List<LanDevice>): String = buildString {
    appendLine("EKPE - LAN report")
    if (net != null) appendLine("Network: ${net.ssid ?: "?"}  ${net.ip}/${net.prefix}  gateway ${net.gateway ?: "?"}  dns ${net.dns.joinToString()}")
    appendLine("Devices: ${devs.size}")
    devs.forEach { d ->
        appendLine()
        appendLine("${d.ip}  ${d.title}  [${d.riskLabel} ${d.risk}]")
        d.mac?.let { appendLine("  MAC $it ${d.vendor ?: ""}") }
        if (d.tags.isNotEmpty()) appendLine("  ${d.tags.joinToString(" · ")}")
        d.services.forEach { appendLine("  mDNS: $it") }
        d.upnp?.let { appendLine("  UPnP: $it") }
        d.ports.forEach { appendLine("  ${it.port}/tcp ${it.service}" + (it.banner?.let { b -> "  \"$b\"" } ?: "")) }
        d.warnings.forEach { appendLine("  ! $it") }
    }
}

private fun lanCsv(devs: List<LanDevice>): String = buildString {
    appendLine("ip,name,mac,vendor,risk,ports,tags")
    fun q(s: String?) = "\"" + (s ?: "").replace("\"", "\"\"") + "\""
    devs.forEach { appendLine(listOf(q(it.ip), q(it.title), q(it.mac), q(it.vendor), "${it.risk}", q(it.ports.joinToString(" ") { p -> "${p.port}" }), q(it.tags.joinToString(" | "))).joinToString(",")) }
}

@Composable
private fun DeviceCard(d: LanDevice, expanded: Boolean, onToggle: () -> Unit, onUpdate: (LanDevice) -> Unit, net: LocalInfo?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var deep by remember { mutableStateOf("") }
    var scanningPorts by remember { mutableStateOf(false) }
    var nick by remember(d.ip) { mutableStateOf(d.nickname ?: "") }
    val riskColor = when { d.risk >= 60 -> Bad; d.risk >= 30 -> Warn; else -> Good }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(d.title + if (d.isNew) "   🆕" else "", fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(d.ip + (d.mac?.let { "  ·  $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                }
                Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                    Text(if (d.me) "This phone" else if (d.gateway) "Router" else if (d.pingMs >= 0) "${d.pingMs} ms" else "", style = MaterialTheme.typography.bodySmall)
                    Text(d.riskLabel, color = riskColor, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (d.tags.isNotEmpty()) Text(d.tags.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            if (d.ports.isNotEmpty()) Text("Open: " + d.ports.joinToString(", ") { "${it.port}/${it.service}" }, style = MaterialTheme.typography.bodySmall)
            d.warnings.take(if (expanded) 20 else 2).forEach { Text("⚠ $it", color = Bad, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = onToggle, contentPadding = PaddingValues(0.dp)) { Text(if (expanded) "Hide details" else "Details & actions") }
            if (expanded) {
                HorizontalDivider()
                d.vendor?.let { KV("Maker", it) }
                if (d.randomMac) KV("MAC", "Private/randomised address (phones hide their real MAC)")
                d.hostname?.let { KV("Hostname", it) }
                d.netbios?.let { KV("NetBIOS name", it) }
                d.upnp?.let { KV("UPnP / DLNA", it) }
                d.osGuess?.let { KV("Likely OS", it) }
                d.services.forEach { KV("Bonjour", it) }
                d.httpTitle?.let { KV("Web page title", it) }
                d.httpServer?.let { KV("Web server", it) }
                d.tls?.let { KV("TLS certificate", it) }
                if (d.ports.isNotEmpty()) {
                    Text("Ports & accessibility", fontWeight = FontWeight.Medium)
                    d.ports.forEach { p ->
                        val c = when (p.risk) { 3 -> Bad; 2 -> Warn; else -> Good }
                        Column {
                            Text("${p.port}/tcp  ${p.service}", color = c, fontWeight = FontWeight.Medium)
                            Text(p.note, style = MaterialTheme.typography.bodySmall)
                            p.banner?.let { Text("banner: $it", style = MonoStyle) }
                        }
                    }
                }
                if (d.advice.isNotEmpty()) {
                    Text("What to do", fontWeight = FontWeight.Medium)
                    d.advice.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
                OutlinedTextField(nick, { nick = it }, Modifier.fillMaxWidth(), label = { Text("Nickname (marks it as known)") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { KnownDevices.remember(ctx, d, nick); onUpdate(d.copy(isNew = false, nickname = nick.ifBlank { null })) }) { Text(if (d.isNew) "Trust device" else "Save") }
                    OutlinedButton(onClick = { copyText(ctx, lanReport(net, listOf(d))) }) { Text("Copy") }
                    OutlinedButton(onClick = { copyText(ctx, d.ip) }) { Text("Copy IP") }
                    if (d.ports.any { it.port == 22 }) OutlinedButton(onClick = { copyText(ctx, "ssh ${d.ip}") }) { Text("Copy SSH") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val web = d.ports.firstOrNull { it.port in listOf(80, 8080, 8000, 8008, 8081, 81, 8088) }
                    val sec = d.ports.firstOrNull { it.port in listOf(443, 8443, 5001) }
                    if (web != null) OutlinedButton(onClick = { openUrl(ctx, "http://${d.ip}:${web.port}") }) { Text("Open web UI") }
                    if (sec != null) OutlinedButton(onClick = { openUrl(ctx, "https://${d.ip}:${sec.port}") }) { Text(if (web != null) "Open HTTPS" else "Open web UI") }
                    if (d.mac != null && !d.randomMac && net != null) OutlinedButton(onClick = {
                        scope.launch(Dispatchers.IO) { NetIntel.wakeOnLan(d.mac, net.base + ".255") }
                    }) { Text("Wake (WoL)") }
                }
                OutlinedButton(enabled = !scanningPorts, onClick = {
                    scanningPorts = true; deep = "Scanning ports 1-1024 and common services…"
                    scope.launch {
                        val open = withContext(Dispatchers.IO) {
                            val gate = Semaphore(80)
                            coroutineScope { PortDb.deep().map { p -> async { gate.withPermit { if (NetIntel.probe(d.ip, p, 300) == 1) p else null } } }.awaitAll().filterNotNull() }
                        }
                        val infos = open.map { PortDb.info(it) }
                        val merged = (d.ports + infos).distinctBy { it.port }.sortedBy { it.port }
                        onUpdate(NetIntel.analyze(d.copy(ports = merged)))
                        deep = "Deep scan done: ${open.size} open port(s) found."
                        scanningPorts = false
                    }
                }) { Text(if (scanningPorts) "Scanning…" else "Deep port scan this device") }
                if (deep.isNotEmpty()) Text(deep, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun LanScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<LanDevice>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var prog by remember { mutableFloatStateOf(0f) }
    var stage by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var depth by remember { mutableStateOf(ScanDepth.STANDARD) }
    var filter by remember { mutableStateOf("All") }
    var open by remember { mutableStateOf<String?>(null) }
    var net by remember { mutableStateOf(NetIntel.localInfo(ctx)) }
    var showClassic by remember { mutableStateOf(false) }
    var customSpec by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun order(l: List<LanDevice>) = l.sortedBy { it.ip.substringAfterLast('.').toIntOrNull() ?: 999 }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 14.dp)
    ) {
        item {
            Text("LAN scanner", style = MaterialTheme.typography.headlineSmall)
            Text("Discovers every device on your Wi-Fi using ping sweep, ARP, Bonjour/mDNS, UPnP/SSDP and NetBIOS, then inspects open ports, banners, web pages and certificates. Only scan networks you own or have permission to test.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Section("Your network") {
                val n = net
                if (n == null) Text("Connect to Wi-Fi to scan your network.")
                else {
                    KV("Wi-Fi name", n.ssid ?: "hidden / needs Location permission")
                    KV("Your IP", "${n.ip}/${n.prefix}")
                    KV("Router", n.gateway ?: "?")
                    KV("DNS", n.dns.joinToString().ifEmpty { "?" })
                    KV("Scan range", "${n.hostIps().first()} – ${n.hostIps().last()}")
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChipRow(ScanDepth.values().map { it.label }, depth.label) { l -> depth = ScanDepth.values().first { it.label == l } }
                Text(when (depth) {
                    ScanDepth.QUICK -> "Quick: 18 common ports. Takes seconds."
                    ScanDepth.STANDARD -> "Standard: ~90 well-known service ports, banners, web titles, TLS."
                    ScanDepth.DEEP -> "Deep: ports 1-1024 plus extras on every device. Slower."
                    ScanDepth.FULL -> "Full: every port 1-65535 on every device. Thorough but slow (minutes per device); you can stop any time."
                }, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(customSpec, { customSpec = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Custom ports (optional): 22,80,8000-8100") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !scanning && net != null, onClick = {
                        devices = emptyList(); msg = ""; prog = 0f; scanning = true; net = NetIntel.localInfo(ctx)
                        val custom = PortDb.parse(customSpec).takeIf { it.isNotEmpty() }
                        if (customSpec.isNotBlank() && custom == null) msg = "No valid ports in the custom list, using the ${depth.label} scan."
                        job = scope.launch {
                            try {
                                val known = withContext(Dispatchers.IO) { KnownDevices.load(ctx) }
                                val err = NetIntel.scan(ctx, depth, known, custom, { s, p -> stage = s; prog = p }) { d ->
                                    devices = order(devices.filterNot { it.ip == d.ip } + d)
                                }
                                if (err != null) msg = err
                            } finally { scanning = false; stage = "" }
                        }
                    }) { Text(if (scanning) "Scanning…" else "Scan network") }
                    if (scanning) OutlinedButton(onClick = { job?.cancel(); msg = "Scan stopped. Devices found so far are kept." }) { Text("Stop") }
                    OutlinedButton(enabled = devices.isNotEmpty(), onClick = {
                        devices.forEach { KnownDevices.remember(ctx, it) }
                        devices = devices.map { it.copy(isNew = false) }
                    }) { Text("Trust all") }
                }
                if (scanning) {
                    LinearProgressIndicator(progress = { prog }, modifier = Modifier.fillMaxWidth())
                    Text(stage, style = MaterialTheme.typography.bodySmall)
                }
                if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.error)
            }
        }
        if (devices.isNotEmpty()) {
            item {
                val risky = devices.count { it.risk >= 30 }
                val fresh = devices.count { it.isNew }
                Section("Summary") {
                    Text("${devices.size} devices · $risky risky · $fresh new/unknown", fontWeight = FontWeight.Medium)
                    if (fresh > 0) Text("🆕 devices not seen before. If you do not recognise one, change your Wi-Fi password.", color = Warn, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { shareText(ctx, lanReport(net, devices)) }) { Text("Share report") }
                        OutlinedButton(onClick = { shareText(ctx, lanCsv(devices)) }) { Text("Share CSV") }
                    }
                    ChipRow(listOf("All", "New", "Risky", "Web UI", "Cameras", "Apple", "Printers"), filter) { filter = it }
                }
            }
        }
        val shown = devices.filter {
            when (filter) {
                "New" -> it.isNew
                "Risky" -> it.risk >= 30
                "Web UI" -> it.ports.any { p -> p.port in listOf(80, 443, 8080, 8443) }
                "Cameras" -> it.tags.any { t -> t.startsWith("Camera") }
                "Apple" -> it.tags.any { t -> t.startsWith("Apple") }
                "Printers" -> it.tags.any { t -> t == "Printer" }
                else -> true
            }
        }
        items(shown, key = { it.ip }) { d ->
            DeviceCard(d, open == d.ip, { open = if (open == d.ip) null else d.ip }, { upd -> devices = order(devices.filterNot { it.ip == upd.ip } + upd) }, net)
        }
        item {
            TextButton(onClick = { showClassic = !showClassic }) { Text(if (showClassic) "Hide classic quick scan" else "Classic quick scan") }
            if (showClassic) ClassicLanScan()
        }
    }
}
