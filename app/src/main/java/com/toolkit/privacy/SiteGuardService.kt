package com.toolkit.privacy

import android.animation.ValueAnimator
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Floating "site safety" bubble. A round gauge (1-100) that can be dragged anywhere, coloured red -> yellow -> green.
 * Fed by: the accessibility URL reader (auto), the Share sheet, or the in-app checker.
 */
class SiteGuardService : Service() {
    companion object {
        const val ACTION_START = "com.toolkit.privacy.SG_START"
        const val ACTION_STOP = "com.toolkit.privacy.SG_STOP"
        const val ACTION_CHECK = "com.toolkit.privacy.SG_CHECK"
        const val EXTRA_URL = "url"
        private const val NOTIF_ID = 4101

        @Volatile var instance: SiteGuardService? = null
        @Volatile var last: SiteVerdict? = null
        @Volatile var scanning: Boolean = false

        fun canDraw(ctx: Context) = Settings.canDrawOverlays(ctx)
        fun isRunning() = instance != null

        fun start(ctx: Context, url: String? = null) {
            val i = Intent(ctx, SiteGuardService::class.java).setAction(if (url == null) ACTION_START else ACTION_CHECK)
            if (url != null) i.putExtra(EXTRA_URL, url)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Exception) { }
        }

        fun stop(ctx: Context) {
            Prefs.sp(ctx).edit().putBoolean(Prefs.SG_ON, false).apply()
            ctx.startService(Intent(ctx, SiteGuardService::class.java).setAction(ACTION_STOP))
        }

        /** Used by the accessibility reader: only pushes when the bubble is already running. */
        fun push(url: String) { instance?.check(url) }
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var wm: WindowManager
    private var bubble: BubbleView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var panel: LinearLayout? = null
    private var gen = 0
    private val cache = HashMap<String, Pair<Long, SiteVerdict>>()
    private val warned = HashMap<String, Long>()
    private var currentUrl: String = ""
    private var lastPushUrl = ""
    private var lastPushAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        Prefs.sp(this).edit().putBoolean(Prefs.SG_ON, true).apply()
        if (canDraw(this)) showBubble()
        intent?.getStringExtra(EXTRA_URL)?.let { check(it, force = true) }
        return START_STICKY
    }

    private fun goForeground() {
        val n = Notify.status(this, "Site Guard is on", "Tap the bubble to see why a site scored what it did", tab = 5, sub = 0)
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, n)
    }

    override fun onDestroy() {
        hidePanel()
        bubble?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        bubble = null
        worker.shutdownNow()
        instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ checking

    fun check(raw: String, force: Boolean = false) {
        val p = SiteRules.parse(raw) ?: return
        val now = System.currentTimeMillis()
        if (!force && raw == lastPushUrl && now - lastPushAt < 4000) return
        lastPushUrl = raw; lastPushAt = now
        val key = p.scheme + "://" + p.host + p.path
        currentUrl = p.full
        val cached = cache[key]
        if (!force && cached != null && now - cached.first < 10 * 60_000L) {
            main.post { show(cached.second, scanning = false) }
            return
        }
        val static = SiteRules.analyze(raw)
        val my = ++gen
        main.post { show(static, scanning = true) }
        worker.execute {
            val v = try { SiteCheck.run(raw) } catch (e: Exception) { static }
            main.post {
                if (my != gen) return@post
                cache[key] = System.currentTimeMillis() to v
                show(v, scanning = false)
                warnIfDangerous(v)
            }
        }
    }

    private fun show(v: SiteVerdict, scanning: Boolean) {
        last = v
        SiteGuardService.scanning = scanning
        bubble?.set(v.score, scanning)
        panel?.let { showPanel() } // refresh if open
    }

    private fun warnIfDangerous(v: SiteVerdict) {
        if (v.score >= 45 || v.host.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - (warned[v.host] ?: 0L) < 30 * 60_000L) return
        warned[v.host] = now
        Notify.alert(this, 4200 + (v.host.hashCode() and 0xFF), "Dangerous site: ${v.host}", "Safety score ${v.score}/100. ${topReasons(v, 2)}", urgent = true, tab = 5, sub = 0)
    }

    private fun topReasons(v: SiteVerdict, n: Int) =
        v.findings.filter { it.delta < 0 }.sortedBy { it.delta }.take(n).joinToString(". ") { it.text }

    // ------------------------------------------------------------------ bubble

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private val overlayType get() = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun showBubble() {
        if (bubble != null) return
        val size = dp(64f).toInt()
        val sp = Prefs.sp(this)
        val lp = WindowManager.LayoutParams(
            size, size, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = sp.getInt(Prefs.SG_X, resources.displayMetrics.widthPixels - size - dp(8f).toInt())
            y = sp.getInt(Prefs.SG_Y, (resources.displayMetrics.heightPixels * 0.35f).toInt())
        }
        val b = BubbleView(this)
        last?.let { b.set(it.score, scanning) }
        b.setOnTouchListener(DragTouch(lp))
        try { wm.addView(b, lp); bubble = b; bubbleLp = lp } catch (e: Exception) { }
    }

    private inner class DragTouch(val lp: WindowManager.LayoutParams) : View.OnTouchListener {
        private var downX = 0f; private var downY = 0f
        private var startX = 0; private var startY = 0
        private var dragging = false
        private val slop = ViewConfiguration.get(this@SiteGuardService).scaledTouchSlop

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; dragging = false
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) { dragging = true; hidePanel() }
                    if (dragging) {
                        val m = resources.displayMetrics
                        lp.x = (startX + dx).toInt().coerceIn(0, m.widthPixels - lp.width)
                        lp.y = (startY + dy).toInt().coerceIn(0, m.heightPixels - lp.height)
                        try { wm.updateViewLayout(v, lp) } catch (ex: Exception) { }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    if (dragging) snapToEdge(v, lp) else if (e.actionMasked == MotionEvent.ACTION_UP) togglePanel()
                }
            }
            return true
        }
    }

    private fun snapToEdge(v: View, lp: WindowManager.LayoutParams) {
        val m = resources.displayMetrics
        val margin = dp(6f).toInt()
        val target = if (lp.x + lp.width / 2 < m.widthPixels / 2) margin else m.widthPixels - lp.width - margin
        ValueAnimator.ofInt(lp.x, target).apply {
            duration = 180
            addUpdateListener { a -> lp.x = a.animatedValue as Int; try { wm.updateViewLayout(v, lp) } catch (ex: Exception) { } }
            start()
        }
        Prefs.sp(this).edit().putInt(Prefs.SG_X, target).putInt(Prefs.SG_Y, lp.y).apply()
    }

    // ------------------------------------------------------------------ detail panel

    private fun togglePanel() { if (panel != null) hidePanel() else showPanel() }

    private fun hidePanel() {
        panel?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        panel = null
    }

    private fun tv(text: String, sp: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun showPanel() {
        hidePanel()
        val blp = bubbleLp ?: return
        val v = last
        val m = resources.displayMetrics
        val w = minOf(dp(300f).toInt(), m.widthPixels - dp(24f).toInt())
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(14f).toInt(); setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply { setColor(0xF2121A24.toInt()); cornerRadius = dp(18f) }
        }
        if (v == null) {
            box.addView(tv("No site checked yet", 16f, Color.WHITE, true))
            box.addView(tv("Open a website in your browser (with auto-detect on), or share a link to Privacy Toolkit.", 13f, 0xFFB6C2D0.toInt()))
        } else {
            val c = SiteRules.colorFor(v.score)
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            titleCol.addView(tv(v.host.ifEmpty { "Unknown" }, 15f, Color.WHITE, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            titleCol.addView(tv(if (scanning) "Scanning live signals..." else v.label, 13f, c, true))
            head.addView(titleCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(tv("${v.score}%", 26f, c, true))
            box.addView(head)
            val shown = v.findings.filter { it.delta != 0 }.sortedBy { it.delta }.let { l -> (l.filter { it.delta < 0 }.take(4) + l.filter { it.delta > 0 }.takeLast(1)) }
            if (shown.isEmpty()) box.addView(tv("Nothing suspicious found.", 13f, 0xFFB6C2D0.toInt()).apply { setPadding(0, dp(8f).toInt(), 0, 0) })
            shown.forEach {
                box.addView(tv((if (it.delta < 0) "▼ " else "▲ ") + it.text, 12.5f, if (it.delta < 0) 0xFFFCA5A5.toInt() else 0xFF86EFAC.toInt()).apply { setPadding(0, dp(6f).toInt(), 0, 0) })
            }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12f).toInt(), 0, 0) }
        fun btn(label: String, onClick: () -> Unit) = tv(label, 13f, 0xFF2DD4BF.toInt(), true).apply {
            setPadding(0, dp(6f).toInt(), dp(18f).toInt(), dp(6f).toInt()); setOnClickListener { onClick() }
        }
        if (currentUrl.isNotEmpty()) row.addView(btn("Re-scan") { check(currentUrl, force = true) })
        row.addView(btn("Open app") {
            hidePanel()
            startActivity(Intent(this, MainActivity::class.java).putExtra("tab", 5).putExtra("sub", 0)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        })
        row.addView(btn("Hide bubble") { stop(this) })
        box.addView(row)

        val below = blp.y + blp.height / 2 < m.heightPixels / 2
        val lp = WindowManager.LayoutParams(
            w, WindowManager.LayoutParams.WRAP_CONTENT, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (blp.x + blp.width / 2 - w / 2).coerceIn(dp(12f).toInt(), m.widthPixels - w - dp(12f).toInt())
            y = if (below) blp.y + blp.height + dp(8f).toInt() else (blp.y - dp(8f).toInt() - dp(230f).toInt()).coerceAtLeast(dp(24f).toInt())
        }
        box.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_OUTSIDE) { hidePanel(); true } else false }
        try { wm.addView(box, lp); panel = box } catch (e: Exception) { }
    }

    // ------------------------------------------------------------------ the round gauge

    private class BubbleView(ctx: Context) : View(ctx) {
        private var score = -1
        private var scanning = false
        private var spin = 0f
        private val d = ctx.resources.displayMetrics.density
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEE0E141B.toInt() }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5 * d; color = 0x33FFFFFF }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5 * d; strokeCap = Paint.Cap.ROUND }
        private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; textSize = 20 * d }
        private val pct = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt(); textAlign = Paint.Align.CENTER; textSize = 9 * d }
        private val box = RectF()
        private var anim: ValueAnimator? = null

        fun set(s: Int, scan: Boolean) {
            score = s
            if (scan != scanning) {
                scanning = scan
                anim?.cancel()
                if (scan) anim = ValueAnimator.ofFloat(0f, 360f).apply {
                    duration = 900; repeatCount = ValueAnimator.INFINITE; interpolator = android.view.animation.LinearInterpolator()
                    addUpdateListener { spin = it.animatedValue as Float; invalidate() }; start()
                }
            }
            invalidate()
        }

        override fun onDetachedFromWindow() { anim?.cancel(); super.onDetachedFromWindow() }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val r = minOf(w, h) / 2f
            c.drawCircle(w / 2, h / 2, r - d, bg)
            val inset = 7 * d
            box.set(inset, inset, w - inset, h - inset)
            c.drawOval(box, track)
            val color = if (score < 0) 0xFF8899AA.toInt() else SiteRules.colorFor(score)
            arc.color = color
            when {
                scanning -> c.drawArc(box, spin - 90f, 100f, false, arc)
                score >= 0 -> c.drawArc(box, -90f, 360f * score / 100f, false, arc)
            }
            val text = if (score < 0) "–" else if (scanning && score >= 0) "$score" else "$score"
            c.drawText(text, w / 2, h / 2 + 5 * d, num)
            if (score >= 0) c.drawText("%", w / 2, h / 2 + 17 * d, pct)
        }
    }
}
