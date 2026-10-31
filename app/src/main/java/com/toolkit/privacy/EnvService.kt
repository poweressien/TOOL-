package com.toolkit.privacy

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import kotlin.math.sqrt

/**
 * Background environment watch. Measures sound level (the audio is analysed in memory and never saved or sent),
 * reads ambient sensors, checks outdoor air quality now and then, and posts alerts for aggressive noise, heat,
 * sudden temperature changes and poor air.
 */
class EnvService : Service() {
    companion object {
        const val ACTION_STOP = "com.toolkit.privacy.ENV_STOP"
        private const val NOTIF_ID = 4201
        @Volatile var instance: EnvService? = null
        @Volatile var liveDb = 0.0
        @Volatile var leq = 0.0
        @Volatile var peak = 0.0
        @Volatile var dose = 0.0
        val history = ArrayList<Double>()
        fun running() = instance != null
        fun hasMic(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        fun start(ctx: Context) {
            if (!hasMic(ctx)) return
            val i = Intent(ctx, EnvService::class.java)
            try { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) } catch (e: Exception) { }
        }
        fun stop(ctx: Context) { ctx.startService(Intent(ctx, EnvService::class.java).setAction(ACTION_STOP)) }
    }

    @Volatile private var alive = false
    private var worker: Thread? = null
    private var sensors: EnvSensors? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notify.status(this, "Environment watch", "Listening for loud noise and tracking temperature, air and pressure. Tap to open.", tab = 8, sub = 6)
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(NOTIF_ID, n)
        } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_STOP || !hasMic(this)) { Prefs.sp(this).edit().putBoolean(Prefs.ENV_ON, false).apply(); shutdown(); stopSelf(); return START_NOT_STICKY }
        if (alive) return START_STICKY
        alive = true; instance = this
        Prefs.sp(this).edit().putBoolean(Prefs.ENV_ON, true).apply()
        sensors = EnvSensors(this).also { it.start() }
        worker = Thread { loop() }.also { it.isDaemon = true; it.start() }
        return START_STICKY
    }

    private fun loop() {
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try { AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate)) } catch (e: Exception) { null }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) { alive = false; stopSelf(); return }
        val sp = Prefs.sp(this)
        val tracker = NoiseMath.Tracker(sp.getFloat(Prefs.ENV_LIMIT_DB, 85f).toDouble(), sp.getInt(Prefs.ENV_SUSTAIN, 10))
        val buf = ShortArray(rate / 2)
        var t = 0.0; var lastEnv = 0L; var lastAir = 0L; var aqi: Int? = null
        val sentAt = HashMap<String, Long>()
        history.clear()
        rec.startRecording()
        try {
            while (alive) {
                var n = 0
                while (n < buf.size && alive) { val r = rec.read(buf, n, buf.size - n); if (r <= 0) break; n += r }
                if (n == 0) { Thread.sleep(200); continue }
                var s = 0.0; for (i in 0 until n) { val v = buf[i] / 32768.0; s += v * v }
                val db = NoiseMath.db(sqrt(s / n), sp.getFloat(Prefs.ENV_OFFSET, 90f).toDouble())
                t += 0.5
                liveDb = db
                synchronized(history) { history.add(db); while (history.size > 240) history.removeAt(0) }
                for (e in tracker.push(db, t)) {
                    Notify.alert(this, 4210 + (e.kind.hashCode() and 0xF), "🔊 ${e.title}", e.text, urgent = true, tab = 8, sub = 6)
                    EnvStore.logAlert(this, e.kind, e.title, e.text)
                }
                leq = tracker.leq; peak = tracker.peak; dose = tracker.doseTotal
                val now = System.currentTimeMillis()
                if (now - lastEnv > 60_000) {
                    lastEnv = now
                    val bt = EnvStore.batteryTempC(this); bt?.let { EnvLive.pushBatt(it) }
                    if (now - lastAir > 30 * 60_000L) {
                        lastAir = now
                        EnvStore.place(this)?.let { (la, lo) -> aqi = EnvStore.fetchAir(la, lo)?.aqi }
                    }
                    val snap = EnvAlerts.Snapshot(null, EnvLive.tempC, EnvLive.tempZ, EnvMath.ratePerMin(EnvLive.snapshot(EnvLive.tempHist)), EnvLive.hum, aqi,
                        EnvMath.pressureTrend(EnvLive.snapshot(EnvLive.presHist).filter { now - it.first < 3 * 3600_000L }), bt)
                    for (a in EnvAlerts.evaluate(snap)) {
                        if (now - (sentAt[a.id] ?: 0L) < 3600_000L) continue
                        sentAt[a.id] = now
                        Notify.alert(this, 4230 + (a.id.hashCode() and 0xF), a.title, a.text, a.urgent, tab = 8, sub = 6)
                        EnvStore.logAlert(this, a.id, a.title, a.text)
                    }
                }
            }
        } catch (e: Exception) { }
        try { rec.stop() } catch (e: Exception) { }
        rec.release()
    }

    private fun shutdown() {
        alive = false
        sensors?.stop(); sensors = null
        instance = null; liveDb = 0.0
    }

    override fun onDestroy() { shutdown(); super.onDestroy() }
}
