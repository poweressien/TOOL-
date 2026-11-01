package com.toolkit.privacy

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.*
import kotlin.random.Random

/** Every way the organizer can show your apps. Grid and List are normal scrolling lists; the rest are live animated stages. */
enum class OrgStyle(val title: String, val live: Boolean, val hint: String) {
    GRID("Grid", false, ""),
    LIST("List", false, ""),
    DRIFT("Floating", true, "Apps drift around and bounce off the walls."),
    SHUFFLE("Swap", true, "Apps swap places with each other on a timer."),
    SPHERE("Sphere", true, "A 3D ball of apps. Drag to spin it."),
    RING("Carousel", true, "3D ring(s) of apps. Drag to spin."),
    CUBE("Cube", true, "Apps on the six faces of a cube. Drag to turn it."),
    CYLINDER("Helix", true, "Apps on a spinning 3D spiral. Drag to spin."),
    ORBIT("Orbit", true, "Apps circling in rings, like planets."),
    SPIRAL("Spiral", true, "A sunflower spiral of apps that slowly turns."),
    WAVE("Wave", true, "A grid that ripples like water."),
    HONEY("Honeycomb", true, "Hex-packed apps that breathe."),
    BUBBLES("Bubbles", true, "Apps rise like bubbles, each a different size.");

    companion object {
        fun from(name: String?): OrgStyle = values().firstOrNull { it.name == name || it.title == name } ?: GRID
    }
}

/** Mutable animation state for one live stage. Poses are rewritten every frame by [update]. */
class OrgScene(val n: Int) {
    val x = FloatArray(n); val y = FloatArray(n); val s = FloatArray(n) { 1f }; val a = FloatArray(n) { 1f }
    val z = FloatArray(n)
    val tick = mutableLongStateOf(0L)
    val rank = mutableStateListOf<Int>().also { l -> repeat(n) { l.add(0) } }

    private val rnd = Random(System.nanoTime())
    private val vx = FloatArray(n); private val vy = FloatArray(n); private val bx = FloatArray(n)
    private val seed = FloatArray(n) { rnd.nextFloat() }
    private var perm = IntArray(n) { it }
    private val pop = FloatArray(n)
    private var t = 0f
    private var swapTimer = 0f
    private var yaw = 0f; private var pitch = 0.3f
    private var yawVel = 0.4f
    private var lastStyle: OrgStyle? = null
    private var lastRank = 0f

    fun drag(dx: Float, dy: Float) { yaw += dx * 0.01f; pitch = (pitch - dy * 0.01f).coerceIn(-1.2f, 1.2f); yawVel = dx * 0.5f }

    /** How many apps this layout can show at once. */
    fun capacity(style: OrgStyle, w: Float, h: Float, size: Float): Int = when (style) {
        OrgStyle.SHUFFLE, OrgStyle.WAVE -> {
            val cols = max(3, (w / (size * 1.45f)).toInt()); val rows = max(2, (h / (size * 1.55f)).toInt()); cols * rows
        }
        OrgStyle.HONEY -> {
            val cols = max(3, (w / (size * 1.25f)).toInt()); val rows = max(2, (h / (size * 1.1f)).toInt()); cols * rows - rows / 2
        }
        OrgStyle.CUBE -> 54
        OrgStyle.SPHERE -> 90
        OrgStyle.ORBIT -> 60
        else -> 70
    }

    private fun reset(style: OrgStyle, w: Float, h: Float, size: Float) {
        perm = IntArray(n) { it }; swapTimer = 0f
        for (i in 0 until n) {
            x[i] = size + rnd.nextFloat() * max(1f, w - 2 * size); y[i] = size + rnd.nextFloat() * max(1f, h - 2 * size)
            val ang = rnd.nextFloat() * 6.2832f; val sp = 40f + rnd.nextFloat() * 70f
            vx[i] = cos(ang) * sp; vy[i] = sin(ang) * sp
            s[i] = 1f; a[i] = 1f; pop[i] = 0f
        }
        for (i in 0 until n) bx[i] = x[i]
        if (style == OrgStyle.BUBBLES) for (i in 0 until n) { y[i] = rnd.nextFloat() * h }
    }

    fun hit(px: Float, py: Float, size: Float): Int {
        var best = -1; var bestZ = -1e9f
        for (i in 0 until n) {
            if (a[i] <= 0.05f) continue
            val r = size * s[i] * 0.8f
            if (abs(px - x[i]) <= r && abs(py - y[i]) <= r && z[i] > bestZ) { best = i; bestZ = z[i] }
        }
        return best
    }

    fun update(style: OrgStyle, dt: Float, w: Float, h: Float, size: Float, intervalSec: Int) {
        if (lastStyle != style) { lastStyle = style; reset(style, w, h, size); lastRank = 0f }
        t += dt
        val cx = w / 2f; val cy = h / 2f
        when (style) {
            OrgStyle.DRIFT -> for (i in 0 until n) {
                // slow random steering so the motion never looks like a plain bounce
                val steer = sin(t * (0.5f + seed[i]) + seed[i] * 20f) * 25f * dt
                vx[i] += steer; vy[i] += cos(t * (0.6f + seed[i]) + seed[i] * 9f) * 25f * dt
                val sp = hypot(vx[i], vy[i]); if (sp > 130f) { vx[i] *= 130f / sp; vy[i] *= 130f / sp } else if (sp < 35f) { vx[i] *= 1.05f; vy[i] *= 1.05f }
                x[i] += vx[i] * dt; y[i] += vy[i] * dt
                val m = size * 0.6f
                if (x[i] < m) { x[i] = m; vx[i] = abs(vx[i]) } else if (x[i] > w - m) { x[i] = w - m; vx[i] = -abs(vx[i]) }
                if (y[i] < m) { y[i] = m; vy[i] = abs(vy[i]) } else if (y[i] > h - m) { y[i] = h - m; vy[i] = -abs(vy[i]) }
                s[i] = 0.9f + 0.15f * sin(t * 1.3f + seed[i] * 6f); a[i] = 1f; z[i] = 0f
            }
            OrgStyle.BUBBLES -> for (i in 0 until n) {
                val big = 0.7f + seed[i] * 0.8f
                y[i] -= (22f + 50f * (1.4f - big + 0.3f)) * dt
                if (y[i] < -size) { y[i] = h + size; bx[i] = size + rnd.nextFloat() * max(1f, w - 2 * size) }
                x[i] = (bx[i] + sin(t * (0.6f + seed[i]) + seed[i] * 12f) * size * 0.5f).coerceIn(size * 0.5f, w - size * 0.5f)
                s[i] = big; a[i] = 0.92f; z[i] = big
            }
            OrgStyle.SHUFFLE -> {
                val cols = max(3, (w / (size * 1.45f)).toInt()); val rows = max(1, ceil(n / cols.toFloat()).toInt())
                val cw = w / cols; val ch = min(size * 1.7f, h / rows)
                swapTimer += dt
                if (swapTimer >= intervalSec.coerceIn(1, 60)) {
                    swapTimer = 0f
                    val pairs = max(2, n / 5)
                    repeat(pairs) { val p = rnd.nextInt(n); val q = rnd.nextInt(n); if (p != q) { val tmp = perm[p]; perm[p] = perm[q]; perm[q] = tmp; pop[p] = 1f; pop[q] = 1f } }
                }
                val k = 1f - exp(-dt * 7f)
                for (i in 0 until n) {
                    val slot = perm[i]
                    val tx = cw * (slot % cols + 0.5f); val ty = ch * (slot / cols + 0.5f) + size * 0.2f
                    x[i] += (tx - x[i]) * k; y[i] += (ty - y[i]) * k
                    pop[i] = max(0f, pop[i] - dt * 1.6f)
                    s[i] = 1f + 0.35f * sin(pop[i] * 3.1416f); a[i] = 1f; z[i] = pop[i]
                }
            }
            OrgStyle.WAVE -> {
                val cols = max(3, (w / (size * 1.45f)).toInt()); val rows = max(1, ceil(n / cols.toFloat()).toInt())
                val cw = w / cols; val ch = min(size * 1.7f, h / rows)
                for (i in 0 until n) {
                    val c = i % cols; val r = i / cols
                    val ph = t * 2.4f + c * 0.65f + r * 0.45f
                    x[i] = cw * (c + 0.5f) + cos(ph) * size * 0.18f
                    y[i] = ch * (r + 0.5f) + size * 0.2f + sin(ph) * size * 0.35f
                    s[i] = 1f + 0.18f * sin(ph); a[i] = 0.75f + 0.25f * sin(ph + 1f); z[i] = s[i]
                }
            }
            OrgStyle.HONEY -> {
                val cols = max(3, (w / (size * 1.25f)).toInt()); val dx = w / (cols + 0.5f); val dy = size * 1.1f
                val rows = max(1, ceil(n / cols.toFloat()).toInt()); val top = max(size * 0.7f, (h - rows * dy) / 2f)
                for (i in 0 until n) {
                    val c = i % cols; val r = i / cols
                    x[i] = dx * (c + 0.5f) + if (r % 2 == 1) dx / 2f else 0f
                    y[i] = top + r * dy
                    s[i] = 1f + 0.08f * sin(t * 1.6f + c * 0.5f + r * 0.7f); a[i] = 1f; z[i] = 0f
                }
            }
            OrgStyle.ORBIT -> {
                var ring = 0; var placed = 0
                val maxR = min(w, h) / 2f - size * 0.7f
                var idxInRing = 0; var cap = 1; var radius = 0f; var dir = 1f
                for (i in 0 until n) {
                    if (idxInRing >= cap) {
                        ring++; placed = 0; idxInRing = 0; radius = size * 1.35f * ring + size * 0.2f
                        cap = max(4, (2f * 3.1416f * radius / (size * 1.25f)).toInt()); dir = if (ring % 2 == 0) 1f else -1f
                    }
                    val r = min(radius, maxR)
                    val ang = (idxInRing.toFloat() / cap) * 6.2832f + dir * t * (0.55f / (1f + ring * 0.45f))
                    x[i] = cx + cos(ang) * r; y[i] = cy + sin(ang) * r
                    s[i] = if (ring == 0) 1.25f else 1f; a[i] = 1f; z[i] = 0f
                    idxInRing++; placed++
                }
            }
            OrgStyle.SPIRAL -> {
                val maxR = min(w, h) / 2f - size * 0.7f; val c = maxR / sqrt(max(1, n).toFloat())
                for (i in 0 until n) {
                    val r = c * sqrt(i + 0.5f); val ang = i * 2.39996f + t * 0.25f
                    x[i] = cx + cos(ang) * r; y[i] = cy + sin(ang) * r
                    s[i] = 0.7f + 0.45f * (i / max(1f, n.toFloat())); a[i] = 1f; z[i] = s[i]
                }
            }
            OrgStyle.SPHERE, OrgStyle.RING, OrgStyle.CUBE, OrgStyle.CYLINDER -> {
                yaw += (yawVel) * dt
                yawVel += (0.4f - yawVel) * (1f - exp(-dt * 1.5f))
                val basePitch = when (style) { OrgStyle.SPHERE -> 0.25f; OrgStyle.RING -> 0.35f; OrgStyle.CUBE -> 0.45f; else -> 0.15f }
                pitch += (basePitch - pitch) * (1f - exp(-dt * 0.25f))
                val cy0 = cos(yaw); val sy = sin(yaw); val cp = cos(pitch); val sp = sin(pitch)
                val rad = min(w, h) / 2f - size * 0.8f
                val dist = rad * 3.2f
                for (i in 0 until n) {
                    var px = 0f; var py = 0f; var pz = 0f
                    when (style) {
                        OrgStyle.SPHERE -> {
                            val yy = 1f - 2f * (i + 0.5f) / n; val rr = sqrt(max(0f, 1f - yy * yy)); val th = i * 2.39996f
                            px = cos(th) * rr * rad; py = yy * rad; pz = sin(th) * rr * rad
                        }
                        OrgStyle.RING -> {
                            val rings = max(1, ceil(n / 14f).toInt()); val per = ceil(n / rings.toFloat()).toInt()
                            val ri = i / per; val k = i % per; val ang = k * 6.2832f / per + ri * 0.4f
                            val rr = min(w * 0.4f, rad * 1.1f)
                            px = cos(ang) * rr; pz = sin(ang) * rr; py = (ri - (rings - 1) / 2f) * size * 1.6f
                        }
                        OrgStyle.CYLINDER -> {
                            val ang = i * 0.62f; val rr = rad * 0.8f
                            px = cos(ang) * rr; pz = sin(ang) * rr; py = (i / max(1f, n - 1f) - 0.5f) * rad * 1.7f
                        }
                        else -> { // cube: n apps spread over 6 faces
                            val g = max(1, ceil(sqrt(n / 6f)).toInt()); val face = i / (g * g); val k = i % (g * g)
                            val u = ((k % g) - (g - 1) / 2f) * (2f / g); val v = ((k / g) - (g - 1) / 2f) * (2f / g)
                            val hh = rad * 0.62f
                            val (fx, fy, fz) = when (face % 6) {
                                0 -> Triple(u, v, 1f); 1 -> Triple(-u, v, -1f); 2 -> Triple(1f, v, -u)
                                3 -> Triple(-1f, v, u); 4 -> Triple(u, 1f, -v); else -> Triple(u, -1f, v)
                            }
                            px = fx * hh; py = fy * hh; pz = fz * hh
                        }
                    }
                    val x1 = px * cy0 + pz * sy; val z1 = -px * sy + pz * cy0
                    val y2 = py * cp - z1 * sp; val z2 = py * sp + z1 * cp
                    val f = dist / (dist - z2)
                    x[i] = cx + x1 * f; y[i] = cy + y2 * f; s[i] = f * 0.85f; z[i] = z2
                    a[i] = (0.35f + 0.65f * ((z2 / rad + 1f) / 2f)).coerceIn(0.25f, 1f)
                }
                lastRank += dt
                if (lastRank > 0.2f) {
                    lastRank = 0f
                    val order = (0 until n).sortedBy { z[it] }
                    for ((r, idx) in order.withIndex()) if (rank[idx] != r) rank[idx] = r
                }
            }
            else -> {}
        }
    }
}

/** One app on a stage: positioned only inside graphicsLayer so per-frame motion never recomposes. */
@Composable
private fun StageItem(i: Int, app: OrgApp, icon: ImageBitmap?, scene: OrgScene, iconDp: Int) {
    val d = LocalDensity.current
    val itemW = with(d) { (iconDp * 1.5f).dp.toPx() }; val itemH = with(d) { (iconDp + 18).dp.toPx() }
    val z = if (i < scene.rank.size) scene.rank[i] else 0
    Column(
        Modifier.zIndex(z.toFloat()).size((iconDp * 1.5f).dp, (iconDp + 18).dp).graphicsLayer {
            scene.tick.longValue
            translationX = scene.x[i] - itemW / 2f; translationY = scene.y[i] - itemH / 2f
            scaleX = scene.s[i]; scaleY = scene.s[i]; alpha = scene.a[i]
        },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (icon != null) Image(icon, null, Modifier.size(iconDp.dp)) else Box(Modifier.size(iconDp.dp))
        Text(app.label, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface)
    }
}

/** The animated stage for every live style. [apps] is already filtered and sorted; the first N that fit are shown. */
@Composable
fun OrgStage(
    style: OrgStyle, apps: List<OrgApp>, icons: Map<String, ImageBitmap>, intervalSec: Int,
    onTap: (OrgApp) -> Unit, onLong: (OrgApp) -> Unit, onShown: (Int) -> Unit
) {
    val density = LocalDensity.current
    var box by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val iconDp = 46
    val sizePx = with(density) { iconDp.dp.toPx() }
    val probe = remember { OrgScene(1) }
    val w = box.width.toFloat(); val h = box.height.toFloat()
    val cap = if (w > 0f) probe.capacity(style, w, h, sizePx) else 0
    val list = remember(apps, cap) { apps.take(cap) }
    LaunchedEffect(list.size) { onShown(list.size) }

    Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged { box = it }) {
        if (list.isNotEmpty() && w > 0f) {
            val scene = remember(list.size, style) { OrgScene(list.size) }
            LaunchedEffect(scene, style, w, h, intervalSec) {
                var last = 0L
                while (true) {
                    withFrameNanos { now ->
                        val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0.001f, 0.05f)
                        last = now
                        scene.update(style, dt, w, h, sizePx, intervalSec)
                        scene.tick.longValue = now
                    }
                }
            }
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(scene, list) {
                        detectTapGestures(
                            onTap = { o -> scene.hit(o.x, o.y, sizePx).let { if (it >= 0) onTap(list[it]) } },
                            onLongPress = { o -> scene.hit(o.x, o.y, sizePx).let { if (it >= 0) onLong(list[it]) } }
                        )
                    }
                    .pointerInput(scene, style) {
                        detectDragGestures { change, d -> if (style.name in DRAGGABLE) { change.consume(); scene.drag(d.x, d.y) } }
                    }
            ) {
                list.forEachIndexed { i, app -> key(app.pkg) { StageItem(i, app, icons[app.pkg], scene, iconDp) } }
            }
        }
    }
}

private val DRAGGABLE = setOf("SPHERE", "RING", "CUBE", "CYLINDER")

