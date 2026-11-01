package com.toolkit.privacy.home

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.toolkit.privacy.Prefs
import com.toolkit.privacy.core.ThemeMode
import com.toolkit.privacy.core.ThemeState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Saved choice for the animated home background (Settings > Live background). */
object LiveBg {
    /** id, title, one-line description */
    val styles = listOf(
        Triple("auto", "Auto (follows theme)", "Powder Black: stars · Butter White: bots · Follow system: code · System colors: cells · Custom: lightning"),
        Triple("stars", "Shooting stars", "Twinkling sky with shooting stars"),
        Triple("dev", "Dev vibes", "Code snippets floating up the screen"),
        Triple("bots", "Floating bots", "Robots and gears drifting by"),
        Triple("cells", "Cell towers", "Phones, dishes and expanding signal rings"),
        Triple("bolts", "Lightning", "Floating bolts and a sky flash now and then"),
        Triple("off", "Off", "Plain background")
    )

    var style by mutableStateOf("auto")
        private set
    var level by mutableIntStateOf(2)
        private set
    private var loaded = false

    fun ensure(ctx: android.content.Context) {
        if (loaded) return
        loaded = true
        val sp = Prefs.sp(ctx)
        style = sp.getString("live_style", "auto") ?: "auto"
        level = sp.getInt("live_level", 2).coerceIn(1, 3)
    }

    fun setStyle(ctx: android.content.Context, s: String) { style = s; Prefs.sp(ctx).edit().putString("live_style", s).apply() }
    fun setLevel(ctx: android.content.Context, l: Int) { level = l; Prefs.sp(ctx).edit().putInt("live_level", l).apply() }

    fun resolved(): String = if (style != "auto") style else when (ThemeState.mode) {
        ThemeMode.POWDER -> "stars"
        ThemeMode.BUTTER -> "bots"
        ThemeMode.AUTO -> "dev"
        ThemeMode.SYSTEM -> "cells"
        ThemeMode.CUSTOM -> "bolts"
    }
}

/** Stable pseudo-random number in 0..1 for (item, slot). Lets every particle be a pure function of time. */
private fun rnd(i: Int, k: Int): Float {
    var h = i * 374761393 + k * 668265263
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return (h and 0x7fffffff) / 2147483647f
}

private val CODE = listOf(
    "{ }", "</>", "fun()", "0101", "git push", "def main():", "=>", "import os", "npm i", "while(1)", "#include",
    "SELECT *", "200 OK", "sudo", "</div>", "async", "true", "0xFF", "lambda", "return;", "pip install", "&&", "class App", "val x = 1"
)

private fun DrawScope.glyph(paint: Paint, txt: String, x: Float, y: Float, sizePx: Float, alpha: Float, color: Int, mono: Boolean, rot: Float = 0f) {
    drawIntoCanvas { c ->
        val nc = c.nativeCanvas
        paint.textSize = sizePx
        paint.color = color
        paint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
        paint.typeface = if (mono) Typeface.MONOSPACE else Typeface.DEFAULT
        paint.textAlign = Paint.Align.CENTER
        nc.save(); nc.rotate(rot, x, y); nc.drawText(txt, x, y, paint); nc.restore()
    }
}

/** A thing that drifts up the screen forever: returns y for item i at time t. */
private fun rise(t: Float, i: Int, speed: Float, h: Float, pad: Float): Float {
    val span = h + pad * 2
    val p = (t * speed + rnd(i, 90) * span) % span
    return h + pad - p
}

private fun fadeEdge(y: Float, h: Float): Float {
    val m = h * 0.14f
    return (minOf(y, h - y) / m).coerceIn(0f, 1f)
}

/**
 * Animated background for the home screen. Colours come from the active theme (accent + text colour),
 * so it changes with Appearance. Draws only; nothing is stored or sent.
 */
@Composable
fun LiveBackground(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    LiveBg.ensure(ctx)
    val style = LiveBg.resolved()
    if (style == "off") return
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(style) {
        val t0 = androidx.compose.runtime.withFrameNanos { it }
        while (true) androidx.compose.runtime.withFrameNanos { t = (it - t0) / 1_000_000_000f }
    }
    val accent = MaterialTheme.colorScheme.primary
    val fg = MaterialTheme.colorScheme.onBackground
    val light = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val n = when (LiveBg.level) { 1 -> 0.55f; 3 -> 1.6f; else -> 1f }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val boost = if (light) 1.35f else 1f

    Canvas(modifier.fillMaxSize()) {
        val w = size.width; val h = size.height; val d = density
        when (style) {
            "stars" -> {
                val dotColor = if (light) accent else Color.White
                for (i in 0 until (46 * n).toInt()) {
                    val a = (0.22f + 0.38f * (0.5f + 0.5f * sin(t * (0.7f + rnd(i, 3) * 1.6f) + rnd(i, 4) * 6.28f))) * boost
                    drawCircle(dotColor.copy(alpha = a.coerceIn(0f, 1f)), (0.7f + rnd(i, 5) * 1.5f) * d, Offset(rnd(i, 1) * w, rnd(i, 2) * h))
                }
                for (i in 0 until (5 * n).toInt().coerceAtLeast(2)) {
                    val period = 3.5f + rnd(i, 10) * 4.5f
                    val tt = t + rnd(i, 11) * period
                    val cycle = floor(tt / period).toInt()
                    val f = (tt % period) / 1.3f
                    if (f in 0f..1f) {
                        val key = i * 97 + cycle
                        val sx = rnd(key, 12) * w * 1.1f - w * 0.1f
                        val sy = rnd(key, 13) * h * 0.55f
                        val ang = 0.45f + rnd(key, 14) * 0.35f
                        val dx = cos(ang); val dy = sin(ang)
                        val dist = w * 0.85f
                        val head = Offset(sx + dx * dist * f, sy + dy * dist * f)
                        val tail = Offset(head.x - dx * 120f * d, head.y - dy * 120f * d)
                        val env = sin(PI.toFloat() * f)
                        val col = if (light) accent else Color.White
                        drawLine(
                            Brush.linearGradient(listOf(Color.Transparent, col.copy(alpha = (0.9f * env).coerceIn(0f, 1f))), tail, head),
                            tail, head, strokeWidth = 2.2f * d, cap = StrokeCap.Round
                        )
                        drawCircle(accent.copy(alpha = (0.9f * env).coerceIn(0f, 1f)), 2.6f * d, head)
                    }
                }
            }
            "dev" -> {
                val col = if (light) accent.toArgb() else accent.toArgb()
                for (i in 0 until (24 * n).toInt()) {
                    val y = rise(t, i, (14f + rnd(i, 1) * 34f) * d, h, 30f * d)
                    val x = rnd(i, 2) * w + sin(t * 0.5f + rnd(i, 3) * 6f) * 10f * d
                    val a = fadeEdge(y, h) * (0.16f + 0.2f * rnd(i, 4)) * boost
                    glyph(paint, CODE[(i * 7 + rnd(i, 5) * 100).toInt() % CODE.size], x, y, (11f + rnd(i, 6) * 8f) * d, a, if (rnd(i, 7) > 0.65f) fg.toArgb() else col, true)
                }
            }
            "bots" -> {
                val icons = listOf("🤖", "🦾", "⚙️", "🤖", "🔧")
                for (i in 0 until (10 * n).toInt()) {
                    val y = rise(t, i, (9f + rnd(i, 1) * 20f) * d, h, 50f * d)
                    val x = rnd(i, 2) * w + sin(t * (0.4f + rnd(i, 3) * 0.6f) + rnd(i, 4) * 6f) * 26f * d
                    val a = fadeEdge(y, h) * (0.2f + 0.16f * rnd(i, 5)) * boost
                    glyph(paint, icons[(rnd(i, 6) * icons.size).toInt() % icons.size], x, y, (22f + rnd(i, 7) * 26f) * d, a, 0xFFFFFFFF.toInt(), false, sin(t * 0.7f + rnd(i, 8) * 6f) * 12f)
                }
            }
            "cells" -> {
                for (i in 0 until (3 * n).toInt().coerceAtLeast(2)) {
                    val cx = (0.15f + rnd(i, 1) * 0.7f) * w; val cy = (0.12f + rnd(i, 2) * 0.76f) * h
                    for (k in 0..2) {
                        val p = (t * 0.28f + rnd(i, 3) + k / 3f) % 1f
                        drawCircle(accent.copy(alpha = ((1f - p) * 0.38f * boost).coerceIn(0f, 1f)), p * 150f * d, Offset(cx, cy), style = Stroke(1.6f * d))
                    }
                    glyph(paint, "📡", cx, cy + 8f * d, 24f * d, 0.5f * boost, 0xFFFFFFFF.toInt(), false)
                }
                val icons = listOf("📱", "📶", "📱", "📲")
                for (i in 0 until (7 * n).toInt()) {
                    val ii = i + 40
                    val y = rise(t, ii, (10f + rnd(ii, 1) * 22f) * d, h, 40f * d)
                    val x = rnd(ii, 2) * w + sin(t * 0.5f + rnd(ii, 3) * 6f) * 18f * d
                    glyph(paint, icons[(rnd(ii, 4) * icons.size).toInt() % icons.size], x, y, (20f + rnd(ii, 5) * 16f) * d, fadeEdge(y, h) * 0.26f * boost, 0xFFFFFFFF.toInt(), false, sin(t + rnd(ii, 6) * 6f) * 10f)
                }
            }
            "bolts" -> {
                for (i in 0 until (11 * n).toInt()) {
                    val y = rise(t, i, (12f + rnd(i, 1) * 26f) * d, h, 40f * d)
                    val x = rnd(i, 2) * w + sin(t * 0.6f + rnd(i, 3) * 6f) * 14f * d
                    val flick = 0.55f + 0.45f * sin(t * (2f + rnd(i, 4) * 3f) + rnd(i, 5) * 6f)
                    glyph(paint, "⚡", x, y, (20f + rnd(i, 6) * 22f) * d, fadeEdge(y, h) * 0.3f * flick * boost, 0xFFFFFFFF.toInt(), false, sin(t * 0.8f + rnd(i, 7) * 6f) * 14f)
                }
                val period = 6.5f
                val cycle = floor(t / period).toInt()
                val f = (t % period) / 0.7f
                if (f in 0f..1f) {
                    val env = (if (f < 0.25f) f / 0.25f else (1f - f) / 0.75f) * (0.7f + 0.3f * sin(f * 40f))
                    val path = Path()
                    var x = (0.2f + rnd(cycle, 1) * 0.6f) * w; var y = 0f
                    path.moveTo(x, y)
                    var s = 0
                    while (y < h * 0.62f) { s++; y += (28f + rnd(cycle * 31 + s, 2) * 40f) * d; x += (rnd(cycle * 31 + s, 3) - 0.5f) * 70f * d; path.lineTo(x, y) }
                    drawRect(accent.copy(alpha = (0.07f * env).coerceIn(0f, 1f)))
                    drawPath(path, accent.copy(alpha = (0.28f * env).coerceIn(0f, 1f)), style = Stroke(9f * d, cap = StrokeCap.Round))
                    drawPath(path, (if (light) accent else Color.White).copy(alpha = (0.9f * env).coerceIn(0f, 1f)), style = Stroke(2.4f * d, cap = StrokeCap.Round))
                }
            }
        }
    }
}

/** Settings > Live background */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveBgPage() {
    val ctx = LocalContext.current
    LiveBg.ensure(ctx)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.background)
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(18.dp))
        ) {
            LiveBackground()
            Text(
                "Preview", Modifier.align(Alignment.BottomStart).padding(12.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text("Style", fontWeight = FontWeight.Bold)
        LiveBg.styles.forEach { (id, title, desc) ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { LiveBg.setStyle(ctx, id) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = LiveBg.style == id, onClick = null, modifier = Modifier.padding(horizontal = 8.dp))
                Column(Modifier.weight(1f)) {
                    Text(title)
                    Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text("Amount", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1 to "Calm", 2 to "Normal", 3 to "Busy").forEach { (l, name) ->
                FilterChip(selected = LiveBg.level == l, onClick = { LiveBg.setLevel(ctx, l) }, label = { Text(name) })
            }
        }
        Text(
            "Colours follow your theme (Settings > Appearance). The animation runs only on the home screen and pauses when the app is in the background.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
