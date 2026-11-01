package com.toolkit.privacy.home

import com.toolkit.privacy.*
import com.toolkit.privacy.core.*

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------------------------
// Shared bits
// ---------------------------------------------------------------------------------------------

private fun Context.safeStart(i: Intent) {
    try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {
        android.widget.Toast.makeText(this, "Could not open that screen on this phone.", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** ON / OFF label and switch. Flipping it stops (or restarts) everything in the app. */
@Composable
fun MasterToggle() {
    val ctx = LocalContext.current
    val on = MasterSwitch.on
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (on) "ON" else "OFF", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
            color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(6.dp))
        Switch(checked = on, onCheckedChange = { MasterSwitch.set(ctx, it) })
    }
}

@Composable
fun AppTopBar(title: String, leadingIcon: String, onLeading: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onLeading) { GlyphIcon(leadingIcon, MaterialTheme.colorScheme.onSurface, 24.dp) }
        Text(
            title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        MasterToggle()
    }
}

@Composable
private fun PageColumn(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) { content() }
}

/** Shown in place of a tool while the master switch is off. */
@Composable
fun PausedPanel() {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        GlyphIcon("power_settings_new", MaterialTheme.colorScheme.onSurfaceVariant, 64.dp)
        Spacer(Modifier.height(16.dp))
        Text("EKPE is switched off", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Every tool and all background activity is paused. Turn the master switch on and everything that was running starts again.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = { MasterSwitch.set(ctx, true) }) { Text("Turn on") }
    }
}

// ---------------------------------------------------------------------------------------------
// Home: every tool is its own app tile
// ---------------------------------------------------------------------------------------------

@Composable
fun ToolTile(t: ToolDef, enabled: Boolean, onClick: () -> Unit) {
    val base = Color(t.group.color)
    val lightTheme = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val tint = if (lightTheme) lerp(base, Color.Black, 0.35f) else base
    Column(
        Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.38f)
            .clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(58.dp).clip(RoundedCornerShape(18.dp)).background(base.copy(alpha = if (lightTheme) 0.22f else 0.16f)),
            contentAlignment = Alignment.Center
        ) { GlyphIcon(t.icon, tint, 30.dp) }
        Spacer(Modifier.height(6.dp))
        Text(
            t.name, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
            maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp)
        )
    }
}

@Composable
fun HomeScreen(onOpen: (ToolDef) -> Unit) {
    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase()
    val on = MasterSwitch.on
    val groups = remember(q) {
        Group.values().map { g ->
            g to Catalog.tools.filter { it.group == g && (q.isEmpty() || it.name.lowercase().contains(q) || g.title.lowercase().contains(q)) }
        }.filter { it.second.isNotEmpty() }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 84.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (on) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlyphIcon("power_settings_new", if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onErrorContainer, 26.dp)
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(if (on) "Everything is running" else "Everything is paused", fontWeight = FontWeight.Bold)
                            Text(
                                if (on) "${Catalog.tools.size} tools ready. The switch at the top stops all activity at once."
                                else "Turn the master switch on to use the tools. Whatever was running will start again.",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (on) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("Search ${Catalog.tools.size} tools") },
                    leadingIcon = { GlyphIcon("search", MaterialTheme.colorScheme.onSurfaceVariant, 22.dp) },
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()
                )
            }
        }
        groups.forEach { (g, list) ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.padding(start = 4.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(g.color)))
                    Text(
                        g.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                    Text(
                        "  ${list.size}", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(list) { t -> ToolTile(t, on) { onOpen(t) } }
        }
        if (groups.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "No tool matches \"$query\".", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp).fillMaxWidth(), textAlign = TextAlign.Center
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Pages opened from the side menu
// ---------------------------------------------------------------------------------------------

@Composable
fun SettingsPage(openAppearance: () -> Unit) {
    val ctx = LocalContext.current
    val on = MasterSwitch.on
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(on) { delay(1500); tick++ }
    val running = remember(on, tick) { MasterSwitch.runningNow(ctx) }

    PageColumn {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Master switch", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "OFF stops every tool and background activity in the app. ON starts again whatever was running.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = on, onCheckedChange = { MasterSwitch.set(ctx, it) })
                }
                HorizontalDivider()
                Text(
                    if (running.isEmpty()) "Nothing is running in the background right now." else "Running now: " + running.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(onClick = { tick++ }) { Text("Refresh") }
            }
        }

        Card(Modifier.fillMaxWidth().clickable { openAppearance() }) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon("palette", MaterialTheme.colorScheme.primary, 26.dp)
                Column(Modifier.padding(start = 12.dp)) {
                    Text("Appearance", fontWeight = FontWeight.Bold)
                    Text(
                        "Theme: " + ThemeState.mode.label, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Phone settings for this app", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                OutlinedButton(
                    onClick = { ctx.safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Permissions") }
                OutlinedButton(
                    onClick = {
                        ctx.safeStart(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Notifications") }
                OutlinedButton(
                    onClick = { ctx.safeStart(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Battery optimisation") }
                OutlinedButton(
                    onClick = { ctx.safeStart(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName))) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Display over other apps") }
            }
        }
    }
}

private val accentSwatches = listOf(
    0xFFF2C94C, 0xFFFF8A65, 0xFFF0657A, 0xFFE879F9, 0xFFA78BFA,
    0xFF60A5FA, 0xFF2DD4BF, 0xFF4ADE80, 0xFFA3E635, 0xFFFFFFFF
).map { it.toInt() }

private val bgSwatches = listOf(
    0xFF121214, 0xFF000000, 0xFF0B1220, 0xFF10201A, 0xFF1F1426,
    0xFF2A1D17, 0xFFFFF9E6, 0xFFFFFFFF, 0xFFEAF2FF, 0xFFF1F5E9
).map { it.toInt() }

private fun hex6(c: Int) = "#%06X".format(c and 0xFFFFFF)

@Composable
private fun SwatchRow(colors: List<Int>, selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        colors.forEach { c ->
            val sel = (c or 0xFF000000.toInt()) == (selected or 0xFF000000.toInt())
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Color(c))
                    .border(if (sel) 3.dp else 1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable { onPick(c) }
            )
        }
    }
}

@Composable
private fun CustomThemeEditor() {
    val ctx = LocalContext.current
    val accent = ThemeState.customAccent
    val bg = ThemeState.customBg
    var hexA by remember(accent) { mutableStateOf(hex6(accent)) }
    var hexB by remember(bg) { mutableStateOf(hex6(bg)) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Accent colour", fontWeight = FontWeight.Bold)
            SwatchRow(accentSwatches, accent) { ThemeState.setCustom(ctx, it, bg) }
            OutlinedTextField(
                value = hexA, singleLine = true, label = { Text("Accent hex, e.g. #F2C94C") }, modifier = Modifier.fillMaxWidth(),
                onValueChange = { v -> hexA = v; parseHexColor(v)?.let { ThemeState.setCustom(ctx, it, bg) } }
            )
            Text("Background", fontWeight = FontWeight.Bold)
            SwatchRow(bgSwatches, bg) { ThemeState.setCustom(ctx, accent, it) }
            OutlinedTextField(
                value = hexB, singleLine = true, label = { Text("Background hex, e.g. #121214") }, modifier = Modifier.fillMaxWidth(),
                onValueChange = { v -> hexB = v; parseHexColor(v)?.let { ThemeState.setCustom(ctx, accent, it) } }
            )
            Text(
                "Text colour is chosen automatically so it stays readable on your background.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun AppearancePage() {
    val ctx = LocalContext.current
    val mode = ThemeState.mode
    PageColumn {
        Text(
            "Pick how the whole app looks. It applies to every screen, including the EKPE tools.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ThemeMode.values().forEach { m ->
            val sel = m == mode
            Card(
                Modifier.fillMaxWidth().clickable { ThemeState.setMode(ctx, m) },
                border = if (sel) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = sel, onClick = { ThemeState.setMode(ctx, m) })
                    Column(Modifier.padding(start = 6.dp)) {
                        Text(m.label, fontWeight = FontWeight.Bold)
                        Text(m.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (mode == ThemeMode.CUSTOM) CustomThemeEditor()
    }
}

@Composable
fun AboutPage() {
    val ctx = LocalContext.current
    val ver = remember {
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
    }
    PageColumn {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("EKPE", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("Privacy Toolkit + EKPE suite in one app", style = MaterialTheme.typography.titleSmall)
                if (ver.isNotEmpty()) Text("Version $ver", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("What it is", fontWeight = FontWeight.Bold)
                Text(
                    "${Catalog.tools.size} tools for files, networks, security, health, prices, radio, location and more, each one an app of its own on the home screen.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Tools work on your phone. Features that use the internet or your Google account (like Cloud Gallery) do so only when you switch them on.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun DeveloperPage() {
    val ctx = LocalContext.current
    PageColumn {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("iNWEKOBONG", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("iWEKOBONGstudios", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Text(
                    "Web apps, mobile apps, games and backend services built for Nigeria and Africa.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Button(
            onClick = { ctx.safeStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/poweressien"))) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("GitHub: poweressien") }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Build info", fontWeight = FontWeight.Bold)
                val ver = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }
                Text("Package: ${ctx.packageName}", style = MaterialTheme.typography.bodySmall)
                Text("Version: $ver", style = MaterialTheme.typography.bodySmall)
                Text("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", style = MaterialTheme.typography.bodySmall)
                Text("Device: ${Build.MANUFACTURER} ${Build.MODEL}", style = MaterialTheme.typography.bodySmall)
                Text("Tools: ${Catalog.tools.size}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
