package com.toolkit.privacy

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val kbDec = KeyboardOptions(keyboardType = KeyboardType.Decimal)
private fun hm(t: Long) = SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(t))

// ------------------------------------------------------------------------------------------------ SDR

/** Minimal rtl_tcp client: works with an RTL-SDR dongle served by a driver app on this phone or an rtl_tcp server on the network. */
class SdrClient(private val host: String, private val port: Int) {
    private var sock: Socket? = null
    private var out: OutputStream? = null
    @Volatile var running = false
    var tuner = 0; var gains = 0

    fun connect(): String? = try {
        val s = Socket(); s.connect(InetSocketAddress(host, port), 3000); s.soTimeout = 4000; sock = s; out = s.getOutputStream()
        val h = ByteArray(12); DataInputStream(s.getInputStream()).readFully(h)
        if (String(h, 0, 4) != "RTL0") "Connected, but it does not look like an rtl_tcp server." else { tuner = h[7].toInt(); gains = h[11].toInt(); null }
    } catch (e: Exception) { "Cannot connect to $host:$port (${e.message})" }

    private fun cmd(c: Int, v: Int) { try { out?.write(byteArrayOf(c.toByte(), (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())); out?.flush() } catch (e: Exception) { } }
    fun setFreq(hz: Long) = cmd(0x01, hz.toInt())
    fun setRate(sps: Int) = cmd(0x02, sps)
    fun setGain(tenthsDb: Int?) { if (tenthsDb == null) { cmd(0x03, 0); cmd(0x08, 1) } else { cmd(0x03, 1); cmd(0x04, tenthsDb) } }

    fun stream(n: Int, onSpec: (DoubleArray) -> Unit) {
        val inp = DataInputStream(sock!!.getInputStream()); val buf = ByteArray(2 * n); val recent = ArrayDeque<DoubleArray>()
        running = true
        try {
            while (running) {
                inp.readFully(buf)
                recent.addLast(Dsp.spectrum(buf, 0, n)); while (recent.size > 3) recent.removeFirst()
                onSpec(Dsp.average(recent.toList()))
            }
        } catch (e: Exception) { }
        running = false
    }

    fun close() { running = false; try { sock?.close() } catch (e: Exception) { } }
}

private fun heat(db: Double): Int {
    val t = ((db + 100) / 70.0).coerceIn(0.0, 1.0)
    val r = (255 * ((t - 0.5) * 2).coerceIn(0.0, 1.0)).toInt(); val g = (255 * (if (t < 0.5) t * 2 else (1 - (t - 0.5) * 1.2)).coerceIn(0.0, 1.0)).toInt(); val b = (255 * (1 - t * 1.4).coerceIn(0.15, 1.0)).toInt()
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

@Composable
fun RadioSdrScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val usb = remember { ctx.getSystemService(Context.USB_SERVICE) as UsbManager }
    val known = mapOf((0x0BDA to 0x2838) to "RTL-SDR (RTL2832U)", (0x0BDA to 0x2832) to "RTL-SDR (RTL2832U)", (0x1D50 to 0x6089) to "HackRF One", (0x1D50 to 0x60A1) to "Airspy", (0x1DF7 to 0x2500) to "SDRplay RSP")
    val devices = remember { usb.deviceList.values.map { (known[it.vendorId to it.productId] ?: (it.productName ?: "USB device")) to "%04X:%04X".format(it.vendorId, it.productId) } }
    var host by remember { mutableStateOf(Prefs.sp(ctx).getString(Prefs.ENV_RTL_HOST, "127.0.0.1") ?: "127.0.0.1") }
    var port by remember { mutableStateOf("1234") }
    var freq by remember { mutableStateOf("98.0") }
    var rate by remember { mutableStateOf("2.048") }
    var gain by remember { mutableStateOf("Auto") }
    var status by remember { mutableStateOf("") }
    var client by remember { mutableStateOf<SdrClient?>(null) }
    var spec by remember { mutableStateOf<DoubleArray?>(null) }
    val W = 256; val H = 64
    val pixels = remember { IntArray(W * H) { 0xFF000010.toInt() } }
    var wf by remember { mutableStateOf<Bitmap?>(null) }
    DisposableEffect(Unit) { onDispose { client?.close() } }

    fun tune(c: SdrClient?) {
        c ?: return
        c.setRate(((rate.toDoubleOrNull() ?: 2.048) * 1e6).toInt()); c.setFreq(((freq.toDoubleOrNull() ?: 98.0) * 1e6).toLong())
        c.setGain(when (gain) { "Auto" -> null; "Low" -> 150; "Mid" -> 300; else -> 450 })
    }

    fun connect() {
        client?.close(); status = "Connecting…"; spec = null
        Prefs.sp(ctx).edit().putString(Prefs.ENV_RTL_HOST, host).apply()
        val c = SdrClient(host.trim(), port.toIntOrNull() ?: 1234)
        scope.launch(Dispatchers.IO) {
            val err = c.connect()
            if (err != null) { status = err; return@launch }
            client = c; status = "Streaming (tuner ${c.tuner})"; tune(c)
            var frame = 0
            c.stream(1024) { s ->
                val row = IntArray(W) { i -> var m = -200.0; for (k in i * 4 until i * 4 + 4) if (s[k] > m) m = s[k]; heat(m) }
                System.arraycopy(pixels, 0, pixels, W, W * (H - 1)); System.arraycopy(row, 0, pixels, 0, W)
                frame++
                if (frame % 3 == 0) { spec = s; wf = Bitmap.createBitmap(pixels.copyOf(), W, H, Bitmap.Config.ARGB_8888) }
            }
            if (client === c) status = "Stream ended"
        }
    }

    val centre = freq.toDoubleOrNull() ?: 98.0; val bw = rate.toDoubleOrNull() ?: 2.048
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
        item { Text("SDR spectrum", style = MaterialTheme.typography.headlineSmall) }
        item {
            Section("External SDR hardware", "A phone's own radios cannot see FM, airband or ISM bands. A USB SDR dongle can. Plug one in with a USB-OTG cable.") {
                if (devices.isEmpty()) Text("No USB devices attached.", style = MaterialTheme.typography.bodySmall)
                devices.forEach { (n, id) -> Text("🔌 $n  ($id)", style = MaterialTheme.typography.bodyMedium) }
                Hint("Android lets one app at a time own the USB dongle. Start an rtl_tcp server app for it (any RTL-SDR driver app that offers rtl_tcp), or run `rtl_tcp -a 0.0.0.0` on a Raspberry Pi / PC on your Wi-Fi, then connect below.")
            }
        }
        item {
            Section("Connect to rtl_tcp") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(host, { host = it }, Modifier.weight(1f), label = { Text("Host / IP") }, singleLine = true)
                    OutlinedTextField(port, { port = it.filter { c -> c.isDigit() } }, Modifier.width(90.dp), label = { Text("Port") }, singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { connect() }) { Text(if (client?.running == true) "Reconnect" else "Connect") }
                    OutlinedButton(onClick = { client?.close(); client = null; status = "Disconnected" }) { Text("Disconnect") }
                }
                if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall, color = if (status.startsWith("Cannot")) Bad else Color.Unspecified)
            }
        }
        item {
            Section("Tune") {
                OutlinedTextField(freq, { freq = it; tune(client) }, Modifier.fillMaxWidth(), label = { Text("Centre frequency (MHz)") }, singleLine = true, keyboardOptions = kbDec)
                ChipRow(listOf("FM 98.0", "NOAA 137.1", "Airband 121.5", "ISM 433.92", "ISM 868.0", "ADS-B 1090", "GSM 947"), "") { freq = it.substringAfter(' '); tune(client) }
                Text("Sample rate (MHz) = visible width", style = MaterialTheme.typography.bodySmall)
                ChipRow(listOf("1.024", "2.048", "2.4"), rate) { rate = it; tune(client) }
                Text("Gain", style = MaterialTheme.typography.bodySmall)
                ChipRow(listOf("Auto", "Low", "Mid", "High"), gain) { gain = it; tune(client) }
            }
        }
        item {
            Section("Live spectrum", spec?.let { s -> val p = Dsp.peak(s); "Peak ${fmtMhz2(centre + (p - 512) * bw / 1024)} at ${"%.0f".format(s[p])} dB" } ?: "Waiting for data") {
                spec?.let { s ->
                    LineChart(List(256) { i -> var m = -200.0; for (k in i * 4 until i * 4 + 4) if (s[k] > m) m = s[k]; m.toFloat() }, Good, 130, lo = -100f, hi = -10f)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(fmtMhz2(centre - bw / 2), style = MaterialTheme.typography.labelSmall); Text(fmtMhz2(centre), style = MaterialTheme.typography.labelSmall); Text(fmtMhz2(centre + bw / 2), style = MaterialTheme.typography.labelSmall)
                    }
                }
                wf?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().height(160.dp), contentScale = ContentScale.FillBounds) }
                Hint("Waterfall: newest at the top, hotter colour = stronger signal. Steady vertical lines are transmitters; the centre spike is the dongle's own DC offset.")
            }
        }
    }
}

private fun fmtMhz2(m: Double) = if (m >= 1000) "%.3f GHz".format(m / 1000) else "%.3f MHz".format(m)

// ------------------------------------------------------------------------------------------------ environment

@Composable
fun RadioEnvScreen() {
    val ctx = LocalContext.current
    val sensors = remember { EnvSensors(ctx) }
    var tick by remember { mutableStateOf(0) }
    var outdoor by remember { mutableStateOf<EnvStore.Outdoor?>(null) }
    DisposableEffect(Unit) { sensors.start(); onDispose { if (!EnvService.running()) sensors.stop() } }
    LaunchedEffect(Unit) {
        while (true) {
            EnvStore.batteryTempC(ctx)?.let { EnvLive.pushBatt(it) }
            tick++
            if (tick % 120 == 1) EnvStore.place(ctx)?.let { (la, lo) -> outdoor = withContext(Dispatchers.IO) { EnvStore.fetchOutdoor(la, lo) } }
            delay(2500)
        }
    }
    val t = EnvLive.tempC; val h = EnvLive.hum; val p = EnvLive.pres; val lux = EnvLive.light
    val tHist = EnvLive.snapshot(EnvLive.tempHist); val pHist = EnvLive.snapshot(EnvLive.presHist); val bHist = EnvLive.snapshot(EnvLive.battHist)
    val batt = EnvStore.batteryTempC(ctx)
    val rate = EnvMath.ratePerMin(tHist)
    val z = EnvLive.tempZ
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
        item { Text("Environment sensors", style = MaterialTheme.typography.headlineSmall) }
        item {
            Section("Sensors on this phone") {
                sensors.have.forEach { (n, ok) -> Text("${if (ok) "✅" else "⬜"} $n", color = if (ok) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant) }
                Text("🔋 Battery temperature ${if (batt != null) "✅" else "⬜"}")
                if (sensors.have["Ambient temperature"] != true) Hint("Most phones have no air-temperature sensor. Battery temperature is used as a stand-in (it runs warmer than the air), and outdoor weather can be fetched in the Air tab.")
            }
        }
        item {
            Section("Live readings") {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    BigStat("air temp", t?.let { "%.1f °C".format(it) } ?: "–"); BigStat("humidity", h?.let { "%.0f %%".format(it) } ?: "–"); BigStat("pressure", p?.let { "%.1f hPa".format(it) } ?: "–")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    BigStat("battery", batt?.let { "%.1f °C".format(it) } ?: "–", if ((batt ?: 0.0) >= 43) Bad else Color.Unspecified); BigStat("light", lux?.let { "%.0f lux".format(it) } ?: "–")
                }
                if (t != null && h != null) { KV("Dew point", "%.1f °C".format(EnvMath.dewPoint(t, h))); KV("Feels like (heat index)", "%.1f °C".format(EnvMath.heatIndexC(t, h))) }
                if (p != null) { KV("Altitude (from pressure)", "%.0f m".format(EnvMath.altitudeM(p))); KV("Weather trend", EnvMath.pressureTrend(pHist.filter { System.currentTimeMillis() - it.first < 3 * 3600_000L })) }
                lux?.let { KV("Lighting", when { it < 10 -> "Dark"; it < 100 -> "Dim"; it < 500 -> "Indoor"; it < 5000 -> "Bright indoor / shade"; else -> "Daylight" }) }
            }
        }
        item {
            Section("Unusual temperature changes", "Learns what is normal for where you are and flags jumps. Needs a few minutes of readings.") {
                val src = if (tHist.isNotEmpty()) tHist else bHist
                if (src.size < 3) Hint("Collecting readings… keep this screen open or start the background watch in the Alerts tab.")
                else {
                    LineChart(src.takeLast(120).map { it.second.toFloat() }, Warn, 100)
                    val r = if (tHist.isNotEmpty()) rate else EnvMath.ratePerMin(bHist)
                    Text("Change: ${"%+.2f".format(r)} °C per minute", fontWeight = FontWeight.Medium)
                    val anomalous = (z != null && kotlin.math.abs(z) > 3.5) || kotlin.math.abs(r) >= 1.5
                    Text(if (anomalous) "⚠ Unusual: ${if (r > 0) "rapid warming" else "rapid cooling"}${z?.let { " (${"%.1f".format(it)}σ from normal)" } ?: ""}" else "✓ Normal", color = if (anomalous) Bad else Good, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (pHist.size > 2) item { Section("Pressure") { LineChart(pHist.takeLast(120).map { it.second.toFloat() }, Info, 90) } }
        if (bHist.size > 2) item { Section("Battery temperature") { LineChart(bHist.takeLast(120).map { it.second.toFloat() }, Bad, 80) } }
        outdoor?.let { o ->
            item {
                Section("Outdoors now (internet)") {
                    o.tempC?.let { KV("Temperature", "%.1f °C".format(it)) }; o.feels?.let { KV("Feels like", "%.1f °C".format(it)) }; o.hum?.let { KV("Humidity", "%.0f %%".format(it)) }
                    o.pres?.let { KV("Pressure", "%.1f hPa".format(it)) }; o.wind?.let { KV("Wind", "%.0f km/h".format(it)) }
                    if (t != null && o.tempC != null) Hint("Phone sensor vs outdoors: ${"%+.1f".format(t - o.tempC)} °C")
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ noise

@Composable
fun RadioNoiseScreen() {
    val ctx = LocalContext.current
    val sp = remember { Prefs.sp(ctx) }
    var mic by remember { mutableStateOf(EnvService.hasMic(ctx)) }
    val permL = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { mic = it; if (it) EnvService.start(ctx) }
    var running by remember { mutableStateOf(EnvService.running()) }
    var db by remember { mutableStateOf(0.0) }
    var hist by remember { mutableStateOf<List<Float>>(emptyList()) }
    var limit by remember { mutableStateOf(sp.getFloat(Prefs.ENV_LIMIT_DB, 85f).toInt().toString()) }
    var sustain by remember { mutableStateOf(sp.getInt(Prefs.ENV_SUSTAIN, 10).toString()) }
    var offset by remember { mutableStateOf(sp.getFloat(Prefs.ENV_OFFSET, 90f)) }
    var refDb by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while (true) { running = EnvService.running(); db = EnvService.liveDb; hist = synchronized(EnvService.history) { EnvService.history.map { it.toFloat() } }; delay(500) } }
    val col = when { db >= 100 -> Bad; db >= 85 -> Color(0xFFFB923C); db >= 70 -> Warn; else -> Good }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
        item { Text("Sound level", style = MaterialTheme.typography.headlineSmall) }
        item {
            Section("Noise monitor", "Measures loudness with the microphone and warns about aggressive noise. Sound is analysed live and thrown away: never recorded, never sent.") {
                if (!mic) Button(onClick = { permL.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Allow microphone") }
                else Button(onClick = { if (running) EnvService.stop(ctx) else EnvService.start(ctx); running = !running }) { Text(if (running) "■ Stop monitoring" else "▶ Start monitoring") }
                if (running) {
                    Text("${"%.0f".format(db)} dB", style = MaterialTheme.typography.displayMedium, color = col, fontWeight = FontWeight.Bold)
                    Text(NoiseMath.label(db), fontWeight = FontWeight.Medium)
                    PercentBar(((db - 20) / 100 * 100).toInt().coerceIn(0, 100), col, 12)
                    if (hist.size > 2) LineChart(hist, col, 90, lo = 20f, hi = 120f)
                    Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) { BigStat("average (Leq)", "%.0f dB".format(EnvService.leq)); BigStat("peak", "%.0f dB".format(EnvService.peak)); BigStat("daily dose", "%.0f %%".format(EnvService.dose * 100), if (EnvService.dose >= 1) Bad else Color.Unspecified) }
                    if (db >= 70) Hint("At ${"%.0f".format(db)} dB, safe listening time is about ${"%.0f".format(NoiseMath.safeMinutes(db))} minutes a day.")
                }
            }
        }
        item {
            Section("Alert rules", "An alert fires when the average level over the time window stays at or above the limit, or on a sudden burst 25 dB above normal.") {
                Text("Limit (dB)", style = MaterialTheme.typography.bodySmall)
                ChipRow(listOf("70", "75", "80", "85", "90", "95"), limit) { limit = it; sp.edit().putFloat(Prefs.ENV_LIMIT_DB, it.toFloat()).apply() }
                Text("Sustained for (seconds)", style = MaterialTheme.typography.bodySmall)
                ChipRow(listOf("5", "10", "20", "30", "60"), sustain) { sustain = it; sp.edit().putInt(Prefs.ENV_SUSTAIN, it.toInt()).apply() }
                if (running) Hint("Restart monitoring to apply new rules.")
            }
        }
        item {
            Section("Calibration", "Phone microphones are not lab instruments (±5–10 dB). Match a reference sound-level meter for better accuracy.") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(refDb, { refDb = it }, Modifier.weight(1f), label = { Text("Reference meter reads (dB)") }, singleLine = true, keyboardOptions = kbDec)
                    Button(enabled = running && db > 0 && refDb.toDoubleOrNull() != null, onClick = { offset = (offset + (refDb.toDouble() - db)).toFloat().coerceIn(40f, 140f); sp.edit().putFloat(Prefs.ENV_OFFSET, offset).apply() }) { Text("Calibrate") }
                }
                Hint("Offset now ${"%.1f".format(offset)}. Hold both devices side by side while it runs.")
                OutlinedButton(onClick = { offset = 90f; sp.edit().putFloat(Prefs.ENV_OFFSET, 90f).apply() }) { Text("Reset") }
            }
        }
        item {
            Section("Reference levels") {
                listOf("30 dB" to "Whisper, quiet bedroom", "60 dB" to "Normal conversation", "70 dB" to "Busy street, vacuum", "85 dB" to "Heavy traffic: harmful after 8 h", "100 dB" to "Generator, nightclub: 15 min limit", "120 dB" to "Siren, thunder: pain threshold").forEach { (a, b) -> KV(a, b) }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ air quality

@Composable
fun RadioAirScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var air by remember { mutableStateOf<EnvStore.Air?>(null) }
    var place by remember { mutableStateOf(EnvStore.place(ctx)) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Triple<String, Double, Double>>>(emptyList()) }
    var pm25 by remember { mutableStateOf("") }
    var pm10 by remember { mutableStateOf("") }
    val permL = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) scope.launch {
            busy = true; msg = "Finding you…"
            val l = withContext(Dispatchers.IO) { GeoStore.currentLocation(ctx) }
            if (l != null) { EnvStore.setPlace(ctx, l.latitude, l.longitude); place = l.latitude to l.longitude; name = "your location" } else msg = "Could not get a location fix."
            busy = false
        }
    }
    LaunchedEffect(place) {
        val p = place ?: return@LaunchedEffect
        while (true) {
            busy = true
            air = withContext(Dispatchers.IO) { EnvStore.fetchAir(p.first, p.second) }
            busy = false; msg = if (air == null) "Could not reach the air-quality service." else ""
            delay(10 * 60_000L)
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
        item { Text("Air quality", style = MaterialTheme.typography.headlineSmall) }
        item {
            Section("Where?", if (place != null) "${if (name.isNotEmpty()) "$name · " else ""}%.3f, %.3f".format(place!!.first, place!!.second) else "Pick a place to see its air") {
                Button(onClick = { permL.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) { Text("📍 Use my location") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(q, { q = it }, Modifier.weight(1f), label = { Text("or search a city") }, singleLine = true)
                    Button(enabled = q.isNotBlank(), onClick = { scope.launch { results = withContext(Dispatchers.IO) { GeoStore.search(q) } } }) { Text("Find") }
                }
                results.forEach { (n, la, lo) -> TextButton(onClick = { EnvStore.setPlace(ctx, la, lo); place = la to lo; name = n.substringBefore(','); results = emptyList() }) { Text(n, maxLines = 1) } }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (msg.isNotEmpty()) Hint(msg)
            }
        }
        air?.let { a ->
            item {
                val aqi = a.aqi
                if (aqi != null) {
                    val c = Aqi.category(aqi)
                    Section("Air quality index (US AQI)", "Updated ${a.time.replace('T', ' ')} · data: Open-Meteo / CAMS, refreshes every 10 min") {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            ScoreGauge(((500 - aqi) / 5).coerceIn(0, 100), 110.dp, "AQI $aqi")
                            Column { Text(c.label, fontWeight = FontWeight.Bold, color = Color(c.color), style = MaterialTheme.typography.titleMedium); Text(c.advice, style = MaterialTheme.typography.bodySmall) }
                        }
                        a.euAqi?.let { KV("European AQI", it.toString()) }
                    }
                }
            }
            item {
                Section("Pollutants (µg/m³)") {
                    a.pm25?.let { KV("PM2.5 fine dust", "%.1f".format(it)) }; a.pm10?.let { KV("PM10 coarse dust", "%.1f".format(it)) }
                    a.no2?.let { KV("Nitrogen dioxide", "%.1f".format(it)) }; a.o3?.let { KV("Ozone", "%.1f".format(it)) }
                    a.so2?.let { KV("Sulphur dioxide", "%.1f".format(it)) }; a.co?.let { KV("Carbon monoxide", "%.0f".format(it)) }
                    a.uv?.let { KV("UV index", "%.1f ${if (it >= 8) "(very high: cover up)" else if (it >= 6) "(high)" else if (it >= 3) "(moderate)" else "(low)"}".format(it)) }
                    Hint("WHO daily guideline: PM2.5 ≤ 15, PM10 ≤ 45 µg/m³.")
                }
            }
            if (a.hourly.size > 3) item { Section("AQI today, hour by hour") { BarChart(a.hourly.map { it.second.toFloat() }, Warn, 90); Hint("${a.hourly.first().first} → ${a.hourly.last().first}") } }
        }
        item {
            Section("Your own air sensor", "Have a PM2.5 monitor (PurpleAir, AirVisual, Xiaomi…)? Type its numbers to get the AQI and advice.") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(pm25, { pm25 = it }, Modifier.weight(1f), label = { Text("PM2.5 µg/m³") }, singleLine = true, keyboardOptions = kbDec)
                    OutlinedTextField(pm10, { pm10 = it }, Modifier.weight(1f), label = { Text("PM10 µg/m³") }, singleLine = true, keyboardOptions = kbDec)
                }
                val a1 = pm25.toDoubleOrNull()?.let { Aqi.fromPm25(it) }; val a2 = pm10.toDoubleOrNull()?.let { Aqi.fromPm10(it) }
                val worst = listOfNotNull(a1, a2).maxOrNull()
                if (worst != null) { val c = Aqi.category(worst); Text("AQI $worst · ${c.label}", fontWeight = FontWeight.Bold, color = Color(c.color)); Text(c.advice, style = MaterialTheme.typography.bodySmall) }
                Hint("Bluetooth temperature/humidity tags (Ruuvi, ATC thermometers) show up automatically in the BLE tab.")
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ alerts

@Composable
fun RadioAlertsScreen() {
    val ctx = LocalContext.current
    var running by remember { mutableStateOf(EnvService.running()) }
    var log by remember { mutableStateOf(EnvStore.alerts(ctx)) }
    var now by remember { mutableStateOf(listOf<EnvAlerts.Alert>()) }
    val notifL = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val permL = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) { EnvService.start(ctx); running = true } }
    val sensors = remember { EnvSensors(ctx) }
    DisposableEffect(Unit) { sensors.start(); onDispose { if (!EnvService.running()) sensors.stop() } }
    LaunchedEffect(Unit) {
        while (true) {
            running = EnvService.running(); log = EnvStore.alerts(ctx)
            val noise = if (running) EnvService.liveDb else null
            now = EnvAlerts.evaluate(EnvAlerts.Snapshot(noise, EnvLive.tempC, EnvLive.tempZ, EnvMath.ratePerMin(EnvLive.snapshot(EnvLive.tempHist)), EnvLive.hum, null,
                EnvMath.pressureTrend(EnvLive.snapshot(EnvLive.presHist)), EnvStore.batteryTempC(ctx)))
            delay(3000)
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
        item { Text("Environmental alerts", style = MaterialTheme.typography.headlineSmall) }
        item {
            Section("Background watch", "Keeps listening for loud noise and tracks temperature, pressure and air quality, then notifies you. Uses a persistent notification.") {
                SwitchRow("Watch my environment", if (running) "On: microphone is analysed live, nothing is recorded" else "Off", running) { on ->
                    if (on) {
                        if (Build.VERSION.SDK_INT >= 33) notifL.launch(Manifest.permission.POST_NOTIFICATIONS)
                        if (EnvService.hasMic(ctx)) { EnvService.start(ctx); running = true } else permL.launch(Manifest.permission.RECORD_AUDIO)
                    } else { EnvService.stop(ctx); running = false }
                }
                Hint("Air-quality alerts need a place: set one in the Air tab.")
            }
        }
        item {
            Section("Right now") {
                if (now.isEmpty()) Text("✓ Nothing to worry about.", color = Good, fontWeight = FontWeight.Medium)
                now.forEach { Text("${if (it.urgent) "🔴" else "🟠"} ${it.title}: ${it.text}", style = MaterialTheme.typography.bodyMedium) }
            }
        }
        item {
            Section("History (${log.size})") {
                if (log.isEmpty()) Hint("No alerts yet.")
                log.takeLast(25).reversed().forEach { Column { Text("${it.title}  ·  ${hm(it.t)}", fontWeight = FontWeight.Medium); Text(it.text, style = MaterialTheme.typography.bodySmall) } }
                if (log.isNotEmpty()) TextButton(onClick = { EnvStore.clearAlerts(ctx); log = emptyList() }) { Text("Clear history") }
            }
        }
    }
}
