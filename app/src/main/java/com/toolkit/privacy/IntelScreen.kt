package com.toolkit.privacy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun IntelScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    val results = remember { mutableStateMapOf<String, Lines>() }
    val loading = remember { mutableStateMapOf<String, Boolean>() }
    var grade by remember { mutableStateOf("") }
    var gradeScore by remember { mutableIntStateOf(0) }

    var runId by remember { mutableIntStateOf(0) }

    fun job(key: String, f: () -> Lines) {
        val my = runId
        loading[key] = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { try { f() } catch (e: Exception) { listOf("Error" to (e.message ?: "failed")) } }
            if (my == runId) { results[key] = r; loading[key] = false }   // ignore answers from an older investigation
        }
    }

    fun run() {
        val host = Intel.hostOf(input)
        if (host.isBlank()) return
        runId++; target = host; results.clear(); loading.clear(); grade = ""
        val ip = Intel.isIp(host)
        job("1 IP & owner") { Intel.ipGeo(if (ip) host else (try { java.net.InetAddress.getByName(host).hostAddress ?: host } catch (e: Exception) { host })) }
        job("2 Resolve") { Intel.resolve(host) }
        if (!ip) {
            job("3 DNS records") { Intel.dnsRecords(host) }
            job("4 Registration (WHOIS/RDAP)") { Intel.whois(Intel.hostOf(SiteRules.registrable(host))) }
            job("5 Email spoofing protection") { Intel.mailAuth(SiteRules.registrable(host)) }
            job("8 Web history") { Intel.archiveAge(host) }
            job("A Subdomains (certificate logs)") { Intel.subdomains(SiteRules.registrable(host)) }
        }
        job("6 HTTPS certificate") { Intel.tls(host) }
        val my = runId
        scope.launch {
            loading["7 Security headers"] = true
            val r = withContext(Dispatchers.IO) { try { Intel.securityHeaders(host) } catch (e: Exception) { Intel.HeaderReport(listOf("Error" to (e.message ?: "")), "-", 0) } }
            if (my == runId) { results["7 Security headers"] = r.lines; grade = r.grade; gradeScore = r.score; loading["7 Security headers"] = false }
        }
        job("9 Redirect chain") { Intel.redirectChain(host) }
        if (!ip) job("B Policy files") { Intel.wellKnown(host) }
    }

    Page("Intel", "Pull real data about any website, domain or IP: owner, location, DNS, certificate, age, security headers, subdomains. Calls public sources directly from your phone.") {
        Sec("Investigate") {
            OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Domain, URL or IP") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { run() }, enabled = input.isNotBlank()) { Text("Investigate") }
                OutlinedButton(onClick = { input = clipboardText(ctx).trim(); if (input.isNotBlank()) run() }) { Text("Paste & go") }
            }
        }
        if (target.isNotEmpty()) {
            Text("Results for $target", fontWeight = FontWeight.Bold)
            if (grade.isNotEmpty()) Text("Security header grade: $grade ($gradeScore/100)", color = if (gradeScore >= 65) Color(0xFF34D399) else if (gradeScore >= 40) Color(0xFFFBBF24) else Color(0xFFEF4444), fontWeight = FontWeight.Bold)
        }
        val keys = (results.keys + loading.keys).distinct().sorted()
        keys.forEach { k ->
            Sec(k.drop(2)) {
                if (loading[k] == true) LinearProgressIndicator(Modifier.fillMaxWidth())
                results[k]?.forEach { (a, b) -> Kv(a, b, true) }
            }
        }
        if (results.isNotEmpty()) OutlinedButton(onClick = {
            shareText(ctx, buildString {
                appendLine("Intel report: $target")
                keys.forEach { k -> appendLine(); appendLine(k.drop(2)); results[k]?.forEach { (a, b) -> appendLine("  $a: $b") } }
            })
        }) { Text("Share report") }
    }
}
