package com.toolkit.privacy

import android.Manifest
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun rememberAzimuth(): State<Float> {
    val ctx = LocalContext.current
    val az = remember { mutableStateOf(0f) }
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val s = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val rm = FloatArray(9); val o = FloatArray(3)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rm, e.values); SensorManager.getOrientation(rm, o)
                az.value = ((Math.toDegrees(o[0].toDouble()) + 360) % 360).toFloat()
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (s != null) sm.registerListener(l, s, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(l) }
    }
    return az
}

/** Arrow that points toward a target given bearing and the phone's compass heading. */
@Composable
fun BearingArrow(bearing: Double, azimuth: Float, color: Color = Good, size: Int = 140) {
    Canvas(Modifier.size(size.dp)) {
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val r = this.size.width * 0.42f
        drawCircle(Color(0x33888888), r, c, style = Stroke(2.dp.toPx()))
        val th = Math.toRadians(bearing - azimuth)
        val tip = Offset(c.x + r * sin(th).toFloat(), c.y - r * cos(th).toFloat())
        val tail = Offset(c.x - r * 0.5f * sin(th).toFloat(), c.y + r * 0.5f * cos(th).toFloat())
        drawLine(color, tail, tip, 6.dp.toPx())
        for (d in listOf(150.0, -150.0)) {
            val a = th + Math.toRadians(d)
            drawLine(color, tip, Offset(tip.x + r * 0.35f * sin(a).toFloat(), tip.y - r * 0.35f * cos(a).toFloat()), 6.dp.toPx())
        }
    }
}

@Composable
private fun LocPermCard(perm: Boolean, onResult: () -> Unit) {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onResult() }
    if (!perm) Section("Location permission needed") {
        Text("GeoSense uses your phone's GPS only on this device. Nothing is uploaded.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) { Text("Allow location") }
    }
}

private fun fmtTime(t: Long) = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(t))

// ------------------------------------------------------------------------------------------------ places

@Composable
fun GeoPlacesScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var places by remember { mutableStateOf(GeoStore.places(ctx)) }
    var events by remember { mutableStateOf(GeoStore.events(ctx).take(12)) }
    var perm by remember { mutableStateOf(GeoService.hasPerm(ctx)) }
    var running by remember { mutableStateOf(GeoService.running()) }
    var loc by remember { mutableStateOf<Location?>(GeoService.last) }
    var inside by remember { mutableStateOf(GeoService.inside) }
    var name by remember { mutableStateOf("") }
    var lat by remember { mutableStateOf("") }
    var lon by remember { mutableStateOf("") }
    var radius by remember { mutableFloatStateOf(150f) }
    var onEnter by remember { mutableStateOf(true) }
    var onExit by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Triple<String, Double, Double>>>(emptyList()) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            perm = GeoService.hasPerm(ctx); running = GeoService.running(); loc = GeoService.last; inside = GeoService.inside
            events = GeoStore.events(ctx).take(12); delay(2000)
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
        item { Text("Saved places", style = MaterialTheme.typography.headlineSmall) }
        item { LocPermCard(perm) { perm = GeoService.hasPerm(ctx) } }
        item {
            Section("Place alerts", "Get a notification when you arrive at or leave a saved place. Works in the background without Google services.") {
                SwitchRow("Watch my places", if (running) "Running (small notification shown)" else "Off", running) { on ->
                    if (on) { if (perm) { GeoService.start(ctx); running = true } else msg = "Allow location first." } else { GeoService.stop(ctx); running = false }
                }
                PermRow("Allow all the time", "Android 10+: set Location to \"Allow all the time\" so alerts work with the screen off", perm && ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED, "Open settings") {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                loc?.let { Hint("Now: %.5f, %.5f  ±%d m (${it.provider})".format(it.latitude, it.longitude, it.accuracy.toInt())) }
                if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        item {
            Section("Add a place") {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name (Home, Office, Church…)") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(lat, { lat = it }, Modifier.weight(1f), label = { Text("Latitude") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(lon, { lon = it }, Modifier.weight(1f), label = { Text("Longitude") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = perm && !busy, onClick = {
                        busy = true; msg = "Getting a GPS fix…"
                        scope.launch {
                            val l = withContext(Dispatchers.IO) { GeoStore.currentLocation(ctx) }
                            if (l != null) { lat = "%.6f".format(l.latitude); lon = "%.6f".format(l.longitude); msg = "Got it (±${l.accuracy.toInt()} m)" } else msg = "No GPS fix. Go outside or enable location."
                            busy = false
                        }
                    }) { Text("Use my location") }
                    OutlinedButton(onClick = {
                        val txt = clipboardText(ctx)
                        scope.launch {
                            var c = GeoMath.parseCoords(txt)
                            if (c == null && txt.startsWith("http")) {
                                val final = withContext(Dispatchers.IO) { try { val h = URL(txt.trim()).openConnection() as HttpURLConnection; h.instanceFollowRedirects = true; h.connectTimeout = 5000; h.readTimeout = 5000; h.responseCode; h.url.toString() } catch (e: Exception) { "" } }
                                c = GeoMath.parseCoords(final)
                            }
                            if (c != null) { lat = c.first.toString(); lon = c.second.toString(); msg = "Coordinates taken from your clipboard" } else msg = "No coordinates found in the copied text/link"
                        }
                    }) { Text("From copied map link") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(query, { query = it }, Modifier.weight(1f), label = { Text("Or search an address") }, singleLine = true)
                    Button(enabled = query.isNotBlank(), onClick = { scope.launch { msg = "Searching…"; results = withContext(Dispatchers.IO) { GeoStore.search(query) }; msg = if (results.isEmpty()) "No results" else "" } }) { Text("Find") }
                }
                results.forEach { r -> TextButton(onClick = { lat = r.second.toString(); lon = r.third.toString(); if (name.isBlank()) name = r.first.substringBefore(','); results = emptyList() }) { Text(r.first, maxLines = 2) } }
                Text("Alert radius: ${radius.toInt()} m", fontWeight = FontWeight.Medium)
                Slider(radius, { radius = it }, valueRange = 50f..1000f)
                SwitchRow("Alert when I arrive", "", onEnter) { onEnter = it }
                SwitchRow("Alert when I leave", "", onExit) { onExit = it }
                Button(enabled = name.isNotBlank() && lat.toDoubleOrNull() != null && lon.toDoubleOrNull() != null, onClick = {
                    GeoStore.addPlace(ctx, GeoPlace(System.currentTimeMillis(), name.trim(), lat.toDouble(), lon.toDouble(), radius.toInt(), onEnter, onExit))
                    places = GeoStore.places(ctx); name = ""; lat = ""; lon = ""
                    if (perm && !running) { GeoService.start(ctx); running = true }
                }) { Text("Save place") }
            }
        }
        items(places, key = { it.id }) { p ->
            val d = loc?.let { GeoMath.distance(it.latitude, it.longitude, p.lat, p.lon) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row { Text(p.name, Modifier.weight(1f), fontWeight = FontWeight.Bold); Text(if (p.id in inside) "● here" else d?.let { GeoMath.fmtDist(it) } ?: "", color = if (p.id in inside) Good else Color.Unspecified) }
                    Text("%.5f, %.5f · radius ${p.radius} m · ${if (p.onEnter) "arrive" else ""}${if (p.onEnter && p.onExit) " + " else ""}${if (p.onExit) "leave" else ""}".format(p.lat, p.lon), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { openUrl(ctx, "geo:${p.lat},${p.lon}?q=${p.lat},${p.lon}(${Uri.encode(p.name)})") }) { Text("Open in maps") }
                        TextButton(onClick = { openUrl(ctx, "google.navigation:q=${p.lat},${p.lon}") }) { Text("Navigate") }
                        TextButton(onClick = { GeoStore.removePlace(ctx, p.id); places = GeoStore.places(ctx) }) { Text("Delete") }
                    }
                }
            }
        }
        if (events.isNotEmpty()) item {
            Section("Recent arrivals & departures") {
                events.forEach { Text("${if (it.kind == "enter") "⬇ Arrived" else "⬆ Left"}  ${it.place}  ·  ${fmtTime(it.time)}", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ trips

@Composable
private fun RouteCanvas(pts: List<TripPoint>) {
    Canvas(Modifier.fillMaxWidth().height(220.dp)) {
        if (pts.size < 2) return@Canvas
        val minLat = pts.minOf { it.lat }; val maxLat = pts.maxOf { it.lat }
        val minLon = pts.minOf { it.lon }; val maxLon = pts.maxOf { it.lon }
        val kx = cos(Math.toRadians((minLat + maxLat) / 2))
        val w = ((maxLon - minLon) * kx).coerceAtLeast(1e-6); val h = (maxLat - minLat).coerceAtLeast(1e-6)
        val scale = minOf((size.width - 24.dp.toPx()) / w, (size.height - 24.dp.toPx()) / h).toFloat()
        val ox = (size.width - (w * scale).toFloat()) / 2; val oy = (size.height - (h * scale).toFloat()) / 2
        fun pt(p: TripPoint) = Offset(ox + ((p.lon - minLon) * kx * scale).toFloat(), size.height - oy - ((p.lat - minLat) * scale).toFloat())
        val path = Path()
        pts.forEachIndexed { i, p -> val o = pt(p); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
        drawPath(path, Info, style = Stroke(4.dp.toPx()))
        drawCircle(Good, 7.dp.toPx(), pt(pts.first()))
        drawCircle(Bad, 7.dp.toPx(), pt(pts.last()))
    }
}

@Composable
fun GeoTripsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var perm by remember { mutableStateOf(GeoService.hasPerm(ctx)) }
    var recId by remember { mutableStateOf(Prefs.sp(ctx).getLong(Prefs.GEO_REC, 0L)) }
    var trips by remember { mutableStateOf(GeoStore.trips(ctx)) }
    var live by remember { mutableStateOf<TripStats?>(null) }
    var openId by remember { mutableStateOf(0L) }
    var openPts by remember { mutableStateOf<List<TripPoint>>(emptyList()) }
    var results by remember { mutableStateOf<List<Result>>(emptyList()) }

    LaunchedEffect(Unit) {
        while (true) {
            perm = GeoService.hasPerm(ctx)
            recId = Prefs.sp(ctx).getLong(Prefs.GEO_REC, 0L)
            if (recId != 0L) live = withContext(Dispatchers.IO) { GeoMath.stats(GeoStore.points(ctx, recId)) }
            delay(3000)
        }
    }
    LaunchedEffect(openId) { openPts = if (openId == 0L) emptyList() else withContext(Dispatchers.IO) { GeoStore.points(ctx, openId) } }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
        item { Text("Journeys", style = MaterialTheme.typography.headlineSmall) }
        item { LocPermCard(perm) { perm = GeoService.hasPerm(ctx) } }
        item {
            Section("Record a journey", "Draws your route, measures distance, time, speed and climb. Export as GPX for any maps app.") {
                if (recId == 0L) {
                    Button(enabled = perm, onClick = { recId = GeoStore.startTrip(ctx); GeoService.start(ctx); live = null }) { Text("● Start recording") }
                } else {
                    Text("Recording…", color = Bad, fontWeight = FontWeight.Bold)
                    live?.let { s -> Text("${GeoMath.fmtDist(s.distM)} · ${GeoMath.fmtDur(s.durationS)} · avg ${"%.1f".format(s.avgKmh)} km/h · max ${"%.1f".format(s.maxKmh)} km/h") }
                    Button(onClick = {
                        GeoStore.finishTrip(ctx); recId = 0L; trips = GeoStore.trips(ctx)
                        if (Prefs.sp(ctx).getBoolean(Prefs.GEO_ON, false)) GeoService.start(ctx) else GeoService.stopAll(ctx)
                    }) { Text("■ Stop & save") }
                }
            }
        }
        items(trips.filter { it.end > 0 }, key = { it.id }) { t ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(t.name, fontWeight = FontWeight.Bold)
                    Text("${GeoMath.fmtDist(t.distM)} · ${GeoMath.fmtDur((t.end - t.start) / 1000)} · ${t.points} points", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { openId = if (openId == t.id) 0L else t.id; results = emptyList() }, contentPadding = PaddingValues(0.dp)) { Text(if (openId == t.id) "Hide" else "Show route & stats") }
                    if (openId == t.id && openPts.size >= 2) {
                        val st = GeoMath.stats(openPts)
                        RouteCanvas(openPts)
                        KV("Distance", GeoMath.fmtDist(st.distM)); KV("Total time", GeoMath.fmtDur(st.durationS)); KV("Moving time", GeoMath.fmtDur(st.movingS))
                        KV("Average speed", "%.1f km/h".format(st.avgKmh)); KV("Top speed", "%.1f km/h".format(st.maxKmh)); KV("Climb", "${st.climbM.toInt()} m")
                        Hint("Speed over the trip (km/h)")
                        LineChart(openPts.map { it.speed * 3.6f }, Warn, 80)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                val f = File(cacheOut(ctx), t.name.replace(Regex("[^A-Za-z0-9]+"), "_") + ".gpx")
                                f.writeText(GeoMath.gpx(t.name, openPts)); results = listOf(Result(f.name, f, 0, f.length(), "application/gpx+xml"))
                            }) { Text("Export GPX") }
                            OutlinedButton(onClick = { openUrl(ctx, "geo:${openPts.first().lat},${openPts.first().lon}") }) { Text("Start point in maps") }
                        }
                        if (results.isNotEmpty()) ResultList(results)
                    }
                    TextButton(onClick = { GeoStore.deleteTrip(ctx, t.id); trips = GeoStore.trips(ctx); if (openId == t.id) openId = 0L }, contentPadding = PaddingValues(0.dp)) { Text("Delete journey") }
                }
            }
        }
        if (trips.none { it.end > 0 }) item { Hint("No saved journeys yet.") }
    }
}

// ------------------------------------------------------------------------------------------------ parking

@Composable
fun GeoParkingScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var perm by remember { mutableStateOf(GeoService.hasPerm(ctx)) }
    var park by remember { mutableStateOf(GeoStore.parking(ctx)) }
    var me by remember { mutableStateOf<Location?>(GeoService.last) }
    var note by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val az by rememberAzimuth()

    // keep a fresh fix while this screen is open
    LaunchedEffect(perm) {
        while (perm) {
            val l = withContext(Dispatchers.IO) { GeoStore.currentLocation(ctx, 8000) }
            if (l != null) me = l
            now = System.currentTimeMillis()
            delay(4000)
        }
    }
    LaunchedEffect(Unit) { while (true) { perm = GeoService.hasPerm(ctx); now = System.currentTimeMillis(); delay(1000) } }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
        item { Text("Where did I park?", style = MaterialTheme.typography.headlineSmall) }
        item { LocPermCard(perm) { perm = GeoService.hasPerm(ctx) } }
        item {
            Section("Save the spot") {
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Note (level 3, near lift, row C…)") }, singleLine = true)
                OutlinedTextField(minutes, { minutes = it }, Modifier.fillMaxWidth(), label = { Text("Parking meter minutes (optional reminder)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Button(enabled = perm && !busy, onClick = {
                    busy = true; msg = "Getting a precise fix…"
                    scope.launch {
                        val l = withContext(Dispatchers.IO) { GeoStore.currentLocation(ctx, 15000) }
                        if (l == null) msg = "No GPS fix. Try again outdoors." else {
                            val exp = minutes.toIntOrNull()?.let { System.currentTimeMillis() + it * 60_000L } ?: 0L
                            GeoStore.saveParking(ctx, Parking(l.latitude, l.longitude, System.currentTimeMillis(), l.accuracy, note, exp))
                            if (exp > 0) GeoService.scheduleParkingAlarm(ctx, exp - 5 * 60_000L) else GeoService.cancelParkingAlarm(ctx)
                            park = GeoStore.parking(ctx); msg = "Saved (±${l.accuracy.toInt()} m)"
                        }
                        busy = false
                    }
                }) { Text(if (busy) "Locating…" else "🅿 Save parking here") }
                if (msg.isNotEmpty()) Hint(msg)
            }
        }
        park?.let { p ->
            item {
                val d = me?.let { GeoMath.distance(it.latitude, it.longitude, p.lat, p.lon) }
                val b = me?.let { GeoMath.bearing(it.latitude, it.longitude, p.lat, p.lon) }
                Section("Your car") {
                    Text("Parked ${fmtTime(p.time)} · ${GeoMath.fmtDur((now - p.time) / 1000)} ago", fontWeight = FontWeight.Medium)
                    if (p.note.isNotBlank()) Text(p.note)
                    if (p.expiry > 0) Text(if (p.expiry > now) "Meter ends in ${GeoMath.fmtDur((p.expiry - now) / 1000)}" else "⚠ Meter expired", color = if (p.expiry > now) Color.Unspecified else Bad)
                    if (d != null && b != null) {
                        Text("${GeoMath.fmtDist(d)} away · head ${GeoMath.compass(b)} (${b.toInt()}°)", style = MaterialTheme.typography.titleMedium)
                        BearingArrow(b, az)
                        Hint("The arrow points to your car. Hold the phone flat.")
                    } else Hint("Waiting for your location…")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { openUrl(ctx, "google.navigation:q=${p.lat},${p.lon}&mode=w") }) { Text("Navigate (walk)") }
                        OutlinedButton(onClick = { openUrl(ctx, "geo:${p.lat},${p.lon}?q=${p.lat},${p.lon}(My%20car)") }) { Text("Open in maps") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { shareText(ctx, "My car is parked here: https://maps.google.com/?q=${p.lat},${p.lon}") }) { Text("Share location") }
                        OutlinedButton(onClick = { GeoStore.clearParking(ctx); GeoService.cancelParkingAlarm(ctx); park = null }) { Text("Found it · clear") }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ reminders

@Composable
fun GeoRemindersScreen() {
    val ctx = LocalContext.current
    var places by remember { mutableStateOf(GeoStore.places(ctx)) }
    var rems by remember { mutableStateOf(GeoStore.reminders(ctx)) }
    var text by remember { mutableStateOf("") }
    var sel by remember { mutableStateOf(places.firstOrNull()?.id ?: 0L) }
    var trig by remember { mutableStateOf("enter") }
    var repeat by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(GeoService.running()) }
    LaunchedEffect(Unit) { while (true) { places = GeoStore.places(ctx); rems = GeoStore.reminders(ctx); running = GeoService.running(); delay(2500) } }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
        item { Text("Location reminders", style = MaterialTheme.typography.headlineSmall) }
        item { Hint("\"Buy bread\" when you reach the market, \"Switch off the gas\" when you leave home. Reminders fire from your saved places, so add a place first (Places tab).") }
        if (places.isEmpty()) item { Text("No saved places yet.", color = Warn) }
        else item {
            Section("New reminder") {
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Remind me to…") })
                Text("Place", fontWeight = FontWeight.Medium)
                ChipRow(places.map { it.name }, places.firstOrNull { it.id == sel }?.name ?: "") { n -> sel = places.first { it.name == n }.id }
                ChipRow(listOf("When I arrive", "When I leave"), if (trig == "enter") "When I arrive" else "When I leave") { trig = if (it.contains("arrive")) "enter" else "exit" }
                SwitchRow("Repeat every time", "Off = once, then it is marked done", repeat) { repeat = it }
                Button(enabled = text.isNotBlank() && sel != 0L, onClick = {
                    GeoStore.saveReminders(ctx, GeoStore.reminders(ctx) + GeoReminder(System.currentTimeMillis(), sel, text.trim(), trig, repeat, false))
                    rems = GeoStore.reminders(ctx); text = ""
                    if (GeoService.hasPerm(ctx) && !GeoService.running()) GeoService.start(ctx)
                }) { Text("Add reminder") }
                if (!running) Hint("Place watching is off: turn on \"Watch my places\" in the Places tab (it starts automatically when you add a reminder and location is allowed).")
            }
        }
        items(rems, key = { it.id }) { r ->
            val pn = places.firstOrNull { it.id == r.placeId }?.name ?: "?"
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(r.text, fontWeight = FontWeight.Medium)
                        Text("${if (r.trigger == "enter") "Arriving at" else "Leaving"} $pn" + (if (r.repeat) " · repeats" else "") + (if (r.done) " · ✓ done" else ""), style = MaterialTheme.typography.bodySmall)
                    }
                    if (r.done) TextButton(onClick = { GeoStore.saveReminders(ctx, GeoStore.reminders(ctx).map { if (it.id == r.id) it.copy(done = false) else it }); rems = GeoStore.reminders(ctx) }) { Text("Re-arm") }
                    TextButton(onClick = { GeoStore.saveReminders(ctx, GeoStore.reminders(ctx).filterNot { it.id == r.id }); rems = GeoStore.reminders(ctx) }) { Text("Delete") }
                }
            }
        }
    }
}
