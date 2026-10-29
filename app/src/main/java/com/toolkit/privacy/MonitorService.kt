package com.toolkit.privacy

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps an eye on the phone and pops up a heads-up notification when something deserves attention:
 * apps running in the background, an app burning data, weak signal, missed calls, calendar events, low battery or storage.
 */
class MonitorService : Service() {
    companion object {
        const val ACTION_STOP = "com.toolkit.privacy.MON_STOP"
        private const val NOTIF_ID = 4102
        @Volatile var running = false

        fun start(ctx: Context) {
            Prefs.sp(ctx).edit().putBoolean(Prefs.MON_ON, true).apply()
            val i = Intent(ctx, MonitorService::class.java)
            try { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) } catch (e: Exception) { }
        }

        fun stop(ctx: Context) {
            Prefs.sp(ctx).edit().putBoolean(Prefs.MON_ON, false).apply()
            ctx.startService(Intent(ctx, MonitorService::class.java).setAction(ACTION_STOP))
        }

        /** Runs one pass and posts whatever is allowed by the cooldowns. Returns the status lines. */
        fun pass(ctx: Context, post: Boolean = true): List<String> {
            val sp = Prefs.sp(ctx)
            val res = MonitorChecks.run(ctx, sp)
            if (post) {
                val store = HashMap<String, Long>()
                sp.all.forEach { (k, v) -> if (k.startsWith("gate_") && v is Long) store[k.removePrefix("gate_")] = v }
                val before = HashMap(store)
                val gate = AlertGate(store)
                val now = System.currentTimeMillis()
                res.alerts.forEach { a ->
                    if (gate.allow(a, now)) {
                        Notify.alert(ctx, a.key.hashCode(), a.title, a.text, a.urgent, tab = 5, sub = 3)
                        res.commits[a.key]?.invoke()
                    }
                }
                val ed = sp.edit()
                store.forEach { (k, v) -> if (before[k] != v) ed.putLong("gate_$k", v) }
                ed.apply()
            }
            return res.lines
        }
    }

    private lateinit var thread: HandlerThread
    private lateinit var h: Handler
    private val loop = object : Runnable {
        override fun run() {
            try {
                pass(this@MonitorService)
                val t = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                val n = Notify.status(this@MonitorService, "Device Monitor is watching", "Last check $t. Tap to change what it watches.", tab = 5, sub = 3)
                (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).notify(NOTIF_ID, n)
            } catch (e: Exception) { }
            val mins = Prefs.sp(this@MonitorService).getInt(Prefs.MON_INTERVAL, 15).coerceIn(2, 240)
            h.postDelayed(this, mins * 60_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("pt-monitor").also { it.start() }
        h = Handler(thread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notify.status(this, "Device Monitor is watching", "Checking in the background", tab = 5, sub = 3)
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(NOTIF_ID, n)
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        running = true
        h.removeCallbacks(loop)
        h.postDelayed(loop, 4000)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        h.removeCallbacksAndMessages(null)
        thread.quitSafely()
        super.onDestroy()
    }
}
