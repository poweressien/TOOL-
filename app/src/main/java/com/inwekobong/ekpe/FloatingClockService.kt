package com.inwekobong.ekpe

import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.*
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FloatingClockService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var wm: WindowManager
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var root: FrameLayout
    private lateinit var timeTv: TextView
    private lateinit var dateTv: TextView
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { updateText(); h.postDelayed(this, 1000) } }
    private val geomKeys = setOf("clock_x", "clock_y", "clock_w", "clock_h")

    override fun onBind(i: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("clock", "Floating clock", NotificationManager.IMPORTANCE_MIN))
        startForeground(30, Notification.Builder(this, "clock").setSmallIcon(android.R.drawable.ic_menu_recent_history).setContentTitle("Floating clock is on").build())
        val sp = prefs()
        lp = WindowManager.LayoutParams(sp.getInt("clock_w", dp(200)), sp.getInt("clock_h", dp(90)), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; x = sp.getInt("clock_x", dp(40)); y = sp.getInt("clock_y", dp(120))
        }
        root = FrameLayout(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(8), dp(4), dp(8), dp(4)) }
        timeTv = TextView(this).apply { gravity = Gravity.CENTER; maxLines = 1; setAutoSizeTextTypeUniformWithConfiguration(8, 200, 2, TypedValue.COMPLEX_UNIT_SP) }
        dateTv = TextView(this).apply { gravity = Gravity.CENTER; maxLines = 1; setAutoSizeTextTypeUniformWithConfiguration(6, 80, 2, TypedValue.COMPLEX_UNIT_SP) }
        col.addView(timeTv, LinearLayout.LayoutParams(-1, 0, 3f)); col.addView(dateTv, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        val handle = TextView(this).apply { text = "◢"; textSize = 16f; setTextColor(0xCCFFFFFF.toInt()); setPadding(dp(6), 0, dp(3), 0); setBackgroundColor(0x55000000) }
        root.addView(handle, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END))

        // drag + pinch-to-resize + double-tap opens settings
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                lp.width = (lp.width * d.scaleFactor).toInt().coerceIn(dp(60), resources.displayMetrics.widthPixels)
                lp.height = (lp.height * d.scaleFactor).toInt().coerceIn(dp(30), resources.displayMetrics.heightPixels)
                wm.updateViewLayout(root, lp); return true
            }
        })
        val taps = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                startActivity(Intent(this@FloatingClockService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("tab", 5)); return true
            }
        })
        var dx = 0f; var dy = 0f; var sx = 0; var sy = 0
        root.setOnTouchListener { _, e ->
            scale.onTouchEvent(e); taps.onTouchEvent(e)
            if (!scale.isInProgress && !prefs().getBoolean("clock_lock", false) && e.pointerCount == 1) when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; sx = lp.x; sy = lp.y }
                MotionEvent.ACTION_MOVE -> { lp.x = sx + (e.rawX - dx).toInt(); lp.y = sy + (e.rawY - dy).toInt(); wm.updateViewLayout(root, lp) }
            }
            if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL) saveGeom()
            true
        }
        var sw0 = 0; var sh0 = 0
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; sw0 = lp.width; sh0 = lp.height }
                MotionEvent.ACTION_MOVE -> {
                    lp.width = (sw0 + (e.rawX - dx).toInt()).coerceAtLeast(dp(60)); lp.height = (sh0 + (e.rawY - dy).toInt()).coerceAtLeast(dp(30))
                    wm.updateViewLayout(root, lp)
                }
                MotionEvent.ACTION_UP -> saveGeom()
            }
            true
        }
        applyStyle()
        wm.addView(root, lp)
        sp.registerOnSharedPreferenceChangeListener(this)
        h.post(tick)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun saveGeom() = prefs().edit().putInt("clock_x", lp.x).putInt("clock_y", lp.y).putInt("clock_w", lp.width).putInt("clock_h", lp.height).apply()

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        if (key in geomKeys) {
            if (key == "clock_x" || key == "clock_y" || key == "clock_w" || key == "clock_h") {
                val sp2 = prefs()
                lp.x = sp2.getInt("clock_x", lp.x); lp.y = sp2.getInt("clock_y", lp.y); lp.width = sp2.getInt("clock_w", lp.width); lp.height = sp2.getInt("clock_h", lp.height)
                try { wm.updateViewLayout(root, lp) } catch (e: Exception) {}
            }
            return
        }
        applyStyle(); updateText()
    }

    private fun applyStyle() {
        val sp = prefs()
        val bg = GradientDrawable().apply {
            val rgb = sp.getInt("clock_bg", 0x101827) and 0xFFFFFF
            setColor((sp.getInt("clock_bg_a", 200) shl 24) or rgb)
            cornerRadius = dp(sp.getInt("clock_r", 16)).toFloat()
            val w = sp.getInt("clock_sw", 0); if (w > 0) setStroke(dp(w), sp.getInt("clock_sc", 0xFF4F8CFF.toInt()) or 0xFF000000.toInt())
        }
        root.background = bg
        val fg = sp.getInt("clock_fg", 0xFFFFFF) or 0xFF000000.toInt()
        val tf = FontStore.load(sp.getString("font_clock", null))
        for (t in listOf(timeTv, dateTv)) {
            t.setTextColor(fg)
            t.setTypeface(tf ?: android.graphics.Typeface.DEFAULT, if (sp.getBoolean("clock_bold", true)) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            if (sp.getBoolean("clock_shadow", true)) t.setShadowLayer(6f, 2f, 2f, 0xAA000000.toInt()) else t.setShadowLayer(0f, 0f, 0f, 0)
        }
        dateTv.visibility = if (sp.getBoolean("clock_date", true)) View.VISIBLE else View.GONE
    }

    private fun updateText() {
        val sp = prefs(); val now = Date(); val is24 = sp.getBoolean("clock_24h", false)
        val pat = (if (is24) "HH:mm" else "h:mm") + (if (sp.getBoolean("clock_sec", false)) ":ss" else "") + (if (is24) "" else " a")
        timeTv.text = SimpleDateFormat(pat, Locale.getDefault()).format(now)
        dateTv.text = SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(now)
    }

    override fun onDestroy() {
        h.removeCallbacks(tick); prefs().unregisterOnSharedPreferenceChangeListener(this)
        try { wm.removeView(root) } catch (e: Exception) {}
        super.onDestroy()
    }
}
