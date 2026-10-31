package com.inwekobong.ekpe

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.*
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*

class FloatingWindowService : Service() {
    private lateinit var wm: WindowManager
    private val windows = ArrayList<View>()
    private var n = 0

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("windows", "Floating windows", NotificationManager.IMPORTANCE_MIN))
        startForeground(20, Notification.Builder(this, "windows").setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle("Floating windows active").setContentText("Stop them from EKPE → Split").build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val dm = resources.displayMetrics
        val sw = dm.widthPixels; val sh = dm.heightPixels
        when (intent?.getStringExtra("type")) {
            "tile" -> { add("web", 0, dp(40), sw / 2, (sh * 0.8).toInt()); add("web", sw / 2, dp(40), sw / 2, (sh * 0.8).toInt()) }
            "notes" -> add("notes", dp(30) + n * dp(24), dp(80) + n * dp(24), (sw * 0.7).toInt(), (sh * 0.4).toInt())
            else -> add("web", dp(20) + n * dp(24), dp(80) + n * dp(24), (sw * 0.85).toInt(), (sh * 0.55).toInt())
        }
        return START_NOT_STICKY
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun add(type: String, x: Int, y: Int, w: Int, h: Int) {
        n++
        val lp = WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; this.x = x; this.y = y
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        val frame = FrameLayout(this).apply { setBackgroundColor(C.CARD) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(C.ACC); setPadding(dp(8), dp(4), dp(4), dp(4)); gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(this).apply { text = if (type == "web") "⠿  Browser" else "⠿  Notes"; setTextColor(0xFFFFFFFF.toInt()); textSize = 14f }
        bar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        fun snap(label: String, f: () -> Unit) = bar.addView(TextView(this).apply {
            text = label; setTextColor(0xFFFFFFFF.toInt()); textSize = 18f; setPadding(dp(9), 0, dp(9), 0); setOnClickListener { f() }
        })
        val dm = resources.displayMetrics
        fun place(nx: Int, ny: Int, nw: Int, nh: Int) { lp.x = nx; lp.y = ny; lp.width = nw; lp.height = nh; wm.updateViewLayout(frame, lp) }
        snap("◧") { place(0, dp(30), dm.widthPixels / 2, dm.heightPixels - dp(80)) }
        snap("◨") { place(dm.widthPixels / 2, dp(30), dm.widthPixels / 2, dm.heightPixels - dp(80)) }
        snap("⬒") { place(0, dp(30), dm.widthPixels, (dm.heightPixels - dp(80)) / 2) }
        snap("⬓") { place(0, dp(30) + (dm.heightPixels - dp(80)) / 2, dm.widthPixels, (dm.heightPixels - dp(80)) / 2) }
        snap("⛶") { place(0, dp(30), dm.widthPixels, dm.heightPixels - dp(80)) }
        snap("✕") { wm.removeView(frame); windows.remove(frame); if (windows.isEmpty()) stopSelf() }
        col.addView(bar)

        if (type == "web") {
            val url = EditText(this).apply {
                hint = "Search or type URL"; setTextColor(C.TXT); setHintTextColor(C.SUB); textSize = 13f; maxLines = 1
                imeOptions = EditorInfo.IME_ACTION_GO; inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
                setBackgroundColor(C.BG); setPadding(dp(10), dp(6), dp(10), dp(6))
            }
            val web = WebView(this).apply {
                settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(v: WebView?, u: String?) { if (u != null) url.setText(u) }
                }
                loadUrl("https://www.google.com")
            }
            url.setOnEditorActionListener { _, _, _ ->
                val t = url.text.toString().trim()
                web.loadUrl(if (t.startsWith("http")) t else if (t.contains(".") && !t.contains(" ")) "https://$t"
                else "https://www.google.com/search?q=" + java.net.URLEncoder.encode(t, "UTF-8"))
                true
            }
            col.addView(url); col.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            val sp = getSharedPreferences("vint", Context.MODE_PRIVATE)
            val note = EditText(this).apply {
                setText(sp.getString("note_text", "")); setTextColor(C.TXT); setBackgroundColor(C.BG); gravity = Gravity.TOP
                setPadding(dp(10), dp(8), dp(10), dp(8)); hint = "Write here…"; setHintTextColor(C.SUB)
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) { sp.edit().putString("note_text", s.toString()).apply() }
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
            }
            col.addView(note, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        frame.addView(col, FrameLayout.LayoutParams(-1, -1))

        val handle = TextView(this).apply { text = "◢"; setTextColor(0xFFFFFFFF.toInt()); textSize = 22f; setBackgroundColor(0x884F8CFF.toInt()); setPadding(dp(8), dp(2), dp(4), dp(2)) }
        frame.addView(handle, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END))

        var dx = 0f; var dy = 0f; var sx = 0; var sy = 0
        bar.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; sx = lp.x; sy = lp.y }
                MotionEvent.ACTION_MOVE -> { lp.x = sx + (e.rawX - dx).toInt(); lp.y = sy + (e.rawY - dy).toInt(); wm.updateViewLayout(frame, lp) }
            }
            true
        }
        var sw0 = 0; var sh0 = 0
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; sw0 = lp.width; sh0 = lp.height }
                MotionEvent.ACTION_MOVE -> {
                    lp.width = (sw0 + (e.rawX - dx).toInt()).coerceAtLeast(dp(160))
                    lp.height = (sh0 + (e.rawY - dy).toInt()).coerceAtLeast(dp(140)); wm.updateViewLayout(frame, lp)
                }
            }
            true
        }
        wm.addView(frame, lp); windows.add(frame)
    }

    override fun onDestroy() {
        windows.forEach { try { wm.removeView(it) } catch (e: Exception) {} }
        windows.clear(); super.onDestroy()
    }
}
