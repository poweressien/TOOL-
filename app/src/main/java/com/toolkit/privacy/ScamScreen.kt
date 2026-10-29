package com.toolkit.privacy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun ScamScreen(initial: String?, onConsumed: () -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    var verdict by remember { mutableStateOf<ScamVerdict?>(null) }

    LaunchedEffect(initial) {
        if (!initial.isNullOrBlank()) { text = initial; verdict = ScamRules.analyze(initial); onConsumed() }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
        item { Text("Scam Message Check", style = MaterialTheme.typography.headlineSmall) }
        item { Text("Paste an SMS, WhatsApp or email. Looks for fake bank alerts, OTP/BVN grabs, advance-fee tricks, fake prizes, risky links and more. You can also share a message straight to Privacy Toolkit.", style = MaterialTheme.typography.bodySmall) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 130.dp), label = { Text("Message") })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { verdict = ScamRules.analyze(text) }, enabled = text.isNotBlank()) { Text("Analyze") }
                        OutlinedButton(onClick = { text = clipboardText(ctx); verdict = ScamRules.analyze(text) }) { Text("Paste & analyze") }
                    }
                }
            }
        }
        verdict?.let { v ->
            item { VerdictCard("Message safety", v.label, v.score, v.findings.filter { it.delta != 0 }.ifEmpty { listOf(Finding("No scam patterns found", 0)) }) }
            if (v.links.isNotEmpty()) {
                item { Text("Links inside it", style = MaterialTheme.typography.titleSmall) }
                v.links.forEach { l ->
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(l.host, fontWeight = FontWeight.Medium, maxLines = 1)
                                    Text(l.label, color = androidx.compose.ui.graphics.Color(SiteRules.colorFor(l.score)), style = MaterialTheme.typography.bodySmall)
                                }
                                Text("${l.score}%", fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color(SiteRules.colorFor(l.score)))
                            }
                        }
                    }
                }
                item { Text("Don't tap them. Use Site Guard to run the full live scan if you must look.", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
