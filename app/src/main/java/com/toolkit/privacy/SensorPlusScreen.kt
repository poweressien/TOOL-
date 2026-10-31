package com.toolkit.privacy

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

@Composable
fun rememberSensor(type: Int, rate: Int = SensorManager.SENSOR_DELAY_UI, enabled: Boolean = true): State<FloatArray?> {
    val ctx = LocalContext.current
    val state = remember(type) { mutableStateOf<FloatArray?>(null) }
    DisposableEffect(type, enabled) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val s = sm.getDefaultSensor(type)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { state.value = e.values.clone() }
            override fun onAccuracyChanged(s: Sensor?, a: Int) { }
        }
        if (enabled && s != null) sm.registerListener(l, s, rate)
        onDispose { sm.unregisterListener(l) }
    }
    return state
}

private fun sensorTypeName(t: Int) = when (t) {
    Sensor.TYPE_ACCELEROMETER -> "Accelerometer"; Sensor.TYPE_MAGNETIC_FIELD -> "Magnetometer"; Sensor.TYPE_GYROSCOPE -> "Gyroscope"
    Sensor.TYPE_LIGHT -> "Light"; Sensor.TYPE_PRESSURE -> "Barometer"; Sensor.TYPE_PROXIMITY -> "Proximity"; Sensor.TYPE_GRAVITY -> "Gravity"
    Sensor.TYPE_LINEAR_ACCELERATION -> "Linear accel."; Sensor.TYPE_ROTATION_VECTOR -> "Rotation vector"; Sensor.TYPE_AMBIENT_TEMPERATURE -> "Ambient temp."
    Sensor.TYPE_RELATIVE_HUMIDITY -> "Humidity"; Sensor.TYPE_STEP_COUNTER -> "Step counter"; Sensor.TYPE_STEP_DETECTOR -> "Step detector"
    Sensor.TYPE_HEART_RATE -> "Heart rate"; Sensor.TYPE_SIGNIFICANT_MOTION -> "Significant motion"; Sensor.TYPE_GAME_ROTATION_VECTOR -> "Game rotation"
    Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> "Geomagnetic rotation"; Sensor.TYPE_ORIENTATION -> "Orientation (old)"; Sensor.TYPE_HINGE_ANGLE -> "Hinge angle"
    else -> "Type $t"
}

@Composable
fun SensorPlusScreen() {
    val ctx = LocalContext.current
    var tool by remember { mutableStateOf("All sensors") }
    Page("Smart sensor tools", "Turn the sensors in your phone into real instruments.") {
        Chips(listOf("All sensors", "Metal detector", "Vibration", "Light meter", "Altimeter & speed", "Shake", "Touch test", "Display test", "Tone generator", "Haptics"), tool) { tool = it }
        when (tool) {
            "All sensors" -> AllSensors()
            "Metal detector" -> MetalDetector()
            "Vibration" -> VibrationMeter()
            "Light meter" -> LightMeter()
            "Altimeter & speed" -> AltimeterSpeed()
            "Shake" -> ShakeTest()
            "Touch test" -> TouchTest()
            "Display test" -> DisplayTest()
            "Tone generator" -> ToneScreen()
            else -> Haptics()
        }
    }
}

@Composable
private fun AllSensors() {
    val ctx = LocalContext.current
    val sm = remember { ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val list = remember { sm.getSensorList(Sensor.TYPE_ALL).sortedBy { it.type } }
    var sel by remember { mutableStateOf<Sensor?>(null) }
    var values by remember { mutableStateOf(floatArrayOf()) }
    DisposableEffect(sel) {
        val s = sel
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { values = e.values.clone() }
            override fun onAccuracyChanged(s: Sensor?, a: Int) { }
        }
        if (s != null) sm.registerListener(l, s, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(l) }
    }
    Note("${list.size} sensors found. Tap one to watch it live.")
    list.forEach { s ->
        Card(Modifier.fillMaxWidth().clickable { sel = if (sel == s) null else s }) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(s.name, fontWeight = FontWeight.Bold)
                Note("${sensorTypeName(s.type)} · ${s.vendor} · range ${s.maximumRange} · res ${s.resolution} · ${"%.2f".format(s.power)} mA" + if (s.minDelay > 0) " · up to ${1_000_000 / s.minDelay} Hz" else "")
                if (sel == s) {
                    if (values.isEmpty()) Text("Waiting for data...") else Text(values.take(6).mapIndexed { i, v -> "v$i = ${"%.4f".format(v)}" }.joinToString("   "), style = MaterialTheme.typography.bodyMedium)
                    if (s.type == Sensor.TYPE_SIGNIFICANT_MOTION) Note("Triggers once on motion; no live stream.")
                }
            }
        }
    }
}

@Composable
private fun MetalDetector() {
    val mag = rememberSensor(Sensor.TYPE_MAGNETIC_FIELD, SensorManager.SENSOR_DELAY_GAME)
    var base by remember { mutableDoubleStateOf(0.0) }
    var sens by remember { mutableFloatStateOf(1.5f) }
    var sound by remember { mutableStateOf(true) }
    val hist = remember { mutableStateListOf<Float>() }
    val tone = remember { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }
    DisposableEffect(Unit) { onDispose { tone.release() } }
    val v = mag.value
    val m = if (v == null) 0.0 else sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble())
    if (base == 0.0 && m > 0) base = m
    val delta = abs(m - base)
    LaunchedEffect(Unit) {
        while (true) {
            hist.add(delta.toFloat()); if (hist.size > 100) hist.removeAt(0)
            delay(60)
        }
    }
    val strength = (delta * sens / 40.0).coerceIn(0.0, 1.0)
    LaunchedEffect(strength, sound) {
        while (sound && strength > 0.08) {
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 40)
            delay((420 - 380 * strength).toLong().coerceAtLeast(40))
        }
    }
    if (v == null) { Note("No magnetometer found on this phone.", true); return }
    Sec("Metal / magnet detector") {
        BigNumber("%.0f".format(m), "µT field", if (strength > 0.5) Color(0xFFEF4444) else MaterialTheme.colorScheme.primary)
        PercentBar((strength * 100).toInt(), if (strength > 0.5) Color(0xFFEF4444) else if (strength > 0.2) Color(0xFFFBBF24) else Color(0xFF34D399), 14)
        KLineChart(hist.toList(), Color(0xFFF59E0B), 70, minY = 0f)
        Text(when { strength > 0.6 -> "Strong metal / magnet very close"; strength > 0.25 -> "Metal nearby"; strength > 0.08 -> "Slight disturbance"; else -> "Nothing detected" }, fontWeight = FontWeight.Bold)
        Text("Sensitivity ${"%.1f".format(sens)}x", style = MaterialTheme.typography.bodySmall)
        Slider(sens, { sens = it }, valueRange = 0.5f..6f)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { base = m }) { Text("Calibrate here") }
            FilterChip(selected = sound, onClick = { sound = !sound }, label = { Text("Beep") })
        }
        Note("Hold the phone away from metal, tap Calibrate, then sweep slowly. Earth's field is about 25-65 µT. Works on pipes, wiring, studs, magnets, speakers and hidden electronics.")
    }
}

@Composable
private fun VibrationMeter() {
    val acc = rememberSensor(Sensor.TYPE_ACCELEROMETER, SensorManager.SENSOR_DELAY_GAME)
    val hist = remember { mutableStateListOf<Float>() }
    var peak by remember { mutableFloatStateOf(0f) }
    var avg by remember { mutableFloatStateOf(0f) }
    var last = remember { floatArrayOf(0f, 0f, 9.81f) }
    val v = acc.value
    LaunchedEffect(Unit) {
        val win = ArrayDeque<Float>()
        while (true) {
            val a = acc.value
            if (a != null) {
                val dx = a[0] - last[0]; val dy = a[1] - last[1]; val dz = a[2] - last[2]
                last = a.clone()
                val mag = sqrt(dx * dx + dy * dy + dz * dz)
                win.addLast(mag); if (win.size > 25) win.removeFirst()
                avg = sqrt(win.map { it * it }.average().toFloat())
                if (avg > peak) peak = avg
                hist.add(avg); if (hist.size > 100) hist.removeAt(0)
            }
            delay(40)
        }
    }
    if (v == null) { Note("No accelerometer found.", true); return }
    Sec("Vibration meter") {
        BigNumber("%.2f".format(avg), "m/s² RMS")
        KLineChart(hist.toList(), Color(0xFF60A5FA), 90, minY = 0f)
        Text(when { avg < 0.03 -> "Still"; avg < 0.15 -> "Barely perceptible"; avg < 0.5 -> "Light vibration (running appliance)"; avg < 1.5 -> "Strong (heavy machinery / vehicle)"; else -> "Severe shaking" }, fontWeight = FontWeight.Bold)
        Kv("Peak since reset", "%.2f m/s²".format(peak))
        Button(onClick = { peak = 0f }) { Text("Reset peak") }
        Note("Place the phone flat on the surface you want to test: washing machine, engine, fan, desk, floor.")
    }
}

@Composable
private fun LightMeter() {
    val l = rememberSensor(Sensor.TYPE_LIGHT)
    val lux = l.value?.getOrNull(0)
    if (lux == null) { Note("No light sensor found.", true); return }
    Sec("Light meter") {
        BigNumber("%.0f".format(lux), "lux")
        Text(when { lux < 1 -> "Dark"; lux < 50 -> "Dim (night / candle)"; lux < 200 -> "Home evening lighting"; lux < 500 -> "Good for reading (office level)"; lux < 1000 -> "Bright indoor / overcast"; lux < 10000 -> "Very bright / window"; else -> "Direct sunlight" }, fontWeight = FontWeight.Bold)
        Kv("Reading / study", if (lux in 300f..750f) "Ideal" else if (lux < 300f) "Too dim, eye strain risk" else "Bright (fine, watch glare)")
        Kv("Photography EV100", "%.1f".format(kotlin.math.ln(lux.coerceAtLeast(0.1f) / 2.5) / kotlin.math.ln(2.0)))
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun AltimeterSpeed() {
    val ctx = LocalContext.current
    val p = rememberSensor(Sensor.TYPE_PRESSURE)
    var seaLevel by remember { mutableFloatStateOf(1013.25f) }
    var speed by remember { mutableFloatStateOf(0f) }
    var maxSpeed by remember { mutableFloatStateOf(0f) }
    var gpsAlt by remember { mutableStateOf<Double?>(null) }
    var acc by remember { mutableFloatStateOf(0f) }
    var hasLoc by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasLoc = it }
    DisposableEffect(hasLoc) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val l = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                speed = if (loc.hasSpeed()) loc.speed else 0f; if (speed > maxSpeed) maxSpeed = speed
                gpsAlt = if (loc.hasAltitude()) loc.altitude else null; acc = loc.accuracy
            }
            @Deprecated("old") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) { }
            override fun onProviderEnabled(p: String) { }
            override fun onProviderDisabled(p: String) { }
        }
        if (hasLoc) try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, l) } catch (e: Exception) { }
        onDispose { try { lm.removeUpdates(l) } catch (e: Exception) { } }
    }
    Sec("Barometric altitude") {
        val hpa = p.value?.getOrNull(0)
        if (hpa == null) Note("No barometer in this phone.") else {
            val alt = 44330.0 * (1 - (hpa / seaLevel).toDouble().pow(1 / 5.255))
            BigNumber("%.1f".format(alt), "m above sea level")
            Kv("Pressure", "%.2f hPa".format(hpa))
            Text("Sea-level pressure ${"%.1f".format(seaLevel)} hPa (set from your local weather for accuracy)", style = MaterialTheme.typography.bodySmall)
            Slider(seaLevel, { seaLevel = it }, valueRange = 970f..1050f)
            gpsAlt?.let { Button(onClick = { seaLevel = (hpa / (1 - it / 44330.0).pow(5.255)).toFloat() }) { Text("Calibrate to GPS altitude (${"%.0f".format(it)} m)") } }
        }
    }
    Sec("GPS speedometer") {
        if (!hasLoc) { Button(onClick = { launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) { Text("Allow location") } }
        else {
            BigNumber("%.1f".format(speed * 3.6f), "km/h")
            Kv("Speed", "%.1f m/s · %.1f mph".format(speed, speed * 2.23694f))
            Kv("Top speed", "%.1f km/h".format(maxSpeed * 3.6f))
            Kv("GPS altitude", gpsAlt?.let { "%.0f m".format(it) } ?: "waiting for fix")
            Kv("GPS accuracy", "± ${"%.0f".format(acc)} m")
            OutlinedButton(onClick = { maxSpeed = 0f }) { Text("Reset top speed") }
        }
    }
}

@Composable
private fun ShakeTest() {
    val acc = rememberSensor(Sensor.TYPE_ACCELEROMETER, SensorManager.SENSOR_DELAY_GAME)
    var threshold by remember { mutableFloatStateOf(14f) }
    var count by remember { mutableIntStateOf(0) }
    var maxG by remember { mutableFloatStateOf(0f) }
    var flash by remember { mutableStateOf(false) }
    var lastShake by remember { mutableLongStateOf(0L) }
    val v = acc.value
    val g = if (v == null) 0f else sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]) / 9.81f
    LaunchedEffect(g) {
        if (g > maxG) maxG = g
        val now = System.currentTimeMillis()
        if (g * 9.81f > threshold && now - lastShake > 350) { count++; lastShake = now; flash = true; delay(180); flash = false }
    }
    Sec("Shake sensitivity") {
        Box(Modifier.fillMaxWidth().height(90.dp).background(if (flash) Color(0xFFEF4444) else MaterialTheme.colorScheme.surfaceVariant)) {
            Text(if (flash) "SHAKE!" else "Shake the phone", Modifier.padding(30.dp), fontWeight = FontWeight.Bold)
        }
        Kv("Shakes counted", "$count")
        Kv("Current force", "%.2f g".format(g))
        Kv("Strongest", "%.2f g".format(maxG))
        Text("Trigger level ${"%.0f".format(threshold)} m/s² (${"%.1f".format(threshold / 9.81f)} g)", style = MaterialTheme.typography.bodySmall)
        Slider(threshold, { threshold = it }, valueRange = 11f..40f)
        OutlinedButton(onClick = { count = 0; maxG = 0f }) { Text("Reset") }
        Note("Use this to find the best shake threshold for your own apps and gestures.")
    }
}

@Composable
private fun TouchTest() {
    var touches by remember { mutableIntStateOf(0) }
    var maxTouches by remember { mutableIntStateOf(0) }
    var pressure by remember { mutableFloatStateOf(0f) }
    val trail = remember { mutableStateListOf<Offset>() }
    Sec("Touch screen test") {
        Kv("Fingers now", "$touches")
        Kv("Most at once", "$maxTouches")
        Kv("Pressure", "%.2f".format(pressure))
        Canvas(Modifier.fillMaxWidth().height(300.dp).background(MaterialTheme.colorScheme.surfaceVariant).pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown()
                do {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    touches = pressed.size
                    if (touches > maxTouches) maxTouches = touches
                    pressed.forEach { trail.add(it.position); pressure = it.pressure; it.consume() }
                    while (trail.size > 700) trail.removeAt(0)
                } while (event.changes.any { it.pressed })
                touches = 0
            }
        }) {
            trail.forEach { drawCircle(Color(0xFF2DD4BF), 9f, it) }
        }
        OutlinedButton(onClick = { trail.clear(); maxTouches = 0 }) { Text("Clear") }
        Note("Draw across the whole box to find dead zones. Most phones support 5-10 fingers.")
    }
}

@Composable
private fun DisplayTest() {
    var show by remember { mutableStateOf(false) }
    var idx by remember { mutableIntStateOf(0) }
    val colors = listOf(Color.Black, Color.White, Color.Red, Color.Green, Color.Blue, Color(0xFF808080), Color.Cyan, Color.Magenta, Color.Yellow)
    Sec("Display test") {
        Text("Full-screen colours reveal dead pixels, stuck pixels, burn-in, tinting and dirty edges. Tap the screen to change colour, press back to leave.")
        Button(onClick = { idx = 0; show = true }) { Text("Start test") }
    }
    if (show) Dialog(onDismissRequest = { show = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(if (idx < colors.size) colors[idx] else Color.Black).clickable { idx++; if (idx > colors.size) show = false }) {
            if (idx == colors.size) Canvas(Modifier.fillMaxSize()) {
                val steps = 64
                for (i in 0 until steps) drawRect(Color(i / (steps - 1f), i / (steps - 1f), i / (steps - 1f)), Offset(size.width * i / steps, 0f), androidx.compose.ui.geometry.Size(size.width / steps + 1, size.height))
            }
        }
    }
}

@Composable
private fun ToneScreen() {
    val gen = remember { ToneGen() }
    var f by remember { mutableFloatStateOf(440f) }
    var vol by remember { mutableFloatStateOf(0.3f) }
    var on by remember { mutableStateOf(false) }
    var sweep by remember { mutableStateOf(false) }
    var left by remember { mutableStateOf(true) }
    var right by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { gen.stop() } }
    gen.freq = f.toDouble(); gen.volume = vol; gen.left = left; gen.right = right
    gen.sweepTo = if (sweep) 20000.0 else 0.0
    Sec("Tone generator") {
        BigNumber("%.0f".format(f), "Hz")
        Slider(f, { f = it }, valueRange = 20f..20000f)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(100, 440, 1000, 4000, 10000, 15000).forEach { OutlinedButton(onClick = { f = it.toFloat() }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("$it") } }
        }
        Text("Volume ${(vol * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
        Slider(vol, { vol = it }, valueRange = 0.02f..1f)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = left, onClick = { left = !left }, label = { Text("Left") })
            FilterChip(selected = right, onClick = { right = !right }, label = { Text("Right") })
            FilterChip(selected = sweep, onClick = { sweep = !sweep }, label = { Text("Sweep to 20 kHz") })
        }
        Button(onClick = { if (on) gen.stop() else gen.start(); on = !on }) { Text(if (on) "Stop" else "Play") }
        Note("Test speakers, find your hearing limit (most adults stop hearing above 15-17 kHz) or locate rattles. Keep volume low: pure tones can be harsh.")
    }
}

@Composable
private fun Haptics() {
    val ctx = LocalContext.current
    val vib = remember { ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator }
    fun buzz(pattern: LongArray, amp: IntArray? = null) {
        try {
            if (Build.VERSION.SDK_INT >= 26) vib.vibrate(if (amp != null && vib.hasAmplitudeControl()) VibrationEffect.createWaveform(pattern, amp, -1) else VibrationEffect.createWaveform(pattern, -1))
            else @Suppress("DEPRECATION") vib.vibrate(pattern, -1)
        } catch (e: Exception) { }
    }
    Sec("Vibration motor test") {
        Kv("Motor present", if (vib.hasVibrator()) "Yes" else "No")
        if (Build.VERSION.SDK_INT >= 26) Kv("Amplitude control", if (vib.hasAmplitudeControl()) "Yes" else "No (on/off only)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { buzz(longArrayOf(0, 60)) }) { Text("Tick") }
            OutlinedButton(onClick = { buzz(longArrayOf(0, 300)) }) { Text("Buzz") }
            OutlinedButton(onClick = { buzz(longArrayOf(0, 1000)) }) { Text("Long") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { buzz(longArrayOf(0, 100, 80, 100, 80, 100)) }) { Text("Triple") }
            OutlinedButton(onClick = { buzz(longArrayOf(0, 80, 40, 80, 40, 400, 100, 80)) }) { Text("SOS-ish") }
            OutlinedButton(onClick = { buzz(longArrayOf(0, 50, 50, 100, 50, 150, 50, 200, 50, 255.toLong()), intArrayOf(0, 30, 0, 80, 0, 140, 0, 200, 0, 255)) }) { Text("Ramp") }
        }
    }
}
