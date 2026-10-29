package com.toolkit.privacy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LookupScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf("Phone") }
    var input by remember { mutableStateOf("") }
    var cc by remember { mutableStateOf("234") }
    var phone by remember { mutableStateOf<PhoneInfo?>(null) }
    var email by remember { mutableStateOf<EmailReport?>(null) }
    var busy by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebReport?>(null) }
    var webBusy by remember { mutableStateOf(false) }
    var domain by remember { mutableStateOf<List<String>>(emptyList()) }
    var profile by remember { mutableStateOf<List<String>>(emptyList()) }

    fun deep() {
        webBusy = true
        scope.launch {
            val p = phone; val e = email
            if (p != null && p.e164.isNotEmpty()) web = withContext(Dispatchers.IO) { WebIntel.phoneFootprint(p) }
            else if (e != null && e.info.valid) {
                val addr = e.info.local + "@" + e.info.domain
                web = withContext(Dispatchers.IO) { WebIntel.emailFootprint(addr) }
                profile = withContext(Dispatchers.IO) { WebIntel.gravatar(e.info.md5) }
                if ("custom" in e.info.kind) domain = withContext(Dispatchers.IO) { WebIntel.domainFacts(WebIntel.domainIntel(e.info.domain)) }
            }
            webBusy = false
        }
    }

    fun scan() {
        if (input.isBlank()) return
        phone = null; email = null; web = null; domain = emptyList(); profile = emptyList()
        if (mode == "Phone") phone = LookupLogic.parsePhone(input, cc.filter { it.isDigit() }.ifEmpty { "234" })
        else {
            busy = true
            scope.launch {
                email = withContext(Dispatchers.IO) { LookupNet.analyzeEmail(input) }
                busy = false
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
        item { Text("Smart Scan", style = MaterialTheme.typography.headlineSmall) }
        item { Text("Who is behind this number or email? Checks the format, where it's from, scam signals, then gives one-tap searches across the web.", style = MaterialTheme.typography.bodySmall) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Phone", "Email").forEach { m ->
                    FilterChip(selected = mode == m, onClick = { mode = m; phone = null; email = null; web = null; domain = emptyList(); profile = emptyList() }, label = { Text(m) })
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(if (mode == "Phone") "Phone number (0803..., +44...)" else "Email address") },
                        keyboardOptions = KeyboardOptions(keyboardType = if (mode == "Phone") KeyboardType.Phone else KeyboardType.Email))
                    if (mode == "Phone") OutlinedTextField(cc, { cc = it.filter { c -> c.isDigit() }.take(3) }, Modifier.width(190.dp), singleLine = true,
                        label = { Text("Default country code") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { scan() }, enabled = !busy && input.isNotBlank()) { Text(if (busy) "Scanning..." else "Scan") }
                        OutlinedButton(onClick = { input = clipboardText(ctx).trim(); scan() }) { Text("Paste & scan") }
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        phone?.let { p ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (p.valid) "Valid format" else "Doesn't look like a valid number", fontWeight = FontWeight.Bold,
                            color = if (p.valid) androidx.compose.ui.graphics.Color(0xFF34D399) else androidx.compose.ui.graphics.Color(0xFFEF4444))
                        if (p.e164.isNotEmpty()) {
                            Text("International: ${p.e164}")
                            if (p.country.isNotEmpty()) Text("Country: ${p.country} (+${p.countryCode})")
                            p.carrier?.let { Text("Network: $it") }
                        }
                        p.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        if (p.valid) Text("Anyone can call from a spoofed number, so a clean result isn't proof they're genuine. Never share OTPs, PINs or BVN with a caller.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { DeepButton(webBusy, web != null) { deep() } }
            web?.let { w -> item { WebCard(w, "number") } }
            item { LinkList(LookupLogic.phoneLinks(p)) }
        }
        email?.let { r ->
            item {
                if (!r.info.valid) Card(Modifier.fillMaxWidth()) { Text("That isn't a valid email address.", Modifier.padding(14.dp)) }
                else VerdictCard(r.info.local + "@" + r.info.domain,
                    when { r.score >= 75 -> "Looks genuine"; r.score >= 45 -> "Unverified"; else -> "Risky" }, r.score,
                    (r.info.notes + r.live).filter { it.delta != 0 || it.text.startsWith("Free mail") || it.text.startsWith("Uses its own") }, r.facts)
            }
            if (r.info.valid) item { DeepButton(webBusy, web != null) { deep() } }
            if (profile.isNotEmpty() || domain.isNotEmpty()) item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Profile and domain details", fontWeight = FontWeight.Bold)
                        (profile + domain).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            web?.let { w -> item { WebCard(w, "address") } }
            item { LinkList(LookupLogic.emailLinks(r.info)) }
        }
    }
}

@Composable
private fun DeepButton(busy: Boolean, done: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Go online for more", fontWeight = FontWeight.Bold)
            Text("Searches the web for this and reads the top results: where it shows up, whether anyone reports it as a scam, plus profile and server details.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = onClick, enabled = !busy) { Text(if (busy) "Searching the web..." else if (done) "Search again" else "Deep scan online") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun WebCard(w: WebReport, what: String) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Web footprint", fontWeight = FontWeight.Bold)
            if (!w.reachable) Text("Couldn't reach the search engine. Check your connection and try again.", style = MaterialTheme.typography.bodySmall)
            else {
                w.scamNotes.forEach { FindingRow(it) }
                w.facts.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                if (w.hits.isEmpty()) Text("No pages mention this $what.", style = MaterialTheme.typography.bodySmall)
                w.hits.take(8).forEach { h ->
                    TextButton(onClick = { openUrl(ctx, h.url) }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(h.title.ifEmpty { h.host }, maxLines = 2)
                            Text(h.host + (if (h.snippet.isNotEmpty()) " · " + h.snippet.take(110) else ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}
