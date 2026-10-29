package com.toolkit.privacy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Brings the monitor and the bubble back after a reboot or app update, if they were on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val sp = Prefs.sp(ctx)
        if (sp.getBoolean(Prefs.MON_ON, false)) MonitorService.start(ctx)
        if (sp.getBoolean(Prefs.SG_ON, false) && SiteGuardService.canDraw(ctx)) SiteGuardService.start(ctx)
    }
}
