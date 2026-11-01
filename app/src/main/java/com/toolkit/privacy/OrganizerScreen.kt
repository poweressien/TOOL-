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
import androidx.compose.foundation.lazy.grid.GridCells
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppCell(app: AppEntry, onMenu: (AppEntry) -> Unit) {
    val ctx = LocalContext.current
    val icon = remember(app.pkg) { try { ctx.packageManager.getApplicationIcon(app.pkg).toBitmap(112, 112).asImageBitmap() } catch (e: Exception) { null } }
    Column(
        Modifier.fillMaxWidth().combinedClickable(
            onClick = { ctx.packageManager.getLaunchIntentForPackage(app.pkg)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
            onLongClick = { onMenu(app) }
        ).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (icon != null) Image(icon, null, Modifier.size(48.dp)) else Box(Modifier.size(48.dp))
        Text(app.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
fun OrganizerScreen(asHome: Boolean = false) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var mode by remember { mutableStateOf("Folders") }
    var query by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf<AppEntry?>(null) }
    var homeOn by remember { mutableStateOf(false) }
    val homeCn = remember { ComponentName(ctx, HomeActivity::class.java) }
    val usage = MonitorChecks.hasUsageAccess(ctx)

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadApps(ctx) }
        loading = false
        homeOn = ctx.packageManager.getComponentEnabledSetting(homeCn) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }

    val now = System.currentTimeMillis()
    val shown = remember(apps, query) { if (query.isBlank()) apps else apps.filter { it.label.contains(query, true) } }

    Column(Modifier.fillMaxSize().then(if (asHome) Modifier.statusBarsPadding() else Modifier)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (asHome) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Home", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { ctx.startActivity(Intent(ctx, MainActivity::class.java)) }) { Text("🧰 Toolkit") }
            } else Text("Home Organizer", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search ${apps.size} apps") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Folders", "A–Z", "Most used", "Unused").forEach { m -> FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m) }) }
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyVerticalGrid(GridCells.Adaptive(76.dp), Modifier.fillMaxSize().padding(horizontal = 10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (!asHome) item(span = { GridItemSpan(maxLineSpan) }) {
                Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Android doesn't let one app move icons around inside another launcher. So this sorts every app into folders (chat, money, social, games...) and you can make it your home screen.", style = MaterialTheme.typography.bodySmall)
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
            if ((mode == "Most used" || mode == "Unused") && !usage) item(span = { GridItemSpan(maxLineSpan) }) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Needs Usage access to know which apps you actually open.", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Open Usage access") }
                    }
                }
            }
            when (mode) {
                "Folders" -> AppCategories.folders(shown).forEach { f ->
                    item(span = { GridItemSpan(maxLineSpan) }) { Text("${f.name}  ·  ${f.apps.size}", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)) }
                    items(f.apps, key = { f.name + it.pkg }) { AppCell(it) { a -> menu = a } }
                }
                else -> {
                    val list = when (mode) {
                        "A–Z" -> AppCategories.alphabetical(shown)
                        "Most used" -> AppCategories.mostUsed(shown)
                        else -> AppCategories.unused(shown, now, 30)
                    }
                    if (mode == "Unused") item(span = { GridItemSpan(maxLineSpan) }) { Text("Not opened in 30 days. Long-press an app to uninstall and free space.", style = MaterialTheme.typography.bodySmall) }
                    items(list, key = { it.pkg }) { AppCell(it) { a -> menu = a } }
                }
            }
        }
    }

    menu?.let { a ->
        AlertDialog(
            onDismissRequest = { menu = null },
            title = { Text(a.label) },
            text = { Text(AppCategories.categorize(a) + (if (a.system) " · system app" else "")) },
            confirmButton = {
                TextButton(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); menu = null
                }) { Text("App info") }
            },
            dismissButton = {
                if (!a.system) TextButton(onClick = {
                    ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); menu = null
                }) { Text("Uninstall") }
            }
        )
    }
}
