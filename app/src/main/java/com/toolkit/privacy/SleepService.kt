package com.toolkit.privacy

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Sleep tracker: put the phone on the mattress. Measures body movement once a minute (accelerometer), then
 * BioStore turns the night into sleep time, wake-ups, deep/light stretches and a score. Optional smart alarm wakes you
 * in a window you choose, at a light-sleep moment.
 */
class SleepService : Service(), SensorEventListener {
    companion object {
        const val ACTION_STOP = "com.toolkit.privacy.SLEEP_STOP"
        const val EXTRA_FROM = "wake_from"; const val EXTRA_TO = "wake_to"
        private const val NOTIF_ID = 4104
        @Volatile var instance: SleepService? = null
        @Volatile var startedAt = 0L
        @Volatile var live: List<Double> = emptyList()
        @Volatile var lastResult: SleepResult? = null

        fun running() = instance != null

        fun start(ctx: Context, wakeFrom: Long = 0L, wakeTo: Long = 0L) {
            val i = Intent(ctx, SleepService::class.java).putExtra(EXTRA_FROM, wakeFrom).putExtra(EXTRA_TO, wakeTo)
            try { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) } catch (e: Exception) { }
        }

        fun stop(ctx: Context) { ctx.startService(Intent(ctx, SleepService::class.java).setAction(ACTION_STOP)) }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var sm: SensorManager
    private var wl: PowerManager.WakeLock? = null
    private val minutes = ArrayList<Double>()
    private var acc = 0.0; private var cnt = 0; private var prevMag = 0.0
    private var wakeFrom = 0L; private var wakeTo = 0L; private var alarmFired = false
    private var ringtone: Ringtone? = null

    private val tick = object : Runnable {
        override fun run() {
            val m = if (cnt > 0) acc / cnt else 0.0
            minutes += m; acc = 0.0; cnt = 0
            live = minutes.toList()
            if (minutes.size % 5 == 0) persist()
            checkAlarm()
            main.postDelayed(this, 60_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() { super.onCreate(); sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notify.status(this, "Sleep tracking", "Leave the phone on the mattress. Tap to stop and see your night.", tab = 6, sub = 2)
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(NOTIF_ID, n)
        if (intent?.action == ACTION_STOP) { finishSession(); stopSelf(); return START_NOT_STICKY }
        if (instance != null && startedAt != 0L) return START_STICKY
        instance = this
        startedAt = System.currentTimeMillis(); lastResult = null; live = emptyList()
        wakeFrom = intent?.getLongExtra(EXTRA_FROM, 0L) ?: 0L; wakeTo = intent?.getLongExtra(EXTRA_TO, 0L) ?: 0L
        try { val pm = getSystemService(Context.POWER_SERVICE) as PowerManager; wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "toolkit:sleep").apply { acquire(14 * 3600_000L) } } catch (e: Exception) { }
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        main.postDelayed(tick, 60_000L)
        return START_STICKY
    }

    override fun onSensorChanged(e: SensorEvent) {
        val mag = sqrt((e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble())
        if (prevMag != 0.0) { acc += abs(mag - prevMag); cnt++ }
        prevMag = mag
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun persist() {
        JsonStore.writeObject(this, "bio_sleep_live", JSONObject().put("start", startedAt).put("m", JSONArray(minutes)))
    }

    private fun checkAlarm() {
        if (alarmFired || wakeTo == 0L) return
        val now = System.currentTimeMillis()
        if (now < wakeFrom) return
        val recent = minutes.takeLast(3).average()
        val all = minutes.sorted(); val base = all.take((all.size * 0.4).toInt().coerceAtLeast(1)).let { it[it.size / 2] }
        val light = recent > maxOf(base * 1.6, 0.03)
        if (light || now >= wakeTo) {
            alarmFired = true
            Notify.alert(this, 4105, "⏰ Good morning", "You are in a light-sleep moment inside your wake window.", urgent = true, tab = 6, sub = 2)
            try {
                val r = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                if (Build.VERSION.SDK_INT >= 28) r.isLooping = true
                r.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                r.play(); ringtone = r
            } catch (e: Exception) { }
            main.postDelayed({ stop(this) }, 3 * 60_000L)
        }
    }

    private fun finishSession() {
        main.removeCallbacksAndMessages(null)
        try { sm.unregisterListener(this) } catch (e: Exception) { }
        try { ringtone?.stop() } catch (e: Exception) { }
        try { wl?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
        if (cnt > 0) minutes += acc / cnt
        val start = if (startedAt != 0L) startedAt else System.currentTimeMillis()
        val r = SleepAnalyzer.analyze(start, minutes)
        if (r != null && r.asleepMin >= 20) { BioStore.addSleep(this, r); lastResult = r }
        JsonStore.writeObject(this, "bio_sleep_live", JSONObject())
        startedAt = 0L; live = emptyList(); instance = null
    }

    override fun onDestroy() {
        if (instance != null) finishSession()
        super.onDestroy()
    }
}
