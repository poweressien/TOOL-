package com.toolkit.privacy

import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Suppress("DEPRECATION")
fun loadApps(ctx: Context): List<AppEntry> {
    val pm = ctx.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val usage = HashMap<String, Pair<Long, Long>>()
    if (MonitorChecks.hasUsageAccess(ctx)) {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 30L * 86_400_000L, now)?.forEach { u ->
            val old = usage[u.packageName]
            usage[u.packageName] = ((old?.first ?: 0L) + u.totalTimeInForeground) to maxOf(old?.second ?: 0L, u.lastTimeUsed)
        }
    }
    val week = System.currentTimeMillis() - 7L * 86_400_000L
    return pm.queryIntentActivities(launcher, 0).mapNotNull { ri ->
        val ai = ri.activityInfo?.applicationInfo ?: return@mapNotNull null
        val pkg = ai.packageName
        val sys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
        val u = usage[pkg]
        val last = u?.second ?: 0L
        AppEntry(pkg, ri.loadLabel(pm).toString(), if (Build.VERSION.SDK_INT >= 26) ai.category else -1, sys,
            if (u != null && last >= week) u.first.coerceAtLeast(1L) else 0L, last)
    }.distinctBy { it.pkg }
}


private val SORTS = listOf("Folders", "A–Z", "Most used", "Unused", "Newest", "Largest")

private fun sortApps(list: List<OrgApp>, mode: String, now: Long): List<OrgApp> {
    val base = when (mode) {
        "A–Z" -> list.sortedBy { it.label.lowercase() }
        "Most used" -> list.filter { it.base.usedMs7d > 0 }.sortedByDescending { it.base.usedMs7d }
        "Unused" -> list.filter { !it.system && it.base.usedMs7d == 0L && (it.base.lastUsedMs == 0L || now - it.base.lastUsedMs > 30L * 86_400_000L) }.sortedBy { it.label.lowercase() }
        "Newest" -> list.sortedByDescending { it.installed }
        "Largest" -> list.sortedByDescending { it.sizeBytes }
        else -> list.sortedBy { it.label.lowercase() }
    }
    return base.sortedByDescending { it.pinned }   // stable: pinned float to the top, order kept inside each group
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppCell(app: OrgApp, icon: ImageBitmap?, onTap: (OrgApp) -> Unit, onMenu: (OrgApp) -> Unit) {
    Column(
        Modifier.fillMaxWidth().combinedClickable(onClick = { onTap(app) }, onLongClick = { onMenu(app) }).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box {
            if (icon != null) Image(icon, null, Modifier.size(48.dp)) else Box(Modifier.size(48.dp))
            if (app.pinned) Text("★", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopEnd))
        }
        Text(app.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppRow(app: OrgApp, icon: ImageBitmap?, onTap: (OrgApp) -> Unit, onMenu: (OrgApp) -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = { onTap(app) }, onLongClick = { onMenu(app) }).padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (icon != null) Image(icon, null, Modifier.size(42.dp)) else Box(Modifier.size(42.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label + if (app.pinned) "  ★" else "", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(app.category + (if (app.tags.isNotEmpty()) "  ·  " + app.tags.joinToString(", ") else ""), style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (app.sizeBytes > 0) Text(android.text.format.Formatter.formatShortFileSize(LocalContext.current, app.sizeBytes), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun OrganizerScreen(asHome: Boolean = false) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var meta by remember { mutableStateOf<Map<String, Pair<Long, Long>>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var version by remember { mutableIntStateOf(0) }
    var mode by rememberSaveable { mutableStateOf(OrgStore.sort(ctx)) }
    var styleName by rememberSaveable { mutableStateOf(OrgStore.style(ctx)) }
    var interval by rememberSaveable { mutableIntStateOf(OrgStore.interval(ctx)) }
    var query by rememberSaveable { mutableStateOf("") }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf<OrgApp?>(null) }
    var edit by remember { mutableStateOf<Pair<OrgApp, Boolean>?>(null) }   // second = true: tags, false: name
    var detail by remember { mutableStateOf<OrgApp?>(null) }
    var shown by remember { mutableIntStateOf(0) }
    var homeOn by remember { mutableStateOf(false) }
    val homeCn = remember { ComponentName(ctx, HomeActivity::class.java) }
    val usage = MonitorChecks.hasUsageAccess(ctx)
    val style = OrgStyle.from(styleName)
    val icons = remember { mutableStateMapOf<String, ImageBitmap>() }
    val styleNow by rememberUpdatedState(style)
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadApps(ctx) }
        loading = false
        homeOn = ctx.packageManager.getComponentEnabledSetting(homeCn) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        withContext(Dispatchers.IO) {
            meta = OrgStore.loadMeta(ctx, apps)
            for (a in apps) if (!icons.containsKey(a.pkg)) {
                try { icons[a.pkg] = ctx.packageManager.getApplicationIcon(a.pkg).toBitmap(112, 112).asImageBitmap() } catch (_: Exception) {}
            }
        }
        // come back to exactly where the list was left
        val saved = Prefs.sp(ctx).getInt("org_scroll", 0)
        if (saved > 0) { try { gridState.scrollToItem(saved); listState.scrollToItem(saved) } catch (_: Exception) {} }
    }
    DisposableEffect(Unit) {
        onDispose {
            val idx = if (styleNow == OrgStyle.LIST) listState.firstVisibleItemIndex else gridState.firstVisibleItemIndex
            Prefs.sp(ctx).edit().putInt("org_scroll", idx).apply()
        }
    }

    val now = System.currentTimeMillis()
    val all = remember(apps, meta, version) { OrgStore.decorate(ctx, apps, meta) }
    val filtered = remember(all, query, showHidden, mode) {
        all.filter { (showHidden || !it.hidden) && (query.isBlank() ||
            it.label.contains(query, true) || it.pkg.contains(query, true) ||
            it.category.contains(query, true) || it.tags.any { t -> t.contains(query, true) }) }
    }
    val sorted = remember(filtered, mode) { sortApps(filtered, mode, now) }
    val hiddenCount = remember(all) { all.count { it.hidden } }

    val launchApp: (OrgApp) -> Unit = { a -> if (!OrgActions.open(ctx, a.pkg)) android.widget.Toast.makeText(ctx, "${a.label} has no screen to open", android.widget.Toast.LENGTH_SHORT).show() }
    val onMenu: (OrgApp) -> Unit = { menu = it }

    Column(Modifier.fillMaxSize().then(if (asHome) Modifier.statusBarsPadding() else Modifier)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (asHome) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Home", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { ctx.startActivity(Intent(ctx, MainActivity::class.java)) }) { Text("Toolkit") }
            } else Text("Home Organizer", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search ${all.size} apps, tags, folders") })
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrgStyle.values().forEach { st -> FilterChip(selected = style == st, onClick = { styleName = st.name; OrgStore.setStyle(ctx, st.name) }, label = { Text(st.title) }) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SORTS.forEach { m -> FilterChip(selected = mode == m, onClick = { mode = m; OrgStore.setSort(ctx, m) }, label = { Text(m) }) }
                if (hiddenCount > 0) FilterChip(selected = showHidden, onClick = { showHidden = !showHidden }, label = { Text("Hidden ($hiddenCount)") })
            }
            if (style == OrgStyle.SHUFFLE) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Swap every ${interval}s", style = MaterialTheme.typography.labelMedium)
                Slider(interval.toFloat(), { interval = it.toInt().coerceIn(1, 30) }, Modifier.weight(1f).padding(start = 12.dp),
                    valueRange = 1f..30f, onValueChangeFinished = { OrgStore.setInterval(ctx, interval) })
            }
            if (style.live) Text(style.hint + if (shown in 1 until sorted.size) "  Showing $shown of ${sorted.size}; search, filter or pin to choose which." else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if ((mode == "Most used" || mode == "Unused") && !usage) Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Needs Usage access to know which apps you actually open.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Allow") }
            }
        }
        when {
            style.live -> Box(Modifier.fillMaxSize().padding(horizontal = 6.dp)) {
                OrgStage(style, sorted, icons, interval, launchApp, onMenu) { shown = it }
            }
            style == OrgStyle.LIST -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                if (mode == "Folders") sorted.groupBy { if (it.pinned) "Pinned" else it.category }.toSortedMap(compareBy<String> { if (it == "Pinned") "" else it }).forEach { (name, list) ->
                    item(key = "h$name") { Text("$name  ·  ${list.size}", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)) }
                    listItems(list, key = { name + it.pkg }) { AppRow(it, icons[it.pkg], launchApp, onMenu) }
                } else listItems(sorted, key = { it.pkg }) { AppRow(it, icons[it.pkg], launchApp, onMenu) }
            }
            else -> LazyVerticalGrid(GridCells.Adaptive(76.dp), Modifier.fillMaxSize().padding(horizontal = 10.dp), state = gridState, contentPadding = PaddingValues(bottom = 24.dp)) {
                if (!asHome) item(span = { GridItemSpan(maxLineSpan) }) {
                    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Android doesn't let one app move icons around inside another launcher. So this organizes every app itself (folders, tags, pins, live layouts) and you can make it your home screen.", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    ctx.packageManager.setComponentEnabledSetting(homeCn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                                    homeOn = true
                                    ctx.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }) { Text(if (homeOn) "Choose home app" else "Use as my home screen") }
                                if (homeOn) OutlinedButton(onClick = {
                                    ctx.packageManager.setComponentEnabledSetting(homeCn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                                    homeOn = false
                                }) { Text("Turn off") }
                            }
                        }
                    }
                }
                if (mode == "Folders") sorted.groupBy { if (it.pinned) "Pinned" else it.category }.toSortedMap(compareBy<String> { if (it == "Pinned") "" else it }).forEach { (name, list) ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "h$name") { Text("$name  ·  ${list.size}", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)) }
                    items(list, key = { name + it.pkg }) { AppCell(it, icons[it.pkg], launchApp, onMenu) }
                } else {
                    if (mode == "Unused") item(span = { GridItemSpan(maxLineSpan) }) { Text("Not opened in 30 days. Long-press an app to uninstall and free space.", style = MaterialTheme.typography.bodySmall) }
                    items(sorted, key = { it.pkg }) { AppCell(it, icons[it.pkg], launchApp, onMenu) }
                }
            }
        }
    }

    menu?.let { a ->
        AlertDialog(
            onDismissRequest = { menu = null },
            title = { Text(a.label) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(a.category + (if (a.system) " · system app" else ""), style = MaterialTheme.typography.bodySmall)
                    MenuAction("Open") { menu = null; launchApp(a) }
                    MenuAction(if (a.pinned) "Unpin" else "Pin to top") { menu = null; OrgStore.togglePin(ctx, a.pkg); version++ }
                    MenuAction(if (a.hidden) "Unhide" else "Hide from organizer") { menu = null; OrgStore.toggleHide(ctx, a.pkg); version++ }
                    MenuAction("Rename (only here)") { menu = null; edit = a to false }
                    MenuAction("Edit tags") { menu = null; edit = a to true }
                    MenuAction("Details") { menu = null; detail = a }
                    MenuAction("App info") { menu = null; OrgActions.info(ctx, a.pkg) }
                    MenuAction("Copy package name") { menu = null; OrgActions.copy(ctx, a.pkg) }
                    if (!a.system) MenuAction("Uninstall") { menu = null; OrgActions.uninstall(ctx, a.pkg) }
                }
            },
            confirmButton = { TextButton(onClick = { menu = null }) { Text("Close") } }
        )
    }
    edit?.let { (a, tags) ->
        var text by remember(a.pkg, tags) { mutableStateOf(if (tags) a.tags.joinToString(", ") else (OrgStore.name(ctx, a.pkg) ?: "")) }
        AlertDialog(
            onDismissRequest = { edit = null },
            title = { Text(if (tags) "Tags for ${a.label}" else "Rename ${a.label}") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text(if (tags) "comma separated" else "blank = original name") }) },
            confirmButton = { TextButton(onClick = { if (tags) OrgStore.setTags(ctx, a.pkg, text) else OrgStore.setName(ctx, a.pkg, text); version++; edit = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { edit = null }) { Text("Cancel") } }
        )
    }
    detail?.let { a ->
        val rows = remember(a.pkg, version) { OrgActions.details(ctx, a) }
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(a.label) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                rows.forEach { (k, v) -> Column { Text(k, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(v, style = MaterialTheme.typography.bodyMedium) } }
            } },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("Close") } }
        )
    }
}

@Composable
private fun MenuAction(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(text, Modifier.fillMaxWidth()) }
}
