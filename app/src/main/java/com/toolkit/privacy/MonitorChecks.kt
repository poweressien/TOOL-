package com.toolkit.privacy

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.Settings
import android.telephony.TelephonyManager
import java.net.InetSocketAddress
import java.net.Socket

/** What one monitor pass found: alerts to pop up, plus plain-English status lines for the "Run check now" button. */
data class CheckResult(val alerts: List<Alert>, val lines: List<String>, val commits: Map<String, () -> Unit>)

object MonitorChecks {
    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
    }

    fun granted(ctx: Context, perm: String) = ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    private fun label(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager; pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    private fun uidLabel(ctx: Context, uid: Int): String = when (uid) {
        1000 -> "Android system"
        NetworkStats.Bucket.UID_REMOVED -> "Removed apps"
        NetworkStats.Bucket.UID_TETHERING -> "Hotspot sharing"
        else -> ctx.packageManager.getPackagesForUid(uid)?.firstOrNull()?.let { label(ctx, it) } ?: "UID $uid"
    }

    fun run(ctx: Context, sp: SharedPreferences, now: Long = System.currentTimeMillis()): CheckResult {
        val alerts = ArrayList<Alert>()
        val lines = ArrayList<String>()
        val commits = HashMap<String, () -> Unit>()
        val usage = hasUsageAccess(ctx)

        // 1. apps running in the background (foreground services still alive)
        if (sp.getBoolean(Prefs.MON_BG, true)) {
            if (!usage) lines += "Background apps: needs Usage access"
            else if (Build.VERSION.SDK_INT < 29) lines += "Background apps: needs Android 10 or newer"
            else {
                val apps = backgroundApps(ctx, now)
                lines += if (apps.isEmpty()) "Background apps: nothing notable running" else "Background apps (${apps.size}): " + apps.take(6).joinToString(", ")
                MonitorRules.background(apps)?.let { alerts += it }
            }
        }

        // 2. data hogs (last 60 min)
        if (sp.getBoolean(Prefs.MON_DATA, true)) {
            if (!usage) lines += "Data use: needs Usage access"
            else {
                val limit = sp.getInt(Prefs.MON_DATA_MB, 100) * 1048576L
                val (mob, wifi) = dataLastHour(ctx, now)
                lines += "Mobile data, last hour: " + (mob.take(3).joinToString(", ") { "${it.first} ${MonitorRules.mb(it.second)}" }.ifEmpty { "quiet" })
                MonitorRules.data(mob, limit, 60, wifi = false)?.let { alerts += it }
                MonitorRules.data(wifi, limit * 5, 60, wifi = true)?.let { alerts += it }
            }
        }

        // 3. network quality
        if (sp.getBoolean(Prefs.MON_NET, true)) {
            val airplane = try { Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1 } catch (e: Exception) { false }
            if (airplane) lines += "Network: airplane mode is on"
            else {
                val n = networkSnapshot(ctx)
                if (n.kind == "other") lines += "Network: connected (${n.kind})"
                else {
                    val prev = sp.getInt(Prefs.MON_NET_STREAK, 0)
                    val bad = MonitorRules.network(n.kind, n.level, n.rssi, n.pingMs, 99) != null
                    val streak = if (bad) prev + 1 else 0
                    sp.edit().putInt(Prefs.MON_NET_STREAK, streak).apply()
                    lines += "Network: " + when (n.kind) {
                        "none" -> "offline"
                        "wifi" -> "Wi-Fi ${n.rssi?.let { "$it dBm" } ?: ""} ${n.pingMs?.let { "· $it ms" } ?: "· no internet"}"
                        else -> "mobile ${n.level?.let { "$it/4 bars" } ?: ""} ${n.pingMs?.let { "· $it ms" } ?: "· no internet"}"
                    }.trim()
                    MonitorRules.network(n.kind, n.level, n.rssi, n.pingMs, streak)?.let { alerts += it }
                }
            }
        }

        // 4. missed calls
        if (sp.getBoolean(Prefs.MON_CALLS, true)) {
            if (!granted(ctx, Manifest.permission.READ_CALL_LOG)) lines += "Missed calls: needs Call log permission"
            else {
                val since = sp.getLong(Prefs.MON_CALLS_SINCE, 0L).let { if (it == 0L) now else it }
                if (sp.getLong(Prefs.MON_CALLS_SINCE, 0L) == 0L) sp.edit().putLong(Prefs.MON_CALLS_SINCE, now).apply()
                val calls = missedCalls(ctx, since)
                lines += if (calls.isEmpty()) "Missed calls: none new" else "Missed calls: ${calls.size} new"
                MonitorRules.missedCalls(calls)?.let { a ->
                    alerts += a
                    commits[a.key] = { sp.edit().putLong(Prefs.MON_CALLS_SINCE, calls.maxOf { it.whenMs }).apply() }
                }
            }
        }

        // 5. calendar
        if (sp.getBoolean(Prefs.MON_CAL, true)) {
            if (!granted(ctx, Manifest.permission.READ_CALENDAR)) lines += "Calendar: needs Calendar permission"
            else {
                val lead = sp.getInt(Prefs.MON_CAL_LEAD, 30)
                val ev = upcomingEvents(ctx, now, lead)
                lines += if (ev.isEmpty()) "Calendar: nothing in the next $lead min" else "Calendar: " + ev.joinToString(", ") { it.title.ifBlank { "event" } }
                alerts += MonitorRules.calendar(ev, now)
            }
        }

        // 6. battery + storage
        if (sp.getBoolean(Prefs.MON_BATT, true)) {
            val (pct, charging) = battery(ctx)
            lines += "Battery: $pct%" + if (charging) " (charging)" else ""
            MonitorRules.battery(pct, charging)?.let { alerts += it }
        }
        if (sp.getBoolean(Prefs.MON_STORAGE, true)) {
            try {
                val st = StatFs(Environment.getDataDirectory().path)
                lines += "Storage free: ${MonitorRules.mb(st.availableBytes)}"
                MonitorRules.storage(st.availableBytes, st.totalBytes)?.let { alerts += it }
            } catch (e: Exception) { }
        }
        return CheckResult(alerts, lines, commits)
    }

    // ------------------------------------------------------------------ measurements

    private fun backgroundApps(ctx: Context, now: Long): List<String> {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val ev = usm.queryEvents(now - 6 * 3_600_000L, now) ?: return emptyList()
        val e = UsageEvents.Event()
        val active = LinkedHashMap<String, Boolean>()
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            if (Build.VERSION.SDK_INT >= 29) when (e.eventType) {
                UsageEvents.Event.FOREGROUND_SERVICE_START -> active[e.packageName] = true
                UsageEvents.Event.FOREGROUND_SERVICE_STOP -> active[e.packageName] = false
            }
        }
        return active.filter { it.value && it.key != ctx.packageName }.keys.map { label(ctx, it) }.distinct().sorted()
    }

    private fun perUid(nsm: NetworkStatsManager, type: Int, start: Long, end: Long): Map<Int, Long> {
        val m = HashMap<Int, Long>()
        try {
            val st = nsm.querySummary(type, null, start, end)
            val b = NetworkStats.Bucket()
            while (st.hasNextBucket()) { st.getNextBucket(b); m[b.uid] = (m[b.uid] ?: 0L) + b.rxBytes + b.txBytes }
            st.close()
        } catch (e: Exception) { }
        return m
    }

    /** (mobile, wifi), each as (appLabel, bytes) sorted high to low. */
    private fun dataLastHour(ctx: Context, now: Long): Pair<List<Pair<String, Long>>, List<Pair<String, Long>>> {
        val nsm = ctx.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
        val start = now - 3_600_000L
        fun top(type: Int) = perUid(nsm, type, start, now).entries.map { uidLabel(ctx, it.key) to it.value }
            .groupBy({ it.first }, { it.second }).map { (k, v) -> k to v.sum() }.filter { it.second > 0 }.sortedByDescending { it.second }.take(8)
        return top(ConnectivityManager.TYPE_MOBILE) to top(ConnectivityManager.TYPE_WIFI)
    }

    data class Net(val kind: String, val level: Int?, val rssi: Int?, val pingMs: Long?)

    @Suppress("DEPRECATION")
    fun networkSnapshot(ctx: Context): Net {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return Net("none", null, null, null)
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
            else -> "other"
        }
        var rssi: Int? = null
        var level: Int? = null
        if (kind == "wifi") {
            try {
                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                rssi = wm.connectionInfo?.rssi?.takeIf { it in -126..0 }
            } catch (e: Exception) { }
        } else if (kind == "cell" && Build.VERSION.SDK_INT >= 28) {
            try {
                val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                level = tm.signalStrength?.level
            } catch (e: Exception) { }
        }
        val ping = try {
            val t0 = System.nanoTime()
            Socket().use { it.connect(InetSocketAddress("1.1.1.1", 443), 2500) }
            (System.nanoTime() - t0) / 1_000_000
        } catch (e: Exception) { null }
        return Net(kind, level, rssi, ping)
    }

    private fun missedCalls(ctx: Context, since: Long): List<MissedCall> {
        val out = ArrayList<MissedCall>()
        try {
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE),
                "${CallLog.Calls.TYPE}=? AND ${CallLog.Calls.DATE}>?", arrayOf(CallLog.Calls.MISSED_TYPE.toString(), since.toString()),
                "${CallLog.Calls.DATE} DESC"
            )?.use { c ->
                while (c.moveToNext() && out.size < 20) {
                    val num = c.getString(0); val name = c.getString(1)
                    out += MissedCall(name?.takeIf { it.isNotBlank() } ?: num?.takeIf { it.isNotBlank() } ?: "Unknown number", c.getLong(2))
                }
            }
        } catch (e: Exception) { }
        return out
    }

    private fun upcomingEvents(ctx: Context, now: Long, leadMin: Int): List<SoonEvent> {
        val out = ArrayList<SoonEvent>()
        try {
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().appendPath(now.toString()).appendPath((now + leadMin * 60_000L).toString()).build()
            ctx.contentResolver.query(
                uri, arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY),
                null, null, "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { c ->
                while (c.moveToNext() && out.size < 5) {
                    val begin = c.getLong(2)
                    if (c.getInt(4) == 1 || begin < now - 60_000L) continue
                    out += SoonEvent(c.getLong(0), c.getString(1) ?: "", begin, c.getString(3))
                }
            }
        } catch (e: Exception) { }
        return out
    }

    private fun battery(ctx: Context): Pair<Int, Boolean> {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return (if (level >= 0) level * 100 / scale else 100) to charging
    }
}
