package com.toolkit.privacy

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.inwekobong.ekpe.AutomationEngine
import com.inwekobong.ekpe.AutomationService
import com.inwekobong.ekpe.FloatingClockService
import com.inwekobong.ekpe.FloatingWindowService
import com.inwekobong.ekpe.LanGuardService
import com.inwekobong.ekpe.MediaWatcherService
import com.inwekobong.ekpe.RadarEngine
import com.inwekobong.ekpe.RadarService

/**
 * The master switch for the whole app.
 *
 * OFF: every background job, overlay, monitor, tunnel and scanner is stopped, alarms and boot hooks are ignored, and the
 *      tools show a "paused" screen so nothing can start. What was running is remembered.
 * ON:  everything that was running is started again, and tools open normally.
 *
 * Settings, saved data and each tool's own on/off choices are never touched, so switching back on is lossless.
 * Floating split-screen windows and a Sleep session that is more than 2 hours old are not restarted (they are one-off).
 */
object MasterSwitch {
    private const val FILE = "ekpe_master"
    private const val K_ON = "on"
    private const val K_SNAP = "snapshot"
    private const val K_AT = "paused_at"
    private const val SLEEP_RESUME_MS = 2 * 3600_000L

    /** Compose-observable copy of the switch. */
    var enabled by mutableStateOf(true)
        private set

    @Volatile private var loaded = false

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        if (!loaded) { enabled = sp(ctx).getBoolean(K_ON, true); loaded = true }
    }

    /** Safe to call from services, receivers and activities. */
    fun isOn(ctx: Context): Boolean = sp(ctx).getBoolean(K_ON, true)

    private inline fun safe(f: () -> Unit) { try { f() } catch (e: Throwable) { } }

    @Suppress("DEPRECATION")
    private fun runningClasses(ctx: Context): Set<String> = try {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.getRunningServices(300).map { it.service.className }.toSet()
    } catch (e: Throwable) { emptySet() }

    private fun wgUp(ctx: Context): Boolean = try { VpnManager.isUp(ctx) } catch (e: Throwable) { false }

    private class Item(val key: String, val label: String, val cls: Class<*>?, val extra: (Context) -> Boolean = { false })

    private val items = listOf(
        Item("sg", "Site Guard bubble", SiteGuardService::class.java) { SiteGuardService.isRunning() },
        Item("mon", "Device Monitor", MonitorService::class.java) { MonitorService.running },
        Item("geo", "GeoSense places and journeys", GeoService::class.java) { GeoService.running() },
        Item("sleep", "Sleep tracking", SleepService::class.java) { SleepService.running() },
        Item("env", "Environment watch (noise, air)", EnvService::class.java) { EnvService.running() },
        Item("mock", "Mock location", MockLocationService::class.java),
        Item("dns", "DNS Shield", DnsShieldService::class.java) { ShieldState.running.value },
        Item("wg", "WireGuard VPN", null) { wgUp(it) },
        Item("radar", "EKPE Radar background scan", RadarService::class.java),
        Item("auto", "EKPE Automation rules", AutomationService::class.java),
        Item("lan", "EKPE LAN Guard watch", LanGuardService::class.java),
        Item("media", "EKPE Cloud gallery watcher", MediaWatcherService::class.java),
        Item("clock", "EKPE Floating clock", FloatingClockService::class.java),
        Item("win", "EKPE Floating windows", FloatingWindowService::class.java)
    )

    private fun isActive(ctx: Context, it: Item, run: Set<String>): Boolean =
        (it.cls != null && run.contains(it.cls.name)) || it.extra(ctx)

    /** Human-readable list of what is running right now. */
    fun activeNow(ctx: Context): List<String> {
        val app = ctx.applicationContext
        val run = runningClasses(app)
        return items.filter { isActive(app, it, run) }.map { it.label }
    }

    fun pausedSnapshot(ctx: Context): List<String> {
        val keys = sp(ctx).getStringSet(K_SNAP, emptySet()) ?: emptySet()
        return items.filter { it.key in keys }.map { it.label }
    }

    // ------------------------------------------------------------------ OFF

    fun turnOff(ctx: Context) {
        val app = ctx.applicationContext
        val run = runningClasses(app)
        val snap = HashSet<String>()
        if (isOn(app)) items.forEach { if (isActive(app, it, run)) snap += it.key }
        // flag first, so anything that tries to restart itself meanwhile is refused
        val ed = sp(app).edit().putBoolean(K_ON, false)
        if (isOn(app)) ed.putStringSet(K_SNAP, snap).putLong(K_AT, System.currentTimeMillis())
        ed.commit()
        enabled = false

        safe { app.startService(Intent(app, SiteGuardService::class.java).setAction(SiteGuardService.ACTION_STOP)) }
        safe { app.startService(Intent(app, MonitorService::class.java).setAction(MonitorService.ACTION_STOP)) }
        safe { app.startService(Intent(app, GeoService::class.java).setAction(GeoService.ACTION_STOP)) }
        safe { SleepService.stop(app) }
        safe { EnvService.stop(app) }
        safe { app.stopService(Intent(app, MockLocationService::class.java)) }
        safe { app.startService(Intent(app, DnsShieldService::class.java).setAction(DnsShieldService.ACTION_STOP)) }
        if ("wg" in snap) Thread { safe { VpnManager.disconnect(app) } }.start()

        safe { AutomationEngine.stop() }
        safe { RadarEngine.stop() }
        safe { app.stopService(Intent(app, RadarService::class.java)) }
        safe { app.stopService(Intent(app, AutomationService::class.java)) }
        safe { app.stopService(Intent(app, LanGuardService::class.java)) }
        safe { app.stopService(Intent(app, MediaWatcherService::class.java)) }
        safe { app.stopService(Intent(app, FloatingClockService::class.java)) }
        safe { app.stopService(Intent(app, FloatingWindowService::class.java)) }
        // pending one-shot and daily alarms are ignored while paused (see the receivers); re-armed on resume
    }

    // ------------------------------------------------------------------ ON

    fun turnOn(ctx: Context) {
        val app = ctx.applicationContext
        val s = sp(app)
        val snap = s.getStringSet(K_SNAP, emptySet()) ?: emptySet()
        val pausedAt = s.getLong(K_AT, 0L)
        s.edit().putBoolean(K_ON, true).remove(K_SNAP).commit()
        enabled = true

        safe { BioStore.scheduleDaily(app) }
        fun fg(cls: Class<*>) = safe { ContextCompat.startForegroundService(app, Intent(app, cls)) }

        if ("sg" in snap && SiteGuardService.canDraw(app)) safe { SiteGuardService.start(app) }
        if ("mon" in snap) safe { MonitorService.start(app) }
        if ("geo" in snap) safe { GeoService.start(app) }
        if ("sleep" in snap && System.currentTimeMillis() - pausedAt < SLEEP_RESUME_MS) safe { SleepService.start(app) }
        if ("env" in snap) safe { EnvService.start(app) }
        if ("mock" in snap) safe {
            ContextCompat.startForegroundService(app, Intent(app, MockLocationService::class.java)
                .putExtra("lat", MockLocationService.lastLat).putExtra("lon", MockLocationService.lastLon))
        }
        if ("dns" in snap) safe {
            if (VpnService.prepare(app) == null)
                app.startService(Intent(app, DnsShieldService::class.java).putExtra("upstream", DnsShieldService.lastUpstream))
        }
        if ("wg" in snap) Thread { safe { VpnManager.selected(app)?.let { VpnManager.connect(app, it) } } }.start()

        if ("radar" in snap) fg(RadarService::class.java)
        if ("auto" in snap) fg(AutomationService::class.java)
        if ("lan" in snap) fg(LanGuardService::class.java)
        if ("media" in snap) fg(MediaWatcherService::class.java)
        if ("clock" in snap) fg(FloatingClockService::class.java)
    }
}
