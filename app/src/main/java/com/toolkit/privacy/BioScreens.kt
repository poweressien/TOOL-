package com.toolkit.privacy

import android.Manifest
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private fun fmtDay(t: Long) = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(t))
private fun fmtHm(t: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))
private fun hmToNext(s: String): Long? {
    val m = Regex("(\\d{1,2}):(\\d{2})").matchEntire(s.trim()) ?: return null
    val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, m.groupValues[1].toInt().coerceIn(0, 23)); set(Calendar.MINUTE, m.groupValues[2].toInt().coerceIn(0, 59)); set(Calendar.SECOND, 0) }
    if (c.timeInMillis < System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1)
    return c.timeInMillis
}

@Composable
private fun Disclaimer() { Hint("For wellness and curiosity only. Phone sensors are not medical devices; talk to a doctor about health concerns.") }

// ------------------------------------------------------------------------------------------------ today

@Composable
fun BioTodayScreen() {
    val ctx = LocalContext.current
    val sp = remember { Prefs.sp(ctx) }
    var perm by remember { mutableStateOf(BioStore.hasActivityPerm(ctx)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { perm = it }
    var steps by remember { mutableStateOf<Int?>(null) }
    var goal by remember { mutableStateOf(sp.getInt(Prefs.BIO_STEP_GOAL, 8000)) }
    var hist by remember { mutableStateOf(BioStore.stepHistory(ctx, 7)) }
    var height by remember { mutableStateOf(sp.getInt(Prefs.BIO_HEIGHT, 170).toString()) }
    var weight by remember { mutableStateOf(sp.getFloat(Prefs.BIO_WEIGHT, 70f).toString()) }
    val sensor = remember { BioStore.hasStepSensor(ctx) }
    var hr by remember { mutableStateOf(BioStore.hrLog(ctx).lastOrNull()) }
    var sleep by remember { mutableStateOf(BioStore.sleeps(ctx).lastOrNull()) }

    LaunchedEffect(perm) {
        BioStore.scheduleDaily(ctx)
        while (true) {
            if (perm) { steps = withContext(Dispatchers.IO) { BioStore.syncSteps(ctx) }; hist = BioStore.stepHistory(ctx, 7) }
            hr = BioStore.hrLog(ctx).lastOrNull(); sleep = BioStore.sleeps(ctx).lastOrNull()
            delay(8000)
        }
    }
    val (h, kg) = BioStore.profile(ctx)
    val st = steps ?: 0

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
        item { Text("BioTrack", style = MaterialTheme.typography.headlineSmall) }
        item { Disclaimer() }
        if (!sensor) item { Text("This phone has no hardware step counter.", color = Warn) }
        else if (!perm) item {
            Section("Allow activity tracking") {
                Text("Android needs the Physical activity permission to read the step counter. Counting happens in the phone's low-power chip, all day, with no battery cost.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { launcher.launch(Manifest.permission.ACTIVITY_RECOGNITION) }) { Text("Allow") }
            }
        } else item {
            Section("Today's activity") {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    BigStat("steps", "%,d".format(st), if (st >= goal) Good else Color.Unspecified)
                    BigStat("km", "%.2f".format(StepMath.distanceKm(st, h)))
                    BigStat("kcal", "${StepMath.kcal(st, h, kg).toInt()}")
                }
                PercentBar((st * 100 / goal.coerceAtLeast(1)).coerceIn(0, 100), if (st >= goal) Good else Info, 12)
                Hint("${(st * 100 / goal.coerceAtLeast(1))}% of your $goal step goal · ~${st / 100} active minutes")
                ChipRow(listOf("5000", "8000", "10000", "12000"), goal.toString()) { goal = it.toInt(); sp.edit().putInt(Prefs.BIO_STEP_GOAL, goal).apply() }
            }
        }
        if (sensor && perm) item {
            Section("Last 7 days") {
                BarChart(hist.map { it.second.toFloat() }, Good, 100)
                Hint(hist.joinToString("  ") { "${it.first.takeLast(5).replace('-', '/')}: ${it.second}" })
            }
        }
        item {
            Section("Right now") {
                KV("Last heart rate", hr?.let { "${it.bpm} bpm · ${BioInsights.hrLabel(it.bpm)} (${fmtDay(it.t)} ${fmtHm(it.t)})" } ?: "none yet: open the Heart tab")
                KV("Last sleep", sleep?.let { "${"%.1f".format(it.hours)} h · score ${it.score} (${fmtDay(it.endMs)})" } ?: "none yet: open the Sleep tab")
            }
        }
        item {
            Section("Your profile", "Used for stride length, distance and calories. Stored only on this phone.") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(height, { height = it; it.toIntOrNull()?.let { v -> if (v in 100..230) sp.edit().putInt(Prefs.BIO_HEIGHT, v).apply() } }, Modifier.weight(1f), label = { Text("Height cm") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(weight, { weight = it; it.toFloatOrNull()?.let { v -> if (v in 25f..300f) sp.edit().putFloat(Prefs.BIO_WEIGHT, v).apply() } }, Modifier.weight(1f), label = { Text("Weight kg") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                val b = BioInsights.bmi(kg, h)
                Hint("BMI ${"%.1f".format(b)} · ${BioInsights.bmiLabel(b)} (a rough guide only)")
                OutlinedButton(onClick = { BioStore.addWeight(ctx, kg) }) { Text("Log today's weight") }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ heart

@Composable
fun BioHeartScreen() {
    val ctx = LocalContext.current
    val camPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val bodyPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var measuring by remember { mutableStateOf(false) }
    var remaining by remember { mutableStateOf(0) }
    var signal by remember { mutableStateOf<List<Float>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Ppg.Result?>(null) }
    var log by remember { mutableStateOf(BioStore.hrLog(ctx)) }
    var manual by remember { mutableStateOf("") }
    val sensorMgr = remember { ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val hrSensor = remember { sensorMgr.getDefaultSensor(Sensor.TYPE_HEART_RATE) }
    var listening by remember { mutableStateOf(false) }
    var sensorBpm by remember { mutableStateOf(0) }
    val ts = remember { ArrayList<Long>() }
    val vs = remember { ArrayList<Double>() }

    LaunchedEffect(measuring) {
        if (!measuring) return@LaunchedEffect
        synchronized(ts) { ts.clear(); vs.clear() }; result = null; signal = emptyList()
        val meter = PpgMeter(ctx, { t, v -> synchronized(ts) { ts.add(t); vs.add(v) } }, { err -> status = err; measuring = false })
        meter.start()
        try {
            for (i in 20 downTo 1) {
                remaining = i; delay(1000)
                synchronized(ts) {
                    val n = vs.size
                    if (n > 10) {
                        val avg = vs.takeLast(10).average()
                        status = when { avg < 35 -> "Cover the camera AND flash fully with your fingertip"; avg > 250 -> "Too bright: press a little softer"; else -> "Hold still… reading your pulse" }
                    }
                    signal = vs.takeLast(150).map { it.toFloat() }
                }
            }
        } finally { meter.stop() }
        measuring = false
        val r = synchronized(ts) { Ppg.process(ts.toLongArray(), vs.toDoubleArray()) }
        result = r
        if (r == null) status = "Could not find a steady pulse. Cover lens and flash fully, keep still, and try again."
        else { status = if (r.quality >= 50) "Done" else "Done (low signal quality, try again for a better reading)"; if (r.quality >= 40) { BioStore.addHr(ctx, r.bpm, "camera", r.rmssd); log = BioStore.hrLog(ctx) } }
    }

    DisposableEffect(listening) {
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { val v = e.values[0].toInt(); if (v > 0) sensorBpm = v }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (listening && hrSensor != null) sensorMgr.registerListener(l, hrSensor, SensorManager.SENSOR_DELAY_NORMAL)
        onDispose { sensorMgr.unregisterListener(l) }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
        item { Text("Heart rate", style = MaterialTheme.typography.headlineSmall) }
        item { Disclaimer() }
        item {
            Section("Measure with the camera", "Place a fingertip gently over the rear camera AND flash for 20 seconds. No extra hardware needed.") {
                if (measuring) {
                    Text("$remaining s", style = MaterialTheme.typography.headlineMedium, color = Bad)
                    LineChart(signal, Bad, 90, fill = false)
                    OutlinedButton(onClick = { measuring = false }) { Text("Cancel") }
                } else Button(onClick = {
                    if (ctx.checkSelfPermission(Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) camPerm.launch(Manifest.permission.CAMERA) else { status = ""; measuring = true }
                }) { Text("♥ Start 20-second measurement") }
                if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
                result?.let { r ->
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        BigStat("beats per minute", "${r.bpm}", Bad); BigStat("HRV (RMSSD) ms", "${r.rmssd}"); BigStat("signal quality", "${r.quality}%")
                    }
                    Text(BioInsights.hrLabel(r.bpm), fontWeight = FontWeight.Medium)
                }
            }
        }
        if (hrSensor != null) item {
            Section("Built-in heart-rate sensor", "Your phone or paired watch exposes a heart-rate sensor. Wear it snugly and keep still.") {
                SwitchRow("Live reading", if (listening) (if (sensorBpm > 0) "$sensorBpm bpm" else "Waiting for a stable reading…") else "Off", listening) { on ->
                    if (on && ctx.checkSelfPermission(Manifest.permission.BODY_SENSORS) != android.content.pm.PackageManager.PERMISSION_GRANTED) bodyPerm.launch(Manifest.permission.BODY_SENSORS) else listening = on
                }
                if (listening && sensorBpm > 0) OutlinedButton(onClick = { BioStore.addHr(ctx, sensorBpm, "sensor"); log = BioStore.hrLog(ctx) }) { Text("Save $sensorBpm bpm") }
            }
        } else item { Hint("No built-in heart-rate sensor on this phone: use the camera method, or enter a reading from a smartwatch below.") }
        item {
            Section("Add a reading by hand") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(manual, { manual = it }, Modifier.weight(1f), label = { Text("bpm (from a watch / pulse count)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Button(enabled = manual.toIntOrNull() in 30..220, onClick = { BioStore.addHr(ctx, manual.toInt(), "manual"); log = BioStore.hrLog(ctx); manual = "" }) { Text("Add") }
                }
            }
        }
        if (log.isNotEmpty()) item {
            Section("History") {
                LineChart(log.takeLast(40).map { it.bpm.toFloat() }, Bad, 100)
                val resting = log.takeLast(40).map { it.bpm }.sorted().take((log.takeLast(40).size / 4).coerceAtLeast(1)).average().toInt()
                Hint("Estimated resting rate: about $resting bpm · ${log.size} readings")
                log.takeLast(8).reversed().forEach { Text("${fmtDay(it.t)} ${fmtHm(it.t)}  ·  ${it.bpm} bpm  ·  ${it.src}" + if (it.hrv > 0) "  ·  HRV ${it.hrv}" else "", style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { BioStore.clearHr(ctx); log = emptyList() }) { Text("Clear history") }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ sleep

@Composable
private fun Hypnogram(stages: List<Int>) {
    Canvas(Modifier.fillMaxWidth().height(70.dp)) {
        if (stages.isEmpty()) return@Canvas
        val w = size.width / stages.size
        stages.forEachIndexed { i, s ->
            val (c, hh) = when (s) { 2 -> Info to 0.9f; 1 -> Good to 0.55f; else -> Warn to 0.25f }
            drawRect(c, Offset(i * w, size.height * (1 - hh)), Size(w + 0.5f, size.height * hh))
        }
    }
}

@Composable
fun BioSleepScreen() {
    val ctx = LocalContext.current
    var running by remember { mutableStateOf(SleepService.running()) }
    var live by remember { mutableStateOf(SleepService.live) }
    var started by remember { mutableStateOf(SleepService.startedAt) }
    var history by remember { mutableStateOf(BioStore.sleeps(ctx)) }
    var wakeFrom by remember { mutableStateOf("") }
    var wakeTo by remember { mutableStateOf("") }
    var inferred by remember { mutableStateOf<List<SleepAnalyzer.Night>>(emptyList()) }
    var msg by remember { mutableStateOf("") }
    var manualH by remember { mutableStateOf("") }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) { while (true) { running = SleepService.running(); live = SleepService.live; started = SleepService.startedAt; history = BioStore.sleeps(ctx); now = System.currentTimeMillis(); delay(2000) } }
    val last = history.lastOrNull()

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
        item { Text("Sleep", style = MaterialTheme.typography.headlineSmall) }
        item { Disclaimer() }
        item {
            Section("Track tonight", "Put the phone face-down on the mattress near your pillow, plugged in. It senses movement to find when you fell asleep, wake-ups and deep stretches.") {
                if (running) {
                    Text("● Tracking since ${fmtHm(started)} · ${GeoMath.fmtDur((now - started) / 1000)}", color = Good, fontWeight = FontWeight.Bold)
                    if (live.size > 1) LineChart(live.takeLast(240).map { it.toFloat() }, Info, 70)
                    Button(onClick = { SleepService.stop(ctx); msg = "Analysing your night…" }) { Text("☀ I'm awake: stop & analyse") }
                } else {
                    Text("Smart alarm (optional): wake me between", fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(wakeFrom, { wakeFrom = it }, Modifier.weight(1f), label = { Text("from e.g. 06:30") }, singleLine = true)
                        OutlinedTextField(wakeTo, { wakeTo = it }, Modifier.weight(1f), label = { Text("to e.g. 07:00") }, singleLine = true)
                    }
                    Hint("The alarm sounds at your lightest moment inside the window, or at the end of it.")
                    Button(onClick = {
                        if (android.os.Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
                        val f = hmToNext(wakeFrom); val t = hmToNext(wakeTo)
                        SleepService.start(ctx, f ?: 0L, if (f != null && t != null) (if (t < f) t + 86_400_000L else t) else 0L); running = true; msg = ""
                    }) { Text("🌙 Start sleep tracking") }
                }
                if (msg.isNotEmpty()) Hint(msg)
            }
        }
        last?.let { r ->
            item {
                Section("Last night · ${fmtDay(r.endMs)}") {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        ScoreGauge(r.score, 100.dp, "sleep score")
                        Column {
                            Text("${"%.1f".format(r.hours)} h asleep", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text("${fmtHm(r.startMs)} → ${fmtHm(r.endMs)}", style = MaterialTheme.typography.bodySmall)
                            Text("Efficiency ${r.efficiency}% · ${r.awakenings} wake-up(s)", style = MaterialTheme.typography.bodySmall)
                            Text("Took ~${r.onsetMin} min to fall asleep", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (r.stages.isNotEmpty()) {
                        Hypnogram(r.stages)
                        Hint("Blue = quiet/deep-ish · Green = light · Amber = awake. (Phone sensors cannot see REM; stages are estimated from movement.)")
                    }
                    KV("Deep-ish", "${r.deepMin / 60}h ${r.deepMin % 60}m"); KV("Light", "${r.lightMin / 60}h ${r.lightMin % 60}m")
                    Text(when { r.score >= 85 -> "Excellent night."; r.score >= 70 -> "Good night."; r.score >= 50 -> "Fair: short or broken sleep."; else -> "Poor night. Keep a steady schedule, dim screens an hour before bed." }, fontWeight = FontWeight.Medium)
                    TextButton(onClick = { BioStore.deleteSleep(ctx, r.startMs); history = BioStore.sleeps(ctx) }) { Text("Delete this night") }
                }
            }
        }
        item {
            Section("No tracking? Estimate from phone use", "Looks at when your screen stayed off overnight (needs the same Usage access as the Data tab).") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        inferred = SleepAnalyzer.inferNights(BioStore.screenEvents(ctx, 10))
                        msg = if (inferred.isEmpty()) "No overnight gaps found. Grant Usage access, or use the sleep tracker." else ""
                    }) { Text("Estimate last 10 nights") }
                    OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Usage access") }
                }
                inferred.forEach { n ->
                    val known = history.any { Math.abs(it.endMs - n.endMs) < 3_600_000L }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("${n.date}  ${fmtHm(n.startMs)}→${fmtHm(n.endMs)}  ·  ${"%.1f".format(n.hours)} h", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        if (!known) TextButton(onClick = {
                            val mins = ((n.endMs - n.startMs) / 60000).toInt()
                            BioStore.addSleep(ctx, SleepResult(n.startMs, n.endMs, mins, mins, 0, 0, 100, 0, mins, (n.hours / 8 * 90).toInt().coerceIn(0, 90), emptyList(), "screen")); history = BioStore.sleeps(ctx)
                        }) { Text("Save") } else Text("saved", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            Section("Add a night by hand") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(manualH, { manualH = it }, Modifier.weight(1f), label = { Text("Hours slept last night") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    Button(enabled = (manualH.toDoubleOrNull() ?: 0.0) in 0.5..16.0, onClick = {
                        val mins = (manualH.toDouble() * 60).toInt(); val end = System.currentTimeMillis()
                        BioStore.addSleep(ctx, SleepResult(end - mins * 60_000L, end, mins, mins, 0, 0, 100, 0, mins, (manualH.toDouble() / 8 * 90).toInt().coerceIn(0, 90), emptyList(), "manual")); history = BioStore.sleeps(ctx); manualH = ""
                    }) { Text("Add") }
                }
            }
        }
        if (history.size > 1) item {
            Section("Recent nights") {
                BarChart(BioStore.sleepByDay(ctx, 14).map { it.second.toFloat() }, Info, 90)
                history.takeLast(7).reversed().forEach { Text("${fmtDay(it.endMs)}  ·  ${"%.1f".format(it.hours)} h  ·  score ${it.score}  ·  ${it.method}", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ trends

@Composable
fun BioTrendsScreen() {
    val ctx = LocalContext.current
    var range by remember { mutableStateOf("14 days") }
    val days = range.substringBefore(' ').toInt()
    val steps = remember(range) { BioStore.stepHistory(ctx, days.coerceAtLeast(14)) }
    val sleep = remember(range) { BioStore.sleepByDay(ctx, days.coerceAtLeast(14)) }
    val hr = remember(range) { BioStore.hrLog(ctx) }
    val wt = remember(range) { BioStore.weights(ctx) }
    val goal = Prefs.sp(ctx).getInt(Prefs.BIO_STEP_GOAL, 8000)
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
        item { Text("Health trends", style = MaterialTheme.typography.headlineSmall) }
        item { ChipRow(listOf("7 days", "14 days", "30 days"), range) { range = it } }
        item {
            Section("Insights") { BioInsights.generate(steps, sleep, hr, goal).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) } }
        }
        item {
            val s = steps.takeLast(days)
            Section("Steps", "avg ${s.map { it.second }.average().toInt()} per day · best ${s.maxOf { it.second }}") { BarChart(s.map { it.second.toFloat() }, Good, 100) }
        }
        item {
            val s = sleep.takeLast(days)
            val have = s.filter { it.second > 0 }
            Section("Sleep (hours)", if (have.isEmpty()) "no data yet" else "avg ${"%.1f".format(have.map { it.second }.average())} h") { BarChart(s.map { it.second.toFloat() }, Info, 100) }
        }
        if (hr.size >= 2) item { Section("Heart rate", "${hr.size} readings") { LineChart(hr.takeLast(40).map { it.bpm.toFloat() }, Bad, 100) } }
        if (wt.size >= 2) item {
            Section("Weight", "${"%.1f".format(wt.last().second)} kg · change ${"%+.1f".format(wt.last().second - wt.first().second)} kg") { LineChart(wt.takeLast(60).map { it.second.toFloat() }, Warn, 100) }
        }
    }
}
