package com.toolkit.privacy

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun a11yEnabled(ctx: android.content.Context): Boolean {
    val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    val cn = ComponentName(ctx, UrlAccessibilityService::class.java)
    return s.contains(cn.flattenToString(), true) || s.contains(cn.flattenToShortString(), true)
}

@Composable
fun SiteGuardScreen(initial: String?, onConsumed: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sp = remember { Prefs.sp(ctx) }
    var overlayOk by remember { mutableStateOf(SiteGuardService.canDraw(ctx)) }
    var a11yOk by remember { mutableStateOf(a11yEnabled(ctx)) }
    var running by remember { mutableStateOf(SiteGuardService.isRunning()) }
    var auto by remember { mutableStateOf(sp.getBoolean(Prefs.SG_AUTO, true)) }
    var live by remember { mutableStateOf(SiteGuardService.last) }
    var input by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<SiteVerdict?>(null) }
    var busy by remember { mutableStateOf(false) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        while (true) {
            overlayOk = SiteGuardService.canDraw(ctx); a11yOk = a11yEnabled(ctx)
            running = SiteGuardService.isRunning(); live = SiteGuardService.last
            delay(1000)
        }
    }

    fun check(u: String) {
        if (u.isBlank()) return
        busy = true
        scope.launch {
            val v = withContext(Dispatchers.IO) { SiteCheck.run(u.trim()) }
            result = v; busy = false
            if (SiteGuardService.isRunning()) SiteGuardService.push(v.url)
        }
    }

    LaunchedEffect(initial) {
        if (!initial.isNullOrBlank()) { input = initial; check(initial); onConsumed() }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
        item { Text("Site Guard", style = MaterialTheme.typography.headlineSmall) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Floating safety bubble", fontWeight = FontWeight.Bold)
                    Text("A round gauge that floats over any app. 1-100%: green is safe, yellow means be careful, red means stay away. Drag it anywhere, tap it for the reasons.", style = MaterialTheme.typography.bodySmall)
                    SwitchRow("Show the bubble", if (overlayOk) "Draws over other apps" else "Needs \"Display over other apps\"", running) { on ->
                        if (on) {
                            if (!overlayOk) ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            else {
                                if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                SiteGuardService.start(ctx); running = true
                            }
                        } else { SiteGuardService.stop(ctx); running = false }
                    }
                    PermRow("Display over other apps", "Lets the bubble float on top of your browser", overlayOk, "Open settings") {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Auto-detect the site you're on", fontWeight = FontWeight.Bold)
                    Text("Turn on the Site Guard accessibility service and the bubble re-scores every page you open in Chrome, Samsung Internet, Firefox, Brave, Edge, Opera and more. It reads only the browser's address bar, never page content, and nothing leaves your phone except the site checks themselves.", style = MaterialTheme.typography.bodySmall)
                    PermRow("Accessibility service", "Android 13+: if the switch is greyed out, open App info, tap the 3 dots, then \"Allow restricted settings\"", a11yOk, "Open settings") {
                        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    SwitchRow("Scan automatically", "Off = only scan links you share or paste", auto) { auto = it; sp.edit().putBoolean(Prefs.SG_AUTO, it).apply() }
                    Text("No accessibility? Share any link to Privacy Toolkit from your browser's Share menu, or paste it below.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        live?.let { v ->
            item { Text("Last site seen by the bubble", style = MaterialTheme.typography.titleSmall) }
            item { VerdictCard(v.host.ifEmpty { v.url }, v.label, v.score, v.findings.filter { it.delta != 0 }) }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Check a link now", fontWeight = FontWeight.Bold)
                    OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), label = { Text("Website or link") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { check(input) }, enabled = !busy && input.isNotBlank()) { Text(if (busy) "Scanning..." else "Check") }
                        OutlinedButton(onClick = { input = clipboardText(ctx).trim(); if (input.isNotBlank()) check(input) }) { Text("Paste & check") }
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        result?.let { v ->
            item { VerdictCard(v.host.ifEmpty { v.url }, v.label, v.score, v.findings.filter { it.delta != 0 || v.findings.size <= 2 },
                if (v.network) listOf("Live checks done") else listOf("Offline checks only")) }
        }
    }
}
