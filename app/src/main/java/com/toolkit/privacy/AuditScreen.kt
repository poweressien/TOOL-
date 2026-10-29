package com.toolkit.privacy

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppAudit(
    val label: String, val pkg: String, val system: Boolean, val risk: Int,
    val perms: List<String>, val trackers: List<String>, val flags: List<String>
)

private val PERM_RISK = mapOf(
    "android.permission.ACCESS_BACKGROUND_LOCATION" to ("Background location" to 20),
    "android.permission.ACCESS_FINE_LOCATION" to ("Precise location" to 10),
    "android.permission.ACCESS_COARSE_LOCATION" to ("Approx. location" to 4),
    "android.permission.RECORD_AUDIO" to ("Microphone" to 12),
    "android.permission.CAMERA" to ("Camera" to 10),
    "android.permission.READ_CONTACTS" to ("Contacts" to 8),
    "android.permission.READ_SMS" to ("Read SMS" to 15),
    "android.permission.RECEIVE_SMS" to ("Receive SMS" to 8),
    "android.permission.SEND_SMS" to ("Send SMS" to 10),
    "android.permission.READ_CALL_LOG" to ("Call log" to 12),
    "android.permission.READ_PHONE_STATE" to ("Phone state" to 4),
    "android.permission.READ_CALENDAR" to ("Calendar" to 3),
    "android.permission.BODY_SENSORS" to ("Body sensors" to 5),
    "android.permission.BLUETOOTH_SCAN" to ("Nearby devices" to 3)
)

private val DECLARED = mapOf(
    "android.permission.SYSTEM_ALERT_WINDOW" to ("Draws over other apps" to 10),
    "android.permission.REQUEST_INSTALL_PACKAGES" to ("Can install other apps" to 8),
    "android.permission.QUERY_ALL_PACKAGES" to ("Sees all installed apps" to 3)
)

private val TRACKERS = listOf(
    "com.google.android.gms.measurement" to "Google Analytics / Firebase",
    "com.google.firebase.crashlytics" to "Crashlytics",
    "com.google.android.gms.ads" to "Google Ads (AdMob)",
    "com.facebook.ads" to "Facebook Audience Network",
    "com.facebook" to "Facebook SDK",
    "com.appsflyer" to "AppsFlyer",
    "com.adjust.sdk" to "Adjust",
    "com.flurry" to "Flurry",
    "com.mixpanel" to "Mixpanel",
    "com.amplitude" to "Amplitude",
    "io.branch" to "Branch",
    "com.onesignal" to "OneSignal",
    "com.unity3d.ads" to "Unity Ads",
    "com.applovin" to "AppLovin",
    "com.ironsource" to "ironSource",
    "com.vungle" to "Vungle",
    "com.chartboost" to "Chartboost",
    "com.mopub" to "MoPub",
    "com.inmobi" to "InMobi",
    "com.startapp" to "StartApp",
    "com.tapjoy" to "Tapjoy",
    "com.yandex.metrica" to "Yandex Metrica",
    "com.segment" to "Segment",
    "com.braze" to "Braze",
    "com.appboy" to "Braze",
    "com.moengage" to "MoEngage",
    "com.clevertap" to "CleverTap"
)

suspend fun scanApps(ctx: Context, includeSystem: Boolean, onProgress: (Int, Int) -> Unit): List<AppAudit> =
    withContext(Dispatchers.Default) {
        val pm = ctx.packageManager
        val apps = pm.getInstalledApplications(0).filter {
            includeSystem || (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                (it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        }
        val out = ArrayList<AppAudit>()
        apps.forEachIndexed { idx, ai ->
            onProgress(idx + 1, apps.size)
            try {
                val pi = pm.getPackageInfo(
                    ai.packageName,
                    PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or
                        PackageManager.GET_ACTIVITIES or PackageManager.GET_PROVIDERS
                )
                var risk = 0
                val perms = mutableListOf<String>()
                val flags = mutableListOf<String>()
                val req = pi.requestedPermissions ?: emptyArray()
                val fl = pi.requestedPermissionsFlags
                req.forEachIndexed { i, perm ->
                    val granted = fl != null && i < fl.size && (fl[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                    val p = PERM_RISK[perm]
                    if (p != null && granted) { perms += p.first; risk += p.second }
                    val d = DECLARED[perm]
                    if (d != null) { flags += d.first; risk += d.second }
                }
                if (pi.services?.any { it.permission == "android.permission.BIND_ACCESSIBILITY_SERVICE" } == true) {
                    flags += "Accessibility service"; risk += 25
                }
                if (pi.receivers?.any { it.permission == "android.permission.BIND_DEVICE_ADMIN" } == true) {
                    flags += "Device admin"; risk += 20
                }
                val names = buildList {
                    pi.activities?.forEach { add(it.name) }
                    pi.services?.forEach { add(it.name) }
                    pi.receivers?.forEach { add(it.name) }
                    pi.providers?.forEach { add(it.name) }
                }
                val found = TRACKERS.filter { (prefix, _) -> names.any { it.startsWith(prefix) } }.map { it.second }.distinct()
                risk += minOf(found.size * 3, 15)
                out += AppAudit(
                    label = pm.getApplicationLabel(ai).toString(), pkg = ai.packageName,
                    system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    risk = minOf(risk, 100), perms = perms, trackers = found, flags = flags
                )
            } catch (_: Exception) {
            }
        }
        out.sortedByDescending { it.risk }
    }

private fun riskColor(r: Int) = when {
    r >= 60 -> Color(0xFFE53935)
    r >= 30 -> Color(0xFFFFB300)
    else -> Color(0xFF43A047)
}

private fun riskLabel(r: Int) = when {
    r >= 60 -> "High"
    r >= 30 -> "Medium"
    else -> "Low"
}

@Composable
fun AuditScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<AppAudit>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf(false) }
    var prog by remember { mutableFloatStateOf(0f) }
    var includeSystem by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    fun run() {
        scope.launch {
            scanning = true
            apps = scanApps(ctx, includeSystem) { d, t -> prog = d / t.toFloat() }
            scanning = false
            scanned = true
        }
    }

    val shown = apps.filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 14.dp)
    ) {
        item {
            Text("App privacy auditor", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Scores every installed app by the sensitive permissions it holds, risky capabilities it declares, and ad/analytics SDKs found inside it.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(enabled = !scanning, onClick = { run() }) { Text(if (scanning) "Scanning…" else if (scanned) "Rescan" else "Scan apps") }
                Switch(checked = includeSystem, onCheckedChange = { includeSystem = it }, enabled = !scanning)
                Text("Include system apps", style = MaterialTheme.typography.bodySmall)
            }
            if (scanning) LinearProgressIndicator(progress = { prog }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        if (scanned) {
            item {
                val high = apps.count { it.risk >= 60 }
                val trk = apps.sumOf { it.trackers.size }
                val triple = apps.count { "Microphone" in it.perms && "Camera" in it.perms && "Precise location" in it.perms }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${apps.size} apps scanned", style = MaterialTheme.typography.titleMedium)
                        Text("High risk: $high", color = riskColor(70), fontWeight = FontWeight.Bold)
                        Text("Tracker SDKs found: $trk")
                        Text("Apps with mic + camera + precise location: $triple")
                    }
                }
            }
            item {
                OutlinedTextField(query, { query = it }, label = { Text("Search apps") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            items(shown, key = { it.pkg }) { app -> AppRow(app) }
        }
    }
}

@Composable
private fun AppRow(app: AppAudit) {
    val ctx = LocalContext.current
    var open by remember(app.pkg) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable { open = !open }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(riskColor(app.risk)),
                    contentAlignment = Alignment.Center
                ) { Text(app.label.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(app.label, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(
                        "${app.perms.size} sensitive permissions · ${app.trackers.size} trackers",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${app.risk}", fontWeight = FontWeight.Bold, color = riskColor(app.risk))
                    Text(riskLabel(app.risk), style = MaterialTheme.typography.bodySmall, color = riskColor(app.risk))
                }
            }
            if (open) {
                Text(app.pkg, style = MaterialTheme.typography.bodySmall)
                if (app.perms.isNotEmpty()) Text("Granted: " + app.perms.joinToString(", "))
                if (app.flags.isNotEmpty()) Text("Capabilities: " + app.flags.joinToString(", "))
                if (app.trackers.isNotEmpty()) Text("Trackers: " + app.trackers.joinToString(", "))
                OutlinedButton(onClick = {
                    ctx.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }) { Text("Review / revoke permissions") }
            }
        }
    }
}
