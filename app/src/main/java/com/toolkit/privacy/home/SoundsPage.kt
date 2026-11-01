package com.toolkit.privacy.home

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.toolkit.privacy.Notify
import com.toolkit.privacy.SoundCatalog

/**
 * Settings > Notification sound. 140+ built-in tones, used only for this app's own notifications
 * (Device alerts, Guard, Radar, New media). Tap a row to hear it and pick it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundsPage() {
    val ctx = LocalContext.current
    var soundOn by remember { mutableStateOf(SoundCatalog.soundOn(ctx)) }
    var picked by remember { mutableStateOf(SoundCatalog.selectedId(ctx)) }
    var cat by remember { mutableStateOf("All") }
    var query by remember { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { SoundCatalog.stop() } }

    val sendTest = {
        Notify.alert(ctx, 7001, "Sound test", "This is how your EKPE notifications will sound.", false, 5, 3)
    }
    val askPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) sendTest() else android.widget.Toast.makeText(ctx, "Allow notifications to hear the test.", android.widget.Toast.LENGTH_SHORT).show()
    }

    val q = query.trim().lowercase()
    val list = remember(cat, q) {
        SoundCatalog.all.filter { (cat == "All" || it.category == cat) && (q.isEmpty() || it.name.lowercase().contains(q) || it.category.lowercase().contains(q)) }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(Modifier.fillMaxWidth().padding(top = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Notification sound", fontWeight = FontWeight.Bold)
                        Text(
                            if (soundOn) "Now: " + (SoundCatalog.byId(picked)?.name ?: "—") else "Sound is off (notifications stay silent)",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = soundOn, onCheckedChange = { soundOn = it; SoundCatalog.setSoundOn(ctx, it) })
                }
                Text(
                    "${SoundCatalog.all.size} tones, used only for this app's own notifications. Volume follows your phone's notification volume.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = {
                    if (Build.VERSION.SDK_INT >= 33 && !Notify.canPost(ctx)) askPerm.launch(Manifest.permission.POST_NOTIFICATIONS) else sendTest()
                }) { Text("Send test notification") }
            }
        }

        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("Search ${SoundCatalog.all.size} sounds") },
            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (listOf("All") + SoundCatalog.categories).forEach { c ->
                FilterChip(selected = cat == c, onClick = { cat = c }, label = { Text(c) })
            }
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.id }) { s ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        picked = s.id; SoundCatalog.select(ctx, s.id)
                        if (!soundOn) { soundOn = true; SoundCatalog.setSoundOn(ctx, true) }
                        SoundCatalog.preview(ctx, s)
                    }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = picked == s.id, onClick = null, modifier = Modifier.padding(horizontal = 8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.name)
                        Text(s.category, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { SoundCatalog.preview(ctx, s) }) {
                        GlyphIcon("play_arrow", MaterialTheme.colorScheme.primary, 24.dp)
                    }
                }
                HorizontalDivider()
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
