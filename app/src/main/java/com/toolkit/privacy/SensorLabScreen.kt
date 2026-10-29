package com.toolkit.privacy

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
private fun Graph(values: List<Float>, color: Color) {
    Canvas(Modifier.fillMaxWidth().height(90.dp)) {
        if (values.size < 2) return@Canvas
        val mn = values.min()
        val mx = values.max()
        val range = (mx - mn).coerceAtLeast(0.0001f)
        val dx = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val y = size.height * (1 - (v - mn) / range) * 0.9f + size.height * 0.05f
            if (i == 0) path.moveTo(0f, y) else path.lineTo(i * dx, y)
        }
        drawPath(path, color, style = Stroke(2.5.dp.toPx()))
    }
}

@Composable
private fun CompassDial(azimuth: Float) {
    val fg = MaterialTheme.colorScheme.onSurface.toArgb()
    val primary = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(260.dp)) {
        val c = center
        val r = size.minDimension / 2 - 8.dp.toPx()
        drawCircle(Color.Gray.copy(alpha = 0.15f), r)
        drawCircle(Color.Gray, r, style = Stroke(2.dp.toPx()))
        val paint = android.graphics.Paint().apply { color = fg; textSize = 18.sp.toPx(); textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true; isFakeBoldText = true }
        rotate(-azimuth, c) {
            for (d in 0 until 360 step 15) {
                val a = Math.toRadians(d.toDouble())
                val len = if (d % 90 == 0) 16.dp.toPx() else if (d % 45 == 0) 12.dp.toPx() else 7.dp.toPx()
                drawLine(Color.Gray, Offset(c.x + (r - len) * sin(a).toFloat(), c.y - (r - len) * cos(a).toFloat()), Offset(c.x + r * sin(a).toFloat(), c.y - r * cos(a).toFloat()), 2.dp.toPx())
            }
            val labels = listOf("N" to 0, "E" to 90, "S" to 180, "W" to 270)
            labels.forEach { (t, d) ->
                val a = Math.toRadians(d.toDouble())
                val rr = r - 36.dp.toPx()
                drawContext.canvas.nativeCanvas.drawText(t, c.x + rr * sin(a).toFloat(), c.y - rr * cos(a).toFloat() + 7.dp.toPx(), paint)
            }
        }
        val tri = Path().apply { moveTo(c.x, c.y - r - 2.dp.toPx()); lineTo(c.x - 9.dp.toPx(), c.y - r + 18.dp.toPx()); lineTo(c.x + 9.dp.toPx(), c.y - r + 18.dp.toPx()); close() }
        drawPath(tri, Color(0xFFE53935))
        drawCircle(primary, 6.dp.toPx(), c)
    }
}

@Composable
private fun LevelBubble(x: Float, y: Float) {
    val ok = Color(0xFF34D399)
    Canvas(Modifier.size(240.dp)) {
        val c = center
        val r = size.minDimension / 2 - 6.dp.toPx()
        drawCircle(Color.Gray.copy(alpha = 0.15f), r)
        drawCircle(Color.Gray, r, style = Stroke(2.dp.toPx()))
        drawLine(Color.Gray, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.dp.toPx())
        drawLine(Color.Gray, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.dp.toPx())
        drawCircle(Color.Gray, 22.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
        val bx = (c.x - x / 9.81f * r * 0.9f).coerceIn(c.x - r, c.x + r)
        val by = (c.y + y / 9.81f * r * 0.9f).coerceIn(c.y - r, c.y + r)
        val level = Math.hypot((bx - c.x).toDouble(), (by - c.y).toDouble()) < 10.dp.toPx()
        drawCircle(if (level) ok else Color(0xFFFBBF24), 18.dp.toPx(), Offset(bx, by))
    }
}

@Composable
fun SensorLabScreen() {
    val ctx = LocalContext.current
    var mode by remember { mutableStateOf("Compass") }
    var v by remember { mutableStateOf(floatArrayOf(0f, 0f, 0f)) }
    var azimuth by remember { mutableFloatStateOf(0f) }
    var available by remember { mutableStateOf(true) }
    val hist = remember { mutableStateListOf<Float>() }

    DisposableEffect(mode) {
        hist.clear()
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val type = when (mode) {
            "Compass" -> Sensor.TYPE_ROTATION_VECTOR
            "Level", "Motion" -> Sensor.TYPE_ACCELEROMETER
            "Barometer" -> Sensor.TYPE_PRESSURE
            "Light" -> Sensor.TYPE_LIGHT
            else -> Sensor.TYPE_MAGNETIC_FIELD
        }
        val sensor = sm.getDefaultSensor(type)
        available = sensor != null
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (mode == "Compass") {
                    val rm = FloatArray(9); val o = FloatArray(3)
                    SensorManager.getRotationMatrixFromVector(rm, e.values)
                    SensorManager.getOrientation(rm, o)
                    azimuth = ((Math.toDegrees(o[0].toDouble()) + 360) % 360).toFloat()
                }
                v = e.values.copyOf()
                val m = if (mode == "Barometer" || mode == "Light") e.values[0] else {
                    val a = e.values; sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
                }
                hist.add(m); if (hist.size > 120) hist.removeAt(0)
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(l) }
    }

    Column(Modifier.fillMaxSize().padding(14.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Sensor lab", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.align(Alignment.Start))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("Compass", "Level", "Barometer", "Light", "Magnetic", "Motion")) { m ->
                FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m) })
            }
        }
        if (!available) {
            Text("This sensor isn't available on this device.", color = MaterialTheme.colorScheme.error)
            return@Column
        }
        val accent = Color(0xFF2DD4BF)
        when (mode) {
            "Compass" -> {
                val dirs = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
                CompassDial(azimuth)
                Text("${azimuth.toInt()}°  ${dirs[((azimuth + 22.5f) / 45f).toInt() % 8]}", fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text("Keep the phone flat and away from magnets for accuracy.", style = MaterialTheme.typography.bodySmall)
            }
            "Level" -> {
                LevelBubble(v[0], v[1])
                val g = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).coerceAtLeast(0.01f)
                val tilt = Math.toDegrees(acos((v[2] / g).coerceIn(-1f, 1f)).toDouble())
                Text("Tilt: %.1f°".format(tilt), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = if (tilt < 1.0) Color(0xFF34D399) else Color.Unspecified)
                Text("Lay the phone flat on a surface. Green = level.", style = MaterialTheme.typography.bodySmall)
            }
            "Barometer" -> {
                val alt = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, v[0])
                Text("%.1f hPa".format(v[0]), fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Text("Approx. altitude: %.0f m (assumes standard sea-level pressure)".format(alt))
                Graph(hist.toList(), accent)
            }
            "Light" -> {
                val lux = v[0]
                val desc = when { lux < 10 -> "Dark"; lux < 200 -> "Dim"; lux < 1000 -> "Indoor"; lux < 10000 -> "Bright"; else -> "Sunlight" }
                Text("%.0f lux".format(lux), fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Text(desc, color = accent, fontWeight = FontWeight.Bold)
                Graph(hist.toList(), accent)
            }
            "Magnetic" -> {
                val m = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
                Text("%.1f µT".format(m), fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Text("x %.1f   y %.1f   z %.1f".format(v[0], v[1], v[2]))
                Text("Earth's field is about 25–65 µT. Higher means a magnet or metal is nearby.", style = MaterialTheme.typography.bodySmall)
                Graph(hist.toList(), accent)
            }
            else -> {
                val m = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
                Text("%.2f g".format(m / 9.81f), fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Text("x %.2f   y %.2f   z %.2f m/s²".format(v[0], v[1], v[2]))
                Graph(hist.toList(), accent)
            }
        }
    }
}
