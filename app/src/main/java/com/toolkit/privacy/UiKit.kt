package com.toolkit.privacy

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Good = Color(0xFF34D399)
val Warn = Color(0xFFFBBF24)
val Bad = Color(0xFFEF4444)
val Info = Color(0xFF60A5FA)
val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)

/** Titled card with a column of content. */
@Composable
fun Section(title: String, sub: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
fun KV(k: String, v: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(k, Modifier.weight(0.38f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, Modifier.weight(0.62f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun ChipRow(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options) { o -> FilterChip(selected = selected == o, onClick = { onSelect(o) }, label = { Text(o) }) }
    }
}

@Composable
fun BigStat(label: String, value: String, color: Color = Color.Unspecified) {
    Column {
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Simple line chart. [values] are plotted left to right; the range auto-fits unless lo/hi are given. */
@Composable
fun LineChart(values: List<Float>, color: Color = Info, height: Int = 110, lo: Float? = null, hi: Float? = null, fill: Boolean = true) {
    Canvas(Modifier.fillMaxWidth().height(height.dp)) {
        if (values.size < 2) return@Canvas
        val mn = lo ?: values.min()
        var mx = hi ?: values.max()
        if (mx - mn < 1e-6f) mx = mn + 1f
        val dx = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * dx
            val y = size.height - (v - mn) / (mx - mn) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        if (fill) {
            val area = Path()
            area.addPath(path)
            area.lineTo(size.width, size.height); area.lineTo(0f, size.height); area.close()
            drawPath(area, color.copy(alpha = 0.15f))
        }
        drawPath(path, color, style = Stroke(width = 2.5.dp.toPx()))
    }
}

/** Vertical bars, one per value. */
@Composable
fun BarChart(values: List<Float>, color: Color = Good, height: Int = 110, highlightLast: Boolean = true) {
    Canvas(Modifier.fillMaxWidth().height(height.dp)) {
        if (values.isEmpty()) return@Canvas
        val mx = (values.max()).coerceAtLeast(1e-6f)
        val slot = size.width / values.size
        val bw = slot * 0.7f
        values.forEachIndexed { i, v ->
            val h = v / mx * size.height
            drawRect(
                if (highlightLast && i == values.size - 1) color else color.copy(alpha = 0.55f),
                topLeft = Offset(i * slot + (slot - bw) / 2f, size.height - h),
                size = androidx.compose.ui.geometry.Size(bw, h)
            )
        }
    }
}

/** Horizontal labelled bars (e.g. spending by category). */
@Composable
fun HBars(items: List<Pair<String, Double>>, color: Color = Info, fmt: (Double) -> String = { "%.0f".format(it) }) {
    val mx = items.maxOfOrNull { it.second }?.coerceAtLeast(1e-9) ?: 1.0
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (n, v) ->
            Row { Text(n, Modifier.weight(1f), maxLines = 1); Text(fmt(v), fontWeight = FontWeight.Medium) }
            PercentBar((v / mx * 100).toInt().coerceIn(0, 100), color, 8)
        }
    }
}

@Composable
fun MonoBox(text: String) {
    Text(text, style = MonoStyle)
}
