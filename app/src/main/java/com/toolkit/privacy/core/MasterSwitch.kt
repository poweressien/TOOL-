package com.toolkit.privacy.core

import com.toolkit.privacy.*

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.inwekobong.ekpe.AutomationService
import com.inwekobong.ekpe.FloatingClockService
import com.inwekobong.ekpe.FloatingWindowService
import com.inwekobong.ekpe.LanGuardService
import com.inwekobong.ekpe.MediaWatcherService
import com.inwekobong.ekpe.RadarEngine
import com.inwekobong.ekpe.RadarService

/**
 * The one general switch for the whole app.
 *
 * OFF: every background activity is stopped (bubble, monitor, location, sleep, noise, VPN, DNS shield, radar,
 *      LAN Guard, automation, gallery watcher, floating clock/windows, mock location) and no tool can be opened.
 *      What was running is remembered.
 * ON : whatever was running is started again.
 */
object MasterSwitch {
    private const val K_ON = "master_on"
    private const val K_SNAP = "master_snapshot"

    /** Compose-observable state, so the UI reacts the moment the switch is flipped. */
    var on by mutableStateOf(true)
        private set

    fun init(ctx: Context) { on = Prefs.sp(ctx).getBoolean(K_ON, true) }

    /** Safe to call from receivers and services (reads the saved value, not UI state). */
    fun isOn(ctx: Context): Boolean = Prefs.sp(ctx).getBoolean(K_ON, true)

    fun set(ctx: Context, value: Boolean) {
        val c = ctx.applicationContext
        if (value == isOn(c) && value == on) return
        Prefs.sp(c).edit().putBoolean(K_ON, value).apply()
        on = value
        if (value) resume(c) else pause(c)
    }

    @Suppress("DEPRECATION")
    private fun running(c: Context, cls: Class<*>): Boolean = try {
        (c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .getRunningServices(Int.MAX_VALUE).any { it.service.className == cls.name }
    } catch (e: Throwable) { false }

    private fun quiet(block: () -> Unit) { try { block() } catch (e: Throwable) { /* one tool failing must never block the rest */ } }

    private fun pause(c: Context) {
        val snap = HashSet<String>()
        val sp = Prefs.sp(c)

        // --- Privacy Toolkit services ---
        quiet { if (SiteGuardService.isRunning()) { snap += "sg"; SiteGuardService.stop(c) } }
        quiet { if (MonitorService.running || sp.getBoolean(Prefs.MON_ON, false)) { snap += "mon"; MonitorService.stop(c) } }
        quiet { if (GeoService.running()) { snap += "geo"; GeoService.stopAll(c) } }
        quiet { if (SleepService.running()) { snap += "sleep"; SleepService.stop(c) } }
        quiet { if (EnvService.running()) { snap += "env"; EnvService.stop(c) } }
        quiet {
            if (running(c, MockLocationService::class.java)) { c.stopService(Intent(c, MockLocationService::class.java)) }
        }
        quiet {
            if (running(c, DnsShieldService::class.java)) {
                snap += "dns"
                c.startService(Intent(c, DnsShieldService::class.java).setAction(DnsShieldService.ACTION_STOP))
            }
        }
        quiet {
            if (VpnManager.isUp(c)) {
                snap += "vpn"
                Thread { quiet { VpnManager.disconnect(c) } }.start()
            }
        }

        // --- EKPE suite services ---
        quiet { if (running(c, RadarService::class.java)) { snap += "radar"; c.stopService(Intent(c, RadarService::class.java)) } }
        quiet { RadarEngine.stop() }
        quiet { if (running(c, AutomationService::class.java)) { snap += "auto"; c.stopService(Intent(c, AutomationService::class.java)) } }
        quiet { if (running(c, LanGuardService::class.java)) { snap += "lan"; c.stopService(Intent(c, LanGuardService::class.java)) } }
        quiet { if (running(c, MediaWatcherService::class.java)) { snap += "media"; c.stopService(Intent(c, MediaWatcherService::class.java)) } }
        quiet { if (running(c, FloatingClockService::class.java)) { snap += "clock"; c.stopService(Intent(c, FloatingClockService::class.java)) } }
        quiet { if (running(c, FloatingWindowService::class.java)) { c.stopService(Intent(c, FloatingWindowService::class.java)) } }

        sp.edit().putStringSet(K_SNAP, snap).apply()
    }

    private fun fg(c: Context, cls: Class<*>) {
        val i = Intent(c, cls)
        try { c.startForegroundService(i) } catch (e: Throwable) { try { c.startService(i) } catch (e2: Throwable) { } }
    }

    private fun resume(c: Context) {
        val sp = Prefs.sp(c)
        val snap = sp.getStringSet(K_SNAP, emptySet()) ?: emptySet()

        quiet { if ("sg" in snap && SiteGuardService.canDraw(c)) { sp.edit().putBoolean(Prefs.SG_ON, true).apply(); SiteGuardService.start(c) } }
        quiet { if ("mon" in snap) MonitorService.start(c) }
        quiet { if ("geo" in snap) GeoService.start(c) }
        quiet { if ("sleep" in snap) SleepService.start(c) }
        quiet { if ("env" in snap) EnvService.start(c) }
        quiet {
            if ("dns" in snap && VpnService.prepare(c) == null) {
                val up = sp.getString("shield_upstream", "1.1.1.1") ?: "1.1.1.1"
                c.startService(Intent(c, DnsShieldService::class.java).putExtra("upstream", up))
            }
        }
        quiet {
            if ("vpn" in snap && VpnService.prepare(c) == null) {
                val name = VpnManager.selected(c)
                if (name != null) Thread { quiet { VpnManager.connect(c, name) } }.start()
            }
        }

        quiet { if ("radar" in snap) { RadarEngine.start(c); fg(c, RadarService::class.java) } }
        quiet { if ("auto" in snap) fg(c, AutomationService::class.java) }
        quiet { if ("lan" in snap) fg(c, LanGuardService::class.java) }
        quiet { if ("media" in snap) fg(c, MediaWatcherService::class.java) }
        quiet { if ("clock" in snap) fg(c, FloatingClockService::class.java) }

        sp.edit().remove(K_SNAP).apply()
    }

    /** Names of what is running right now, for the Settings page. */
    fun runningNow(c: Context): List<String> {
        val out = ArrayList<String>()
        quiet { if (SiteGuardService.isRunning()) out += "Site Guard bubble" }
        quiet { if (MonitorService.running) out += "Device Monitor" }
        quiet { if (GeoService.running()) out += "GeoSense" }
        quiet { if (SleepService.running()) out += "Sleep tracking" }
        quiet { if (EnvService.running()) out += "Noise & environment watch" }
        quiet { if (running(c, DnsShieldService::class.java)) out += "DNS Shield" }
        quiet { if (VpnManager.isUp(c)) out += "VPN" }
        quiet { if (running(c, MockLocationService::class.java)) out += "Mock location" }
        quiet { if (running(c, RadarService::class.java)) out += "Radar" }
        quiet { if (running(c, AutomationService::class.java)) out += "Automation" }
        quiet { if (running(c, LanGuardService::class.java)) out += "LAN Guard" }
        quiet { if (running(c, MediaWatcherService::class.java)) out += "Gallery watcher" }
        quiet { if (running(c, FloatingClockService::class.java)) out += "Floating clock" }
        quiet { if (running(c, FloatingWindowService::class.java)) out += "Floating windows" }
        return out
    }
}
