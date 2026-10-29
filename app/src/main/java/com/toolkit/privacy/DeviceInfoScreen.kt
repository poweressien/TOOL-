package com.toolkit.privacy

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.view.Display
import android.webkit.WebSettings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import java.net.HttpURLConnection
import org.json.JSONObject
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class Section(val title: String, val rows: List<Pair<String, String>>)

private fun fmtBytes(b: Long): String {
    val gb = b / 1073741824.0
    return if (gb >= 1) "%.2f GB".format(gb) else "%.0f MB".format(b / 1048576.0)
}

fun collectLocal(ctx: Context): List<Section> {
    val out = mutableListOf<Section>()

    out += Section("Device", listOf(
        "Manufacturer" to Build.MANUFACTURER, "Brand" to Build.BRAND, "Model" to Build.MODEL,
        "Device" to Build.DEVICE, "Product" to Build.PRODUCT, "Hardware" to Build.HARDWARE, "Board" to Build.BOARD
    ))

    out += Section("Operating system", listOf(
        "Android" to Build.VERSION.RELEASE, "API level" to Build.VERSION.SDK_INT.toString(),
        "Security patch" to (Build.VERSION.SECURITY_PATCH ?: "?"), "Build ID" to Build.ID,
        "Kernel" to (System.getProperty("os.version") ?: "?"), "Fingerprint" to Build.FINGERPRINT
    ))

    val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    val st = StatFs(Environment.getDataDirectory().path)
    out += Section("CPU, memory, storage", listOf(
        "ABIs" to Build.SUPPORTED_ABIS.joinToString(), "Cores" to Runtime.getRuntime().availableProcessors().toString(),
        "RAM total" to fmtBytes(mi.totalMem), "RAM available" to fmtBytes(mi.availMem),
        "Storage total" to fmtBytes(st.totalBytes), "Storage free" to fmtBytes(st.availableBytes),
        "OpenGL ES" to am.deviceConfigurationInfo.glEsVersion
    ))

    val dm = ctx.resources.displayMetrics
    val disp = (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
    val big = maxOf(dm.widthPixels, dm.heightPixels).toFloat()
    val small = minOf(dm.widthPixels, dm.heightPixels).toFloat()
    out += Section("Screen", listOf(
        "Resolution" to "${dm.widthPixels} × ${dm.heightPixels} px",
        "Size" to "${(dm.widthPixels / dm.density).toInt()} × ${(dm.heightPixels / dm.density).toInt()} dp",
        "Density" to "%.2f (%d dpi)".format(dm.density, dm.densityDpi),
        "Refresh rate" to "%.0f Hz".format(disp?.refreshRate ?: 0f),
        "Aspect ratio" to "%.2f".format(big / small),
        "Orientation" to if (ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "Landscape" else "Portrait"
    ))

    val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    if (b != null) {
        val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = when (b.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            BatteryManager.BATTERY_STATUS_FULL -> "Full"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            else -> "Not charging"
        }
        val plug = when (b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> "No"
        }
        out += Section("Battery", listOf(
            "Level" to "$level%", "Status" to status, "Plugged in" to plug,
            "Temperature" to "%.1f °C".format(b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f)
        ))
    }

    val locales = ctx.resources.configuration.locales
    val tz = TimeZone.getDefault()
    out += Section("Language & time", listOf(
        "Languages" to (0 until locales.size()).joinToString { locales[it].toLanguageTag() },
        "Time zone" to tz.id,
        "Local time" to SimpleDateFormat("EEE, MMM d yyyy HH:mm:ss Z", Locale.US).format(Date())
    ))

    val pm = ctx.packageManager
    fun f(n: String) = if (pm.hasSystemFeature(n)) "Yes" else "No"
    val cams = try { (ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager).cameraIdList.size.toString() } catch (e: Exception) { "?" }
    val sensors = (ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getSensorList(Sensor.TYPE_ALL)
    out += Section("Hardware", listOf(
        "Cameras" to cams, "Bluetooth" to f("android.hardware.bluetooth"), "NFC" to f("android.hardware.nfc"),
        "Fingerprint" to f("android.hardware.fingerprint"), "Telephony" to f("android.hardware.telephony"),
        "Wi-Fi" to f("android.hardware.wifi"), "GPS" to f("android.hardware.location.gps"),
        "Touchscreen" to f("android.hardware.touchscreen"), "Sensors" to "${sensors.size}"
    ))

    val dev = Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
    out += Section("Software", listOf(
        "User agent" to WebSettings.getDefaultUserAgent(ctx),
        "Developer options" to if (dev) "Enabled" else "Disabled"
    ))

    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val net = cm.activeNetwork
    val caps = cm.getNetworkCapabilities(net)
    val lp = cm.getLinkProperties(net)
    val types = buildList {
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("Wi-Fi")
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("Cellular")
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("Ethernet")
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("VPN")
    }
    out += Section("Connection (local)", listOf(
        "Type" to (types.joinToString().ifEmpty { "None / unknown" }),
        "VPN active" to if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) "Yes" else "No",
        "Internet validated" to if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) "Yes" else "No",
        "Metered" to if (cm.isActiveNetworkMetered) "Yes" else "No",
        "Local IPs" to (lp?.linkAddresses?.joinToString { it.address.hostAddress ?: "" } ?: "Unknown"),
        "DNS servers" to (lp?.dnsServers?.joinToString { it.hostAddress ?: "" } ?: "Unknown"),
        "Interface" to (lp?.interfaceName ?: "Unknown")
    ))
    return out
}

suspend fun collectPublic(): Section = withContext(Dispatchers.IO) {
    try {
        val txt = URL("https://ipwho.is/").openConnection().apply {
            connectTimeout = 7000
            readTimeout = 7000
        }.getInputStream().bufferedReader().use { it.readText() }
        val j = JSONObject(txt)
        val c = j.optJSONObject("connection")
        Section("Public IP & location (as seen by servers)", listOf(
            "IP address" to j.optString("ip", "?"),
            "IP version" to j.optString("type", "?"),
            "Country" to "${j.optString("country", "?")} (${j.optString("country_code", "?")})",
            "Region" to j.optString("region", "?"),
            "City" to j.optString("city", "?"),
            "Latitude / Longitude" to "${j.optDouble("latitude")}, ${j.optDouble("longitude")}",
            "ISP" to (c?.optString("isp") ?: "?"),
            "Organization" to (c?.optString("org") ?: "?"),
            "ASN" to (c?.optString("asn") ?: "?"),
            "IP time zone" to (j.optJSONObject("timezone")?.optString("id") ?: "?")
        ))
    } catch (e: Exception) {
        Section("Public IP & location (as seen by servers)", listOf("Lookup failed" to (e.message ?: "no connection")))
    }
}

@Composable
private fun rememberAccel(ctx: Context): State<String> {
    val state = remember { mutableStateOf("Not available") }
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                state.value = "x %.2f   y %.2f   z %.2f  m/s²".format(e.values[0], e.values[1], e.values[2])
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (sensor != null) sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(l) }
    }
    return state
}


suspend fun runSpeedTest(onProgress: (String) -> Unit): String = withContext(Dispatchers.IO) {
    try {
        var best = Long.MAX_VALUE
        repeat(3) {
            val t0 = System.nanoTime()
            val c = URL("https://www.cloudflare.com/cdn-cgi/trace").openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 5000
            c.inputStream.use { it.readBytes() }
            c.disconnect()
            best = minOf(best, (System.nanoTime() - t0) / 1_000_000)
        }
        onProgress("Ping $best ms · testing download…")
        val t0 = System.nanoTime()
        var total = 0L
        val c = URL("https://speed.cloudflare.com/__down?bytes=15000000").openConnection().apply {
            connectTimeout = 8000
            readTimeout = 10000
        }
        c.getInputStream().use { ins ->
            val buf = ByteArray(65536)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                total += n
                val sec = (System.nanoTime() - t0) / 1e9
                if (sec > 0.3) onProgress("Ping $best ms · ↓ %.1f Mbps".format(total * 8 / 1e6 / sec))
            }
        }
        val sec = (System.nanoTime() - t0) / 1e9
        "Ping $best ms · ↓ %.1f Mbps".format(total * 8 / 1e6 / sec)
    } catch (e: Exception) {
        "Test failed: ${e.message}"
    }
}

private enum class Level(val label: String, val color: Color) {
    HIGH("High", Color(0xFFE53935)), MEDIUM("Medium", Color(0xFFFFB300)), LOW("Low", Color(0xFF43A047))
}

@Composable
private fun ExposureCard(local: List<Section>, pub: Section?) {
    val vpnOn = local.firstOrNull { it.title.startsWith("Connection") }
        ?.rows?.firstOrNull { it.first == "VPN active" }?.second == "Yes"
    fun pv(k: String) = pub?.rows?.firstOrNull { it.first == k }?.second ?: "…"
    val ipLevel = if (vpnOn) Level.LOW else Level.HIGH
    val items = listOf(
        Triple("IP address", ipLevel, "${pv("IP address")} · ${pv("City")}, ${pv("Country")}"),
        Triple("ISP / network owner", ipLevel, pv("ISP")),
        Triple("Device model & OS", Level.MEDIUM, "${Build.MODEL} · Android ${Build.VERSION.RELEASE} (in user agent)"),
        Triple("Time zone & language", Level.MEDIUM, "${TimeZone.getDefault().id} · ${Locale.getDefault().toLanguageTag()}"),
        Triple("Screen & hardware", Level.LOW, "Resolution and cores are visible to apps")
    )
    val pts = items.sumOf { when (it.second) { Level.LOW -> 2; Level.MEDIUM -> 1; Level.HIGH -> 0 } }
    val pct = pts * 100 / (items.size * 2)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("What websites and apps can see", style = MaterialTheme.typography.titleMedium)
            Text("Privacy score: $pct / 100" + if (!vpnOn) "  ·  connect the VPN tab to hide your IP location" else "  ·  VPN is on",
                style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { pct / 100f }, Modifier.fillMaxWidth())
            items.forEach { (name, lvl, detail) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(name, fontWeight = FontWeight.Medium)
                        Text(detail, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(lvl.label, color = lvl.color, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun DeviceInfoScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var local by remember { mutableStateOf(collectLocal(ctx)) }
    var pub by remember { mutableStateOf<Section?>(null) }
    var speed by remember { mutableStateOf("Not run yet") }
    var testing by remember { mutableStateOf(false) }
    val accel by rememberAccel(ctx)

    fun refresh() {
        local = collectLocal(ctx)
        pub = null
        scope.launch { pub = collectPublic() }
    }
    LaunchedEffect(Unit) { pub = collectPublic() }

    val all = buildList {
        add(pub ?: Section("Public IP & location (as seen by servers)", listOf("Status" to "Loading…")))
        addAll(local)
        add(Section("Live motion", listOf("Accelerometer" to accel)))
    }
    val report = all.joinToString("\n\n") { s -> s.title + "\n" + s.rows.joinToString("\n") { "${it.first}: ${it.second}" } }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        item {
            Text("Device info", style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = { refresh() }) { Text("Refresh") }
                OutlinedButton(onClick = {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("device info", report))
                }) { Text("Copy") }
                OutlinedButton(onClick = {
                    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report)
                    ctx.startActivity(Intent.createChooser(i, "Share report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Share") }
            }
        }
        item { ExposureCard(local, pub) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Network test", style = MaterialTheme.typography.titleMedium)
                    Text(speed, fontWeight = FontWeight.Medium)
                    Button(enabled = !testing, onClick = {
                        testing = true
                        scope.launch {
                            speed = "Testing…"
                            speed = runSpeedTest { speed = it }
                            testing = false
                        }
                    }) { Text(if (testing) "Testing…" else "Run speed test") }
                }
            }
        }
        items(all) { s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(s.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    s.rows.forEach { (k, v) ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            Text(k, Modifier.weight(0.38f), style = MaterialTheme.typography.bodySmall)
                            Text(v, Modifier.weight(0.62f), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
