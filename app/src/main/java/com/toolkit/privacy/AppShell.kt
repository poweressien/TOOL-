package com.toolkit.privacy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.PackageInfoCompat
import com.inwekobong.ekpe.MainActivity as EkpeActivity
import kotlinx.coroutines.launch

private const val D_HOME = "home"
private const val D_SETTINGS = "settings"
private const val D_THEME = "theme"
private const val D_ABOUT = "about"
private const val D_DEV = "developer"
private const val D_TOOL = "tool:"

/** Names shown on the About and Developer pages. Edit here. */
object AppInfo {
    const val APP_NAME = "EKPE"
    const val DEVELOPER = "EKPE"
    const val STUDIO = "iNWEKOBONG studios"
    const val TAGLINE = "Privacy, network, files, health, radio and security tools in one app."
}

/** Home screen + drawer + master switch. Every tool of both original apps is one tile on the home grid. */
@Composable
fun App(route: Route?) {
    val ctx = LocalContext.current
    MasterSwitch.init(ctx)
    val on = MasterSwitch.enabled
    var dest by remember { mutableStateOf(D_HOME) }
    val pending = remember { Pending() }
    var confirmOff by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    LaunchedEffect(route?.id) {
        route?.let {
            val t = ToolRegistry.byRoute(it.tab, it.sub)
            if (t != null) {
                pending.url = it.url; pending.text = it.text
                dest = D_TOOL + t.id
            }
        }
    }

    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    BackHandler(enabled = dest != D_HOME && !drawerState.isOpen) { dest = if (dest == D_THEME) D_SETTINGS else D_HOME }

    val toggle: (Boolean) -> Unit = { v ->
        if (v) { MasterSwitch.turnOn(ctx) } else { confirmOff = true }
    }
    val open: (ToolDef) -> Unit = { t ->
        if (!MasterSwitch.enabled) {
            Toast.makeText(ctx, "EKPE is paused. Switch it on first.", Toast.LENGTH_SHORT).show()
        } else if (t.isEkpe) {
            launchEkpe(ctx, t)
        } else {
            dest = D_TOOL + t.id
        }
    }
    fun go(d: String) { dest = d; scope.launch { drawerState.close() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen || dest == D_HOME,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.ic_fg), null, Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop)
                        Column(Modifier.padding(start = 14.dp)) {
                            Text(AppInfo.APP_NAME, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("${ToolRegistry.all.size} apps in one", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    val pad = NavigationDrawerItemDefaults.ItemPadding
                    NavigationDrawerItem(label = { Text("Home") }, icon = { Icon(Icons.Rounded.Home, null) }, selected = dest == D_HOME, onClick = { go(D_HOME) }, modifier = Modifier.padding(pad))
                    NavigationDrawerItem(label = { Text("Settings") }, icon = { Icon(Icons.Rounded.Settings, null) }, selected = dest == D_SETTINGS, onClick = { go(D_SETTINGS) }, modifier = Modifier.padding(pad))
                    NavigationDrawerItem(label = { Text("Theme & colors") }, icon = { Icon(Icons.Rounded.Palette, null) }, selected = dest == D_THEME, onClick = { go(D_THEME) }, modifier = Modifier.padding(pad))
                    NavigationDrawerItem(label = { Text("About") }, icon = { Icon(Icons.Rounded.Info, null) }, selected = dest == D_ABOUT, onClick = { go(D_ABOUT) }, modifier = Modifier.padding(pad))
                    NavigationDrawerItem(label = { Text("Developer") }, icon = { Icon(Icons.Rounded.DeveloperMode, null) }, selected = dest == D_DEV, onClick = { go(D_DEV) }, modifier = Modifier.padding(pad))
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Master switch", fontWeight = FontWeight.Bold)
                            Text(if (on) "Everything is running normally" else "Everything is paused", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = on, onCheckedChange = toggle)
                    }
                }
            }
        }
    ) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            val tool = if (dest.startsWith(D_TOOL)) ToolRegistry.byId(dest.removePrefix(D_TOOL)) else null
            val title = when {
                tool != null -> tool.name
                dest == D_SETTINGS -> "Settings"
                dest == D_THEME -> "Theme & colors"
                dest == D_ABOUT -> "About"
                dest == D_DEV -> "Developer"
                else -> AppInfo.APP_NAME
            }
            TopBar(title, dest != D_HOME, on,
                onNav = {
                    if (dest == D_HOME) { scope.launch { drawerState.open() } }
                    else { dest = if (dest == D_THEME) D_SETTINGS else D_HOME }
                },
                onToggle = toggle)
            Box(Modifier.height(1.dp).fillMaxWidth().background(MaterialTheme.colorScheme.outlineVariant))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    tool != null -> ToolHost(tool, on, pending) { MasterSwitch.turnOn(ctx) }
                    dest == D_SETTINGS -> SettingsScreen(on, toggle) { dest = D_THEME }
                    dest == D_THEME -> ThemeScreen()
                    dest == D_ABOUT -> AboutScreen()
                    dest == D_DEV -> DeveloperScreen()
                    else -> LauncherHome(on, open) { MasterSwitch.turnOn(ctx) }
                }
            }
        }
    }

    if (confirmOff) {
        val active = remember { MasterSwitch.activeNow(ctx) }
        AlertDialog(
            onDismissRequest = { confirmOff = false },
            title = { Text("Pause everything?") },
            text = {
                Text(
                    "Every tool, background watcher, overlay, scan and tunnel stops, and nothing starts by itself until you switch back on. " +
                        "Your data and settings are kept. " +
                        if (active.isEmpty()) "Nothing is running in the background right now." else "Running now: " + active.joinToString(", ") + "."
                )
            },
            confirmButton = { TextButton(onClick = { confirmOff = false; MasterSwitch.turnOff(ctx) }) { Text("Pause all") } },
            dismissButton = { TextButton(onClick = { confirmOff = false }) { Text("Cancel") } }
        )
    }
}

private fun launchEkpe(ctx: Context, t: ToolDef) {
    val i = Intent(ctx, EkpeActivity::class.java).putExtra("tab", t.ekpeTab)
    t.ekpeTool?.let { i.putExtra("tool", it) }
    ctx.startActivity(i)
}

@Composable
private fun TopBar(title: String, showBack: Boolean, on: Boolean, onNav: () -> Unit, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onNav) {
            if (showBack) Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            else Icon(Icons.Rounded.Menu, contentDescription = "Menu")
        }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (on) "ON" else "PAUSED", style = MaterialTheme.typography.labelMedium, color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        Spacer(Modifier.size(6.dp))
        Switch(checked = on, onCheckedChange = onToggle)
        Spacer(Modifier.size(10.dp))
    }
}

// ---------------------------------------------------------------------------------------------- home

@Composable
private fun LauncherHome(on: Boolean, onOpen: (ToolDef) -> Unit, onTurnOn: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(query) { ToolRegistry.search(query) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 24.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                singleLine = true,
                placeholder = { Text("Search ${ToolRegistry.all.size} apps") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) }
            )
        }
        if (!on) {
            item(span = { GridItemSpan(maxLineSpan) }) { PausedBanner(onTurnOn) }
        }
        if (query.isBlank()) {
            ToolRegistry.sections.forEach { s ->
                val list = ToolRegistry.bySection(s)
                item(key = "h_$s", span = { GridItemSpan(maxLineSpan) }) { SectionHeader(s, list.size) }
                items(list, key = { it.id }) { t -> AppTile(t, on) { onOpen(t) } }
            }
        } else {
            item(key = "h_results", span = { GridItemSpan(maxLineSpan) }) { SectionHeader("Results", results.size) }
            items(results, key = { it.id }) { t -> AppTile(t, on) { onOpen(t) } }
            if (results.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Text("No app matches \"$query\".", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text("$count", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One app: unique icon in a rounded tile with its name underneath. */
@Composable
private fun AppTile(t: ToolDef, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(2.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 2.dp).alpha(if (enabled) 1f else 0.4f),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(54.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(t.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(5.dp))
        Text(t.name, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun PausedBanner(onTurnOn: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(4.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.PowerSettingsNew, null, tint = MaterialTheme.colorScheme.error)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("EKPE is paused", fontWeight = FontWeight.Bold)
                Text("All tools and background work are stopped.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onTurnOn) { Text("Turn on") }
        }
    }
}

@Composable
private fun ToolHost(tool: ToolDef, on: Boolean, pending: Pending, onTurnOn: () -> Unit) {
    if (!on) {
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Rounded.PowerSettingsNew, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(12.dp))
            Text("EKPE is paused", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("${tool.name} and every other tool are stopped. Turn the master switch on to use them.", Modifier.padding(top = 6.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onTurnOn) { Text("Turn on") }
        }
        return
    }
    val c = tool.content
    Box(Modifier.fillMaxSize()) { if (c != null) c(pending) }
}

// ---------------------------------------------------------------------------------------------- settings

private fun openSettings(ctx: Context, intent: Intent) {
    try { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {
        Toast.makeText(ctx, "This screen is not available on your phone", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun SettingsScreen(on: Boolean, onToggle: (Boolean) -> Unit, onTheme: () -> Unit) {
    val ctx = LocalContext.current
    val pkg = Uri.parse("package:" + ctx.packageName)
    val active = remember(on) { MasterSwitch.activeNow(ctx) }
    val waiting = remember(on) { MasterSwitch.pausedSnapshot(ctx) }
    Page("Settings", "General controls for the whole app") {
        Sec("Master switch") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (on) "App is ON" else "App is PAUSED", fontWeight = FontWeight.Bold)
                    Note("Off stops every tool, scan, monitor, overlay, VPN and background service at once. On starts everything that was running.")
                }
                Switch(checked = on, onCheckedChange = onToggle)
            }
            if (on) Note(if (active.isEmpty()) "No background work is running right now." else "Running now: " + active.joinToString(", "))
            else Note(if (waiting.isEmpty()) "Nothing was running when you paused." else "Will start again when you switch on: " + waiting.joinToString(", "))
        }
        Sec("Appearance") {
            Text("Current: ${ThemeStore.currentMode.label}")
            Button(onClick = onTheme) { Text("Theme & colors") }
        }
        Sec("Permissions & system") {
            Note("Shortcuts to the Android screens that control what this app may do.")
            OutlinedButton(onClick = { openSettings(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)) }, modifier = Modifier.fillMaxWidth()) { Text("App permissions") }
            OutlinedButton(onClick = { openSettings(ctx, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)) }, modifier = Modifier.fillMaxWidth()) { Text("Notifications") }
            OutlinedButton(onClick = { openSettings(ctx, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)) }, modifier = Modifier.fillMaxWidth()) { Text("Display over other apps") }
            OutlinedButton(onClick = { openSettings(ctx, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }, modifier = Modifier.fillMaxWidth()) { Text("Usage access") }
            OutlinedButton(onClick = { openSettings(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, modifier = Modifier.fillMaxWidth()) { Text("Accessibility") }
            OutlinedButton(onClick = { openSettings(ctx, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }, modifier = Modifier.fillMaxWidth()) { Text("Battery optimisation") }
        }
    }
}

// ---------------------------------------------------------------------------------------------- theme

private val accentPresets = listOf(0xFFF3D87A, 0xFF8A6400, 0xFFFFB300, 0xFFFF7043, 0xFFEF5350, 0xFFEC407A, 0xFFAB47BC, 0xFF5C6BC0, 0xFF42A5F5, 0xFF26A69A, 0xFF66BB6A, 0xFFD4E157).map { it.toInt() }
private val bgPresets = listOf(0xFF0E1013, 0xFF000000, 0xFF121212, 0xFF0F1220, 0xFF10150F, 0xFF1A1410, 0xFFFFFBEC, 0xFFFFFFFF, 0xFFF6F8FA, 0xFFF5EFE6, 0xFFEAF4F4, 0xFFFDEEF4).map { it.toInt() }
private val cardPresets = listOf(0xFF1A1D23, 0xFF1E2229, 0xFF161B22, 0xFF262B33, 0xFFFFF1C9, 0xFFFFFFFF, 0xFFE6ECF2, 0xFFF1E7D6).map { it.toInt() }
private val textPresets = listOf(0xFFEDEEF0, 0xFFFFFFFF, 0xFFF3D87A, 0xFF1F1B12, 0xFF000000, 0xFF263238).map { it.toInt() }

@Composable
private fun ThemeScreen() {
    val ctx = LocalContext.current
    val v = ThemeStore.version   // redraw when anything changes
    val mode = remember(v) { ThemeStore.currentMode }
    val shown = remember(v) { ThemeStore.current }
    Page("Theme & colors", "Applies to every screen, including the EKPE suite. Changes are instant.") {
        ThemeMode.values().forEach { m ->
            val sample = when (m) {
                ThemeMode.POWDER_BLACK -> ThemeStore.POWDER
                ThemeMode.BUTTER_WHITE -> ThemeStore.BUTTER
                ThemeMode.SYSTEM -> ThemeStore.resolve(ctx, ThemeStore.systemIsDark(ctx))
                ThemeMode.CUSTOM -> AppColors(ThemeStore.customDark, ThemeStore.customBg, ThemeStore.customCard, ThemeStore.customText, ThemeStore.customText, ThemeStore.customAccent, ThemeStore.onColorFor(ThemeStore.customAccent))
            }
            Card(Modifier.fillMaxWidth().clickable {
                if (m == ThemeMode.CUSTOM) { if (mode != ThemeMode.CUSTOM) ThemeStore.customFrom(ctx, shown) else ThemeStore.setMode(ctx, m) }
                else ThemeStore.setMode(ctx, m)
            }, border = if (mode == m) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = mode == m, onClick = null)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(m.label, fontWeight = FontWeight.Bold)
                        Text(m.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(Color(sample.bg)).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(16.dp).clip(CircleShape).background(Color(sample.card)).border(1.dp, Color(sample.sub), CircleShape))
                        Box(Modifier.size(16.dp).clip(CircleShape).background(Color(sample.text)))
                        Box(Modifier.size(16.dp).clip(CircleShape).background(Color(sample.accent)))
                    }
                }
            }
        }
        if (mode == ThemeMode.CUSTOM) {
            Sec("Customize") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = ThemeStore.customDark, onClick = { ThemeStore.setCustom(ctx, dark = true) }, label = { Text("Dark base") })
                    FilterChip(selected = !ThemeStore.customDark, onClick = { ThemeStore.setCustom(ctx, dark = false) }, label = { Text("Light base") })
                }
                Note("The base only decides the shape of switches, dialogs and status-bar icons. Colours below are fully yours.")
                ColorRow("Accent (icons, buttons, highlights)", ThemeStore.customAccent, accentPresets) { ThemeStore.setCustom(ctx, accent = it) }
                ColorRow("Background", ThemeStore.customBg, bgPresets) { ThemeStore.setCustom(ctx, bg = it) }
                ColorRow("Cards and tiles", ThemeStore.customCard, cardPresets) { ThemeStore.setCustom(ctx, card = it) }
                ColorRow("Text", ThemeStore.customText, textPresets) { ThemeStore.setCustom(ctx, text = it) }
            }
        }
        OutlinedButton(onClick = { ThemeStore.reset(ctx) }, modifier = Modifier.fillMaxWidth()) { Text("Reset to Powder Black") }
    }
}

@Composable
private fun ColorRow(label: String, value: Int, presets: List<Int>, onPick: (Int) -> Unit) {
    var hex by remember(value) { mutableStateOf(ThemeStore.hex(value)) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontWeight = FontWeight.Bold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { c ->
                val picked = (value and 0xFFFFFF) == (c and 0xFFFFFF)
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(Color(c))
                        .border(2.dp, if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                        .clickable { onPick(c) }
                )
            }
        }
        OutlinedTextField(
            value = hex,
            onValueChange = { hex = it; ThemeStore.parseHex(it)?.let { c -> onPick(c) } },
            singleLine = true, label = { Text("Hex  #RRGGBB") }, modifier = Modifier.fillMaxWidth()
        )
    }
}

// ---------------------------------------------------------------------------------------------- about / developer

private fun versionOf(ctx: Context): Pair<String, Long> = try {
    val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
    (pi.versionName ?: "?") to PackageInfoCompat.getLongVersionCode(pi)
} catch (e: Exception) { "?" to 0L }

@Composable
private fun AboutScreen() {
    val ctx = LocalContext.current
    val (ver, code) = remember { versionOf(ctx) }
    Page(AppInfo.APP_NAME, "Version $ver (build $code)") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_fg), null, Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)), contentScale = ContentScale.Crop)
            Text(AppInfo.TAGLINE, Modifier.padding(start = 14.dp), style = MaterialTheme.typography.bodyMedium)
        }
        Sec("What is inside") {
            Text("${ToolRegistry.all.size} apps in ${ToolRegistry.sections.size} groups:")
            ToolRegistry.sections.forEach { s -> Kv(s, "${ToolRegistry.bySection(s).size} apps") }
        }
        Sec("Your data") {
            Note("There is no account and no server. Tools run on the phone, and the ones that look things up (rates, site checks, Drive backup, updates) talk straight to public services or to your own Google account.")
            Note("The master switch in the top bar stops everything instantly.")
        }
        Sec("Made by") { Text("${AppInfo.DEVELOPER}  |  ${AppInfo.STUDIO}") }
    }
}

@Composable
private fun DeveloperScreen() {
    val ctx = LocalContext.current
    val (ver, code) = remember { versionOf(ctx) }
    val active = remember { MasterSwitch.activeNow(ctx) }
    val diag = remember(ThemeStore.version) {
        buildString {
            appendLine("${AppInfo.APP_NAME} $ver ($code)")
            appendLine("Package: ${ctx.packageName}")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Theme: ${ThemeStore.currentMode.label}")
            appendLine("Master switch: ${if (MasterSwitch.enabled) "on" else "paused"}")
            appendLine("Running: ${if (active.isEmpty()) "nothing" else active.joinToString()}")
            append("Apps: ${ToolRegistry.all.size}")
        }
    }
    Page("Developer", "Build and diagnostic information") {
        Sec("Developer") {
            Kv("Name", AppInfo.DEVELOPER)
            Kv("Studio", AppInfo.STUDIO)
        }
        Sec("Build") {
            Kv("App", AppInfo.APP_NAME)
            Kv("Version", "$ver ($code)")
            Kv("Package", ctx.packageName, mono = true)
            Kv("Apps", "${ToolRegistry.all.size}")
        }
        Sec("This phone") {
            Kv("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            Kv("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
            Kv("ABIs", Build.SUPPORTED_ABIS.joinToString())
        }
        Sec("Right now") {
            Kv("Master switch", if (MasterSwitch.enabled) "ON" else "PAUSED")
            Kv("Theme", ThemeStore.currentMode.label)
            Note(if (active.isEmpty()) "No background work is running." else "Running: " + active.joinToString(", "))
        }
        Button(onClick = {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("EKPE diagnostics", diag))
            Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
        }, modifier = Modifier.fillMaxWidth()) { Text("Copy diagnostics") }
        OutlinedButton(onClick = { openSettings(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))) }, modifier = Modifier.fillMaxWidth()) { Text("Open Android app info") }
    }
}
