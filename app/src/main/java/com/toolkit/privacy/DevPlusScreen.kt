package com.toolkit.privacy

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** More developer tools, shown under the "More" chip in the Dev tab. */
@Composable
fun DevPlusPanel() {
    val ctx = LocalContext.current
    var tool by remember { mutableStateOf(DevTools.tools.first().name) }
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    var out by remember { mutableStateOf("") }
    val t = DevTools.tools.first { it.name == tool }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipRow(DevTools.tools.map { it.name }, tool) { tool = it; out = "" }
        Hint(t.hint)
        OutlinedTextField(a, { a = it }, Modifier.fillMaxWidth().heightIn(min = 100.dp), label = { Text(t.labelA) }, textStyle = MonoStyle)
        if (t.labelB != null) OutlinedTextField(b, { b = it }, Modifier.fillMaxWidth().heightIn(min = 56.dp), label = { Text(t.labelB) }, textStyle = MonoStyle)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { out = DevTools.run(tool, a, b) }) { Text("Run") }
            OutlinedButton(onClick = { a = clipboardText(ctx) }) { Text("Paste") }
            OutlinedButton(enabled = out.isNotEmpty(), onClick = { copyText(ctx, out) }) { Text("Copy") }
            OutlinedButton(enabled = out.isNotEmpty(), onClick = { a = out; out = "" }) { Text("Use as input") }
        }
        if (out.isNotEmpty()) OutlinedTextField(out, {}, Modifier.fillMaxWidth().heightIn(min = 120.dp), readOnly = true, label = { Text("Result") }, textStyle = MonoStyle)
    }
}

/** Secrets / personal-data scanner and redactor. */
@Composable
fun SensitiveScreen(initial: String? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(initial ?: "") }
    var report by remember { mutableStateOf<SensitiveScan.Report?>(null) }
    var redacted by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("full") }
    var note by remember { mutableStateOf("") }

    fun scan() { report = SensitiveScan.scan(text); redacted = ""; note = "" }
    LaunchedEffect(initial) { if (!initial.isNullOrBlank()) { text = initial; scan() } }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val s = withContext(Dispatchers.IO) {
                try { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().take(3_000_000).toByteArray().toString(Charsets.UTF_8) } } catch (e: Exception) { "" }
            }
            if (s.isEmpty()) note = "Could not read that file as text" else { text = s; scan() }
        }
    }

    Column(Modifier.fillMaxSize().padding(14.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Sensitive data scanner", style = MaterialTheme.typography.headlineSmall)
        Hint("Paste text, code, a chat export or open a text file. Finds API keys, passwords, private keys, card numbers (Luhn-checked), BVN/NIN, emails, phone numbers, wallet addresses and random-looking secrets, then masks them so you can share safely. Nothing leaves the phone.")
        OutlinedTextField(text, { text = it; report = null }, Modifier.fillMaxWidth().heightIn(min = 140.dp), label = { Text("Text to check") }, textStyle = MonoStyle)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = text.isNotBlank(), onClick = { scan() }) { Text("Scan") }
            OutlinedButton(onClick = { text = clipboardText(ctx); scan() }) { Text("Scan clipboard") }
            OutlinedButton(onClick = { pick.launch(arrayOf("text/*", "application/json", "application/xml", "*/*")) }) { Text("Open file") }
        }
        if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall)
        report?.let { r ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    ScoreGauge(r.safety, 100.dp, "safety")
                    Column {
                        Text(r.level, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text("${r.hits.size} item(s) found · ${r.hits.count { it.severity == 3 }} critical", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (r.hits.isEmpty()) Text("✅ Nothing sensitive detected.", color = Good)
            r.hits.sortedByDescending { it.severity }.take(120).forEach { h ->
                val c = when (h.severity) { 3 -> Bad; 2 -> Warn; else -> Info }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text(h.label, color = c, fontWeight = FontWeight.Bold)
                        Text(h.masked(), style = MonoStyle)
                        Text("line ${h.line}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (r.hits.isNotEmpty()) {
                ChipRow(listOf("full", "partial"), mode) { mode = it }
                Hint(if (mode == "full") "Replace every finding with [REDACTED:TYPE]" else "Keep first and last 3 characters, hide the rest")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { redacted = SensitiveScan.redact(text, r, mode) }) { Text("Redact") }
                    OutlinedButton(enabled = redacted.isNotEmpty(), onClick = { copyText(ctx, redacted) }) { Text("Copy safe text") }
                    OutlinedButton(enabled = redacted.isNotEmpty(), onClick = { shareText(ctx, redacted) }) { Text("Share") }
                }
                if (redacted.isNotEmpty()) OutlinedTextField(redacted, {}, Modifier.fillMaxWidth().heightIn(min = 120.dp), readOnly = true, label = { Text("Safe version") }, textStyle = MonoStyle)
            }
        }
    }
}
