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
    const val SG_SNAP = "sg_snap"
    const val SG_SIZE = "sg_size"
    const val SG_ALPHA = "sg_alpha"

    // GeoSense
    const val GEO_ON = "geo_on"
    const val GEO_REC = "geo_rec_trip"
    const val GEO_INSIDE = "geo_inside"

    // BioTrack
    const val BIO_SLEEP_ON = "bio_sleep_on"
    const val BIO_STEP_GOAL = "bio_step_goal"
    const val BIO_WEIGHT = "bio_weight"
    const val BIO_HEIGHT = "bio_height"

    // Radio & Environment
    const val ENV_ON = "env_on"
    const val ENV_LIMIT_DB = "env_limit_db"
    const val ENV_SUSTAIN = "env_sustain_s"
    const val ENV_OFFSET = "env_db_offset"
    const val ENV_LAT = "env_lat"
    const val ENV_LON = "env_lon"
    const val ENV_RTL_HOST = "env_rtl_host"

    // RealityLens
    const val LENS_PLANTNET_KEY = "lens_plantnet_key"
    const val LENS_TARGET_LANG = "lens_target_lang"

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
