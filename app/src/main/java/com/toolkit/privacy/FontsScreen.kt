package com.toolkit.privacy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun FontsScreen() {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("Type your text") }
    var group by remember { mutableStateOf("All") }
    var note by remember { mutableStateOf("") }
    val styles = remember(group) { FontStyles.all.filter { group == "All" || it.group == group } }
    val groups = remember { listOf("All") + FontStyles.groups }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
        item { Text("Fancy Fonts", style = MaterialTheme.typography.headlineSmall) }
        item { Text("${FontStyles.all.size} styles. They're real characters, so they paste into bios, captions, chats and usernames anywhere. Some apps or old phones may show boxes for rare styles.", style = MaterialTheme.typography.bodySmall) }
        item { OutlinedTextField(text, { text = it; note = "" }, Modifier.fillMaxWidth(), label = { Text("Your text") }) }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(groups) { g -> FilterChip(selected = group == g, onClick = { group = g }, label = { Text(g) }) }
            }
        }
        if (note.isNotEmpty()) item { Text(note, color = androidx.compose.ui.graphics.Color(0xFF43A047), style = MaterialTheme.typography.bodySmall) }
        items(styles, key = { it.name }) { st ->
            val out = remember(text, st) { st.apply(text.ifEmpty { " " }) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(st.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(out, fontSize = 20.sp, fontWeight = FontWeight.Normal)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { copyText(ctx, out); note = "Copied ${st.name}" }) { Text("Copy") }
                        OutlinedButton(onClick = { shareText(ctx, out) }) { Text("Share") }
                    }
                }
            }
        }
    }
}
