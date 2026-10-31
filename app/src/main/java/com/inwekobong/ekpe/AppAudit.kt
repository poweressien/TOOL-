package com.inwekobong.ekpe

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Lists dangerous permissions, background capability and ad/tracker SDKs using Android's own package data. */
class AppAuditSection(private val a: MainActivity) : Tool {
    private lateinit var out: LinearLayout
    private lateinit var status: TextView
    private var includeSystem = false
    private var onlyRisky = true

    private data class App(val label: String, val pkg: String, val perms: List<String>, val bg: Boolean, val trackers: List<String>, val score: Int)

    private val DANGER = mapOf(
        "android.permission.CAMERA" to "Camera", "android.permission.RECORD_AUDIO" to "Microphone",
        "android.permission.ACCESS_FINE_LOCATION" to "Location", "android.permission.ACCESS_COARSE_LOCATION" to "Location",
        "android.permission.ACCESS_BACKGROUND_LOCATION" to "Location (always)",
        "android.permission.READ_CONTACTS" to "Contacts", "android.permission.WRITE_CONTACTS" to "Contacts",
        "android.permission.READ_SMS" to "SMS", "android.permission.RECEIVE_SMS" to "SMS", "android.permission.SEND_SMS" to "SMS",
        "android.permission.READ_CALL_LOG" to "Call log", "android.permission.READ_CALENDAR" to "Calendar",
        "android.permission.BODY_SENSORS" to "Body sensors"
    )
    private val BG_PERMS = setOf("android.permission.RECEIVE_BOOT_COMPLETED", "android.permission.FOREGROUND_SERVICE",
        "android.permission.ACCESS_BACKGROUND_LOCATION", "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "android.permission.SCHEDULE_EXACT_ALARM")
    private val TRACKERS = listOf(
        "com.google.android.gms.ads" to "Google Ads", "com.facebook.ads" to "Facebook Ads", "com.facebook.appevents" to "Facebook Analytics",
        "com.unity3d.ads" to "Unity Ads", "com.applovin" to "AppLovin", "com.ironsource" to "ironSource", "com.mopub" to "MoPub",
        "com.vungle" to "Vungle", "com.chartboost" to "Chartboost", "com.inmobi" to "InMobi", "com.adcolony" to "AdColony",
        "com.startapp" to "StartApp", "com.flurry" to "Flurry", "com.appsflyer" to "AppsFlyer", "com.adjust.sdk" to "Adjust",
        "io.branch" to "Branch", "com.amplitude" to "Amplitude", "com.mixpanel" to "Mixpanel", "com.google.firebase.analytics" to "Firebase Analytics",
        "com.google.android.gms.measurement" to "Google Analytics", "com.bytedance.sdk" to "Pangle (TikTok ads)", "com.mbridge" to "Mintegral",
        "com.smaato" to "Smaato", "net.pubnative" to "PubNative", "com.tapjoy" to "Tapjoy", "com.yandex.mobile.ads" to "Yandex Ads"
    )

    override fun view(): View {
        val p = a.page()
        p.add(a.tv("App Privacy Audit", 20f, C.TXT, true), 0)
        p.add(a.tv("Shows granted dangerous permissions, apps that can run in the background, and known ad/tracker SDKs. Tap an app to open its settings and change permissions.", 12f, C.SUB), 4)
        p.add(a.sw("Include system apps", false) { includeSystem = it }, 8)
        p.add(a.sw("Only apps with something to flag", true) { onlyRisky = it }, 2)
        p.add(a.button("Run audit") { run() }, 8)
        status = a.tv("", 12f, C.SUB); p.add(status, 8)
        out = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        p.add(out, 8)
        return p
    }

    @Suppress("DEPRECATION")
    private fun run() {
        status.text = "Auditing…"; out.removeAllViews()
        Thread {
            val pm = a.packageManager
            val list = ArrayList<App>()
            val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or PackageManager.GET_ACTIVITIES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS
            val pkgs = try { pm.getInstalledPackages(0) } catch (e: Exception) { emptyList<PackageInfo>() }
            for (base in pkgs) {
                val ai = base.applicationInfo ?: continue
                val sys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                if (sys && !includeSystem) continue
                val pi = try { pm.getPackageInfo(base.packageName, flags) } catch (e: Exception) { continue }
                val granted = HashSet<String>(); val requested = HashSet<String>()
                pi.requestedPermissions?.forEachIndexed { i, perm ->
                    requested.add(perm)
                    if ((pi.requestedPermissionsFlags?.getOrNull(i) ?: 0) and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0) granted.add(perm)
                }
                val dang = granted.mapNotNull { DANGER[it] }.distinct().sorted()
                val bg = requested.any { it in BG_PERMS }
                val names = ArrayList<String>()
                pi.activities?.forEach { names.add(it.name) }; pi.services?.forEach { names.add(it.name) }
                pi.receivers?.forEach { names.add(it.name) }; pi.providers?.forEach { names.add(it.name) }
                val tr = TRACKERS.filter { t -> names.any { it.startsWith(t.first) } }.map { it.second }.distinct()
                if (onlyRisky && dang.isEmpty() && tr.isEmpty() && !bg) continue
                list.add(App(pm.getApplicationLabel(ai).toString(), pi.packageName, dang, bg, tr, dang.size * 2 + (if (bg) 2 else 0) + tr.size * 3))
            }
            list.sortByDescending { it.score }
            a.runOnUiThread { show(list, pkgs.size) }
        }.start()
    }

    private fun show(list: List<App>, total: Int) {
        val withTr = list.count { it.trackers.isNotEmpty() }
        val withCam = list.count { "Camera" in it.perms }; val withMic = list.count { "Microphone" in it.perms }; val withLoc = list.count { it.perms.any { p -> p.startsWith("Location") } }
        status.text = "${list.size} of $total apps flagged · camera $withCam · mic $withMic · location $withLoc · trackers $withTr" + if (list.size > 80) " (showing top 80)" else ""
        out.removeAllViews()
        list.take(80).forEach { app ->
            val c = a.card()
            c.addView(a.tv(app.label, 15f, C.TXT, true))
            if (app.perms.isNotEmpty()) c.addView(a.tv("Allowed: " + app.perms.joinToString(", "), 12f, C.SUB))
            if (app.bg) c.addView(a.tv("Can run in the background", 12f, C.SUB))
            if (app.trackers.isNotEmpty()) c.addView(a.tv("Ad/tracker SDKs: " + app.trackers.joinToString(", "), 12f, 0xFFFF9500.toInt()))
            c.setOnClickListener {
                try { a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}"))) } catch (e: Exception) { a.toast("Cannot open settings") }
            }
            out.add(c, 6)
        }
        out.applyTypeface(a.tf)
    }
}
