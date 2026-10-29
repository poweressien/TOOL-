package com.toolkit.privacy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder

private val PORT_LABELS = mapOf(
    21 to "FTP", 22 to "SSH", 23 to "Telnet", 25 to "SMTP", 53 to "DNS", 80 to "HTTP", 110 to "POP3", 143 to "IMAP",
    443 to "HTTPS", 445 to "SMB", 3306 to "MySQL", 3389 to "RDP", 5432 to "PostgreSQL", 8080 to "HTTP-alt"
)

private fun parsePorts(s: String): List<Int> {
    val out = sortedSetOf<Int>()
    s.split(',', ' ').filter { it.isNotBlank() }.forEach { t ->
        if ('-' in t) {
            val (a, b) = t.split('-').map { it.trim().toInt() }
            for (p in a..b) out += p
        } else out += t.trim().toInt()
    }
    return out.filter { it in 1..65535 }.take(300)
}

private fun doPing(host: String, ref: Array<Process?>, out: (String) -> Unit) {
    try {
        val p = ProcessBuilder("ping", "-c", "6", host).redirectErrorStream(true).start()
        ref[0] = p
        p.inputStream.bufferedReader().useLines { it.forEach(out) }
        p.waitFor()
    } catch (e: Exception) {
        out("ping unavailable, measuring TCP latency to $host:443 instead")
        repeat(6) {
            val t0 = System.nanoTime()
            val ok = try { Socket().use { it.connect(InetSocketAddress(host, 443), 2000) }; true } catch (e2: Exception) { false }
            out(if (ok) "connected in ${(System.nanoTime() - t0) / 1_000_000} ms" else "timeout")
            Thread.sleep(500)
        }
    }
}

private fun doTrace(host: String, ref: Array<Process?>, out: (String) -> Unit) {
    out("Tracing route to $host (max 30 hops)")
    val ipRe = Regex("(?:[Ff]rom|bytes from) (\\d+\\.\\d+\\.\\d+\\.\\d+)")
    for (ttl in 1..30) {
        val t0 = System.nanoTime()
        val sb = StringBuilder()
        try {
            val p = ProcessBuilder("ping", "-c", "1", "-t", "$ttl", "-W", "2", host).redirectErrorStream(true).start()
            ref[0] = p
            p.inputStream.bufferedReader().useLines { it.forEach { l -> sb.appendLine(l) } }
            p.waitFor()
        } catch (e: Exception) {
            out("traceroute needs the ping binary, which isn't available on this device")
            return
        }
        val txt = sb.toString()
        if (txt.contains("unknown host", true)) { out("Unknown host"); return }
        val ip = ipRe.find(txt)?.groupValues?.get(1)
        val ms = Regex("time=([\\d.]+) ms").find(txt)?.groupValues?.get(1)?.toDoubleOrNull()?.toLong() ?: ((System.nanoTime() - t0) / 1_000_000)
        out(if (ip == null) "%2d  * * *".format(ttl) else "%2d  %-16s %d ms".format(ttl, ip, ms))
        if (txt.contains("bytes from")) { out("Reached destination."); return }
    }
}

private fun doDns(host: String, out: (String) -> Unit) {
    try {
        out("System resolver: " + InetAddress.getAllByName(host).joinToString { it.hostAddress ?: "" })
    } catch (e: Exception) { out("System resolver: ${e.message}") }
    for (t in listOf("A", "AAAA", "MX", "NS", "TXT", "CNAME", "SOA")) {
        try {
            val c = URL("https://cloudflare-dns.com/dns-query?name=${URLEncoder.encode(host, "UTF-8")}&type=$t").openConnection() as HttpURLConnection
            c.setRequestProperty("accept", "application/dns-json")
            c.connectTimeout = 6000; c.readTimeout = 6000
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val ans = j.optJSONArray("Answer")
            if (ans == null) out("$t: (no records)") else for (i in 0 until ans.length()) {
                val a = ans.getJSONObject(i)
                out("$t  ${a.optString("data")}   ttl ${a.optInt("TTL")}")
            }
        } catch (e: Exception) { out("$t: ${e.message}") }
    }
}

private fun whoisQuery(server: String, q: String): String =
    Socket(server, 43).use { s ->
        s.soTimeout = 8000
        s.getOutputStream().write("$q\r\n".toByteArray())
        s.getInputStream().bufferedReader().readText()
    }

private fun doWhois(host: String, out: (String) -> Unit) {
    var res = whoisQuery("whois.iana.org", host)
    val refer = Regex("(?im)^refer:\\s*(\\S+)").find(res)?.groupValues?.get(1)
    if (refer != null) {
        out("→ $refer")
        res = whoisQuery(refer, host)
        val reg = Regex("(?im)^Registrar WHOIS Server:\\s*(\\S+)").find(res)?.groupValues?.get(1)
        if (reg != null && reg != refer) { out("→ $reg"); try { res = whoisQuery(reg, host) } catch (e: Exception) {} }
    }
    res.lines().filter { it.isNotBlank() && !it.startsWith("%") && !it.startsWith(">>>") }.take(120).forEach(out)
}

private suspend fun doPorts(host: String, spec: String, out: (String) -> Unit) = coroutineScope {
    val ports = parsePorts(spec)
    out("Scanning ${ports.size} port(s) on $host…")
    val gate = Semaphore(40)
    val res = ports.map { p ->
        async(Dispatchers.IO) {
            gate.withPermit {
                val ok = try { Socket().use { it.connect(InetSocketAddress(host, p), 1200) }; true } catch (e: Exception) { false }
                if (ok) out("  $p/tcp  OPEN  ${PORT_LABELS[p] ?: ""}")
                ok
            }
        }
    }.awaitAll()
    out("Done: ${res.count { it }} open of ${ports.size}")
}

@Composable
fun ToolboxScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf("Ping") }
    var host by remember { mutableStateOf("1.1.1.1") }
    var ports by remember { mutableStateOf("22,80,443,8080") }
    val lines = remember { mutableStateListOf<String>() }
    var job by remember { mutableStateOf<Job?>(null) }
    var running by remember { mutableStateOf(false) }
    val procRef = remember { arrayOfNulls<Process>(1) }

    fun emit(s: String) { lines.add(s); if (lines.size > 500) lines.removeAt(0) }

    fun start() {
        job?.cancel(); lines.clear()
        val h = host.trim().removePrefix("https://").removePrefix("http://").substringBefore('/')
        if (h.isEmpty()) return
        running = true
        job = scope.launch(Dispatchers.IO) {
            try {
                when (mode) {
                    "Ping" -> doPing(h, procRef, ::emit)
                    "Trace" -> doTrace(h, procRef, ::emit)
                    "DNS" -> doDns(h, ::emit)
                    "Whois" -> doWhois(h, ::emit)
                    else -> doPorts(h, ports, ::emit)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit("Error: ${e.message}")
            } finally {
                running = false
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Network toolbox", style = MaterialTheme.typography.headlineSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("Ping", "Trace", "DNS", "Whois", "Ports")) { m ->
                FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m) })
            }
        }
        OutlinedTextField(host, { host = it }, label = { Text("Host / domain / IP") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (mode == "Ports") {
            OutlinedTextField(ports, { ports = it }, label = { Text("Ports (e.g. 22,80,443 or 1-100)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !running, onClick = { start() }) { Text("Run") }
            OutlinedButton(enabled = running, onClick = { procRef[0]?.destroy(); job?.cancel(); running = false }) { Text("Stop") }
            OutlinedButton(enabled = lines.isNotEmpty(), onClick = { copyText(ctx, lines.joinToString("\n")) }) { Text("Copy") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(lines.toList()) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
        }
    }
}

