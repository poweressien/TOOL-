package com.toolkit.privacy

import android.content.Context
import android.content.SharedPreferences

/** Settings for the Assist tools (Site Guard, Monitor). Plain SharedPreferences, nothing leaves the phone. */
object Prefs {
    fun sp(ctx: Context): SharedPreferences = ctx.applicationContext.getSharedPreferences("toolkit_assist", Context.MODE_PRIVATE)

    // Site Guard
    const val SG_ON = "sg_on"
    const val SG_AUTO = "sg_auto"
    const val SG_X = "sg_x"
    const val SG_Y = "sg_y"

    // Monitor
    const val MON_ON = "mon_on"
    const val MON_INTERVAL = "mon_interval_min"
    const val MON_BG = "mon_bg"
    const val MON_DATA = "mon_data"
    const val MON_NET = "mon_net"
    const val MON_CALLS = "mon_calls"
    const val MON_CAL = "mon_cal"
    const val MON_BATT = "mon_batt"
    const val MON_STORAGE = "mon_storage"
    const val MON_DATA_MB = "mon_data_mb"
    const val MON_CAL_LEAD = "mon_cal_lead_min"
    const val MON_CALLS_SINCE = "mon_calls_since"
    const val MON_NET_STREAK = "mon_net_streak"
}
