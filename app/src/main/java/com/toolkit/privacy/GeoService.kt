package com.toolkit.privacy

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper

/**
 * GeoSense engine: runs in the foreground, watches saved places (enter / exit alerts + location reminders)
 * and records journeys. Uses plain Android location, no Google services, no server.
 */
class GeoService : Service() {
    companion object {
        const val ACTION_STOP = "com.toolkit.privacy.GEO_STOP"
        private const val NOTIF_ID = 4103
        @Volatile var instance: GeoService? = null
        @Volatile var last: Location? = null
        @Volatile var inside: Set<Long> = emptySet()

        fun running() = instance != null
        fun hasPerm(ctx: Context) = ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

        fun start(ctx: Context) {
            if (!hasPerm(ctx)) return
            Prefs.sp(ctx).edit().putBoolean(Prefs.GEO_ON, true).apply()
            val i = Intent(ctx, GeoService::class.java)
            try { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) } catch (e: Exception) { }
        }

        /** Stops the service unless a journey is still being recorded. */
        fun stop(ctx: Context) {
            Prefs.sp(ctx).edit().putBoolean(Prefs.GEO_ON, false).apply()
            if (Prefs.sp(ctx).getLong(Prefs.GEO_REC, 0L) == 0L) ctx.startService(Intent(ctx, GeoService::class.java).setAction(ACTION_STOP))
        }

        fun stopAll(ctx: Context) {
            Prefs.sp(ctx).edit().putBoolean(Prefs.GEO_ON, false).apply()
            ctx.startService(Intent(ctx, GeoService::class.java).setAction(ACTION_STOP))
        }

        fun scheduleParkingAlarm(ctx: Context, atMs: Long) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(ctx, 7700, Intent(ctx, GeoAlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi) } catch (e: Exception) { }
        }

        fun cancelParkingAlarm(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(PendingIntent.getBroadcast(ctx, 7700, Intent(ctx, GeoAlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
    }

    private lateinit var lm: LocationManager
    private var lastRecorded: Location? = null
    private var lastRecordedAt = 0L

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) { handle(loc) }
        @Deprecated("old API") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val rec = Prefs.sp(this).getLong(Prefs.GEO_REC, 0L) != 0L
        val n = Notify.status(this, if (rec) "Recording your journey" else "GeoSense is watching your places", if (rec) "Tap to see the live trip" else "Alerts when you enter or leave saved places", tab = 10, sub = if (rec) 1 else 0)
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(NOTIF_ID, n)
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (!hasPerm(this)) { stopSelf(); return START_NOT_STICKY }
        try { lm.removeUpdates(listener) } catch (e: Exception) { }
        val interval = if (rec) 3000L else 15000L
        val dist = if (rec) 5f else 15f
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, interval, dist, listener, Looper.getMainLooper())
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, interval * 2, dist * 2, listener, Looper.getMainLooper())
        } catch (e: Exception) { }
        return START_STICKY
    }

    override fun onDestroy() {
        try { lm.removeUpdates(listener) } catch (e: Exception) { }
        instance = null
        super.onDestroy()
    }

    private fun handle(loc: Location) {
        val prev = last
        // ignore a worse network fix right after a good GPS fix
        if (prev != null && loc.provider == LocationManager.NETWORK_PROVIDER && prev.provider == LocationManager.GPS_PROVIDER && loc.time - prev.time < 20000 && loc.accuracy > prev.accuracy) return
        last = loc
        checkPlaces(loc)
        val rec = Prefs.sp(this).getLong(Prefs.GEO_REC, 0L)
        if (rec != 0L && loc.accuracy < 60) {
            val lr = lastRecorded
            val moved = lr == null || loc.distanceTo(lr) >= 5f
            if (moved || System.currentTimeMillis() - lastRecordedAt > 30000) {
                GeoStore.appendPoint(this, rec, TripPoint(loc.latitude, loc.longitude, System.currentTimeMillis(), if (loc.hasSpeed()) loc.speed else 0f, loc.accuracy, if (loc.hasAltitude()) loc.altitude else 0.0))
                lastRecorded = loc; lastRecordedAt = System.currentTimeMillis()
            }
        }
    }

    private fun checkPlaces(loc: Location) {
        val places = GeoStore.places(this)
        if (places.isEmpty()) return
        val now = HashSet<Long>()
        val wasInside = inside
        for (p in places) {
            val d = GeoMath.distance(loc.latitude, loc.longitude, p.lat, p.lon)
            val margin = maxOf(15f, loc.accuracy * 0.5f)
            val wasIn = p.id in wasInside
            val isIn = if (wasIn) d <= p.radius + margin else d <= p.radius - minOf(10f, p.radius * 0.1f)
            if (isIn) now += p.id
            if (isIn && !wasIn && loc.accuracy <= p.radius * 1.5) onTransition(p, "enter")
            if (!isIn && wasIn) onTransition(p, "exit")
        }
        inside = now
    }

    private fun onTransition(p: GeoPlace, kind: String) {
        GeoStore.logEvent(this, p.name, kind)
        if ((kind == "enter" && p.onEnter) || (kind == "exit" && p.onExit))
            Notify.alert(this, 4300 + (p.id % 500).toInt(), if (kind == "enter") "Arrived: ${p.name}" else "Left: ${p.name}", p.note.ifBlank { "GeoSense place alert" }, urgent = false, tab = 10, sub = 0)
        val rems = GeoStore.reminders(this)
        var changed = false
        val upd = rems.map { r ->
            if (r.placeId == p.id && r.trigger == kind && !r.done) {
                Notify.alert(this, 4800 + (r.id % 500).toInt(), "Reminder · ${p.name}", r.text, urgent = true, tab = 10, sub = 3)
                if (!r.repeat) { changed = true; r.copy(done = true) } else r
            } else r
        }
        if (changed) GeoStore.saveReminders(this, upd)
    }
}

class GeoAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Notify.alert(ctx, 4999, "🅿 Parking time is almost up", "Your parking meter is about to expire. Tap to navigate to your car.", urgent = true, tab = 10, sub = 2)
    }
}
