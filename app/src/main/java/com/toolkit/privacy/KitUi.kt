package com.toolkit.privacy

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Page scaffold for the newer tools: scrollable column with a title. */
@Composable
fun Page(title: String, sub: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun Sec(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
fun Kv(k: String, v: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(k, Modifier.weight(0.42f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, Modifier.weight(0.58f), style = MaterialTheme.typography.bodyMedium, fontFamily = if (mono) FontFamily.Monospace else null)
    }
}

@Composable
fun Note(text: String, warn: Boolean = false) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = if (warn) Color(0xFFEF4444) else MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun Chips(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(options.size) { i ->
            FilterChip(selected = options[i] == selected, onClick = { onSelect(options[i]) }, label = { Text(options[i]) })
        }
    }
}

@Composable
fun KLineChart(values: List<Float>, color: Color = Color(0xFF2DD4BF), height: Int = 110, minY: Float? = null, maxY: Float? = null) {
    Canvas(Modifier.fillMaxWidth().height(height.dp)) {
        if (values.size < 2) return@Canvas
        val mn = minY ?: values.min()
        val mx = maxY ?: values.max()
        val range = (mx - mn).coerceAtLeast(0.0001f)
        val p = Path()
        values.forEachIndexed { i, v ->
            val x = size.width * i / (values.size - 1)
            val y = size.height - (v - mn) / range * (size.height - 8f) - 4f
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        drawLine(Color.Gray.copy(alpha = 0.3f), Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 1f)
        drawPath(p, color, style = Stroke(2.5f))
    }
}

@Composable
fun KBarChart(values: List<Float>, labels: List<String> = emptyList(), color: Color = Color(0xFF60A5FA), height: Int = 120) {
    val txt = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.fillMaxWidth().height(height.dp)) {
        if (values.isEmpty()) return@Canvas
        val mx = values.max().coerceAtLeast(0.0001f)
        val gap = 4f
        val bw = (size.width - gap * (values.size - 1)) / values.size
        val base = size.height - 14f
        val paint = android.graphics.Paint().apply { this.color = android.graphics.Color.argb(200, (txt.red * 255).toInt(), (txt.green * 255).toInt(), (txt.blue * 255).toInt()); textSize = 22f; textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true }
        values.forEachIndexed { i, v ->
            val h = (base - 4f) * (v / mx)
            val x = i * (bw + gap)
            drawRect(color, Offset(x, base - h), Size(bw, h))
            if (labels.size == values.size) drawContext.canvas.nativeCanvas.drawText(labels[i], x + bw / 2, size.height - 1f, paint)
        }
    }
}

/** Power spectrum line + peak marker. x = bins, y = dB. */
@Composable
fun SpectrumChart(db: FloatArray, minDb: Float = -90f, maxDb: Float = -10f, color: Color = Color(0xFF34D399), height: Int = 160) {
    Canvas(Modifier.fillMaxWidth().height(height.dp)) {
        if (db.size < 2) return@Canvas
        val range = maxDb - minDb
        val p = Path()
        val step = (db.size / size.width.coerceAtLeast(1f)).coerceAtLeast(1f)
        var first = true
        var x = 0f
        while (x < size.width) {
            val i = (x * db.size / size.width).toInt().coerceIn(0, db.size - 1)
            val v = db[i]
            val y = size.height - ((v - minDb) / range).coerceIn(0f, 1f) * size.height
            if (first) { p.moveTo(x, y); first = false } else p.lineTo(x, y)
            x += step.coerceAtLeast(1f)
        }
        for (g in 1..3) drawLine(Color.Gray.copy(alpha = 0.2f), Offset(0f, size.height * g / 4), Offset(size.width, size.height * g / 4), 1f)
        drawPath(p, color, style = Stroke(2f))
    }
}

/** Waterfall: rows of dB values drawn as colour pixels. */
@Composable
fun Waterfall(rows: List<FloatArray>, minDb: Float = -90f, maxDb: Float = -20f, height: Int = 160) {
    Canvas(Modifier.fillMaxWidth().height(height.dp)) {
        if (rows.isEmpty()) return@Canvas
        val rh = size.height / rows.size.coerceAtLeast(1)
        val cols = 160
        val cw = size.width / cols
        rows.forEachIndexed { r, row ->
            if (row.isEmpty()) return@forEachIndexed
            for (c in 0 until cols) {
                val v = row[(c * row.size / cols).coerceIn(0, row.size - 1)]
                val t = ((v - minDb) / (maxDb - minDb)).coerceIn(0f, 1f)
                val col = Color.hsv((240f - 240f * t).coerceIn(0f, 240f), 0.9f, 0.25f + 0.75f * t)
                drawRect(col, Offset(c * cw, r * rh), Size(cw + 0.5f, rh + 0.5f))
            }
        }
    }
}

@Composable
fun BigNumber(value: String, unit: String, color: Color = MaterialTheme.colorScheme.primary) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(value, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = color)
        Text(unit, Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
