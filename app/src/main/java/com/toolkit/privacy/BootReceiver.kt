package com.toolkit.privacy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Brings the monitor, bubble, daily BioTrack alarms and GeoSense back after a reboot or app update, if they were on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!MasterSwitch.isOn(ctx)) return   // master switch is off: nothing starts by itself
        val sp = Prefs.sp(ctx)
        if (sp.getBoolean(Prefs.MON_ON, false)) MonitorService.start(ctx)
        if (sp.getBoolean(Prefs.SG_ON, false) && SiteGuardService.canDraw(ctx)) SiteGuardService.start(ctx)
        try { BioStore.scheduleDaily(ctx) } catch (_: Throwable) {}
        if (sp.getBoolean(Prefs.GEO_ON, false)) {
            try { GeoService.start(ctx) } catch (_: Throwable) { /* background FGS start may be refused on some builds */ }
        }
    }
}
