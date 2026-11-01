package com.inwekobong.ekpe

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout

/** Every Guard tool implements this. stop() is called when leaving the tool or the app. */
interface Tool {
    fun view(): View
    fun stop() {}
}

object GuardNotify {
    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("guard_svc", "Guard background watch", NotificationManager.IMPORTANCE_LOW))
    }

    fun post(c: Context, id: Int, title: String, text: String) {
        channels(c)
        val open = PendingIntent.getActivity(c, 60,
            Intent(c, MainActivity::class.java).putExtra("tab", 8).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(c, com.toolkit.privacy.SoundCatalog.alertChannel(c, "guard_alerts", "Guard alerts"))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).build()
        c.getSystemService(NotificationManager::class.java).notify(id, n)
    }
}

object GuardPerm {
    fun radar(): List<String> {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) { p += Manifest.permission.BLUETOOTH_SCAN; p += Manifest.permission.BLUETOOTH_CONNECT }
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.NEARBY_WIFI_DEVICES
        return p
    }

    /** Starts the shared Radar scanner (Bluetooth + Wi-Fi + magnetometer) after asking for permissions. */
    fun startRadar(a: MainActivity, then: () -> Unit = {}) {
        val need = radar().filter { a.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) { RadarEngine.start(a); then() }
        else a.ask(need.toTypedArray()) { RadarEngine.start(a); then() }
    }
}

/** A tab that shows a menu of tools; opening one swaps the menu for the tool. */
open class ToolHome(
    private val a: MainActivity, private val title: String, private val sub: String,
    private val items: List<Triple<String, String, () -> Tool>>
) {
    private val root = FrameLayout(a).apply { setBackgroundColor(C.BG) }
    private var cur: Tool? = null
    /** True when a home-screen tile opened a tool straight away: there is no menu to go back to. */
    var direct = false
        private set
    private var scrollView: android.widget.ScrollView? = null
    private val sp get() = a.getSharedPreferences("ekpe_nav", Context.MODE_PRIVATE)
    private val key get() = "scroll_$title"
    private var openIndex = -1

    fun view(): View { menu(); return root }
    fun stopAll() { cur?.stop() }

    /** Opens the n-th tool straight away (used by the home screen tiles). */
    fun openAt(index: Int) {
        items.getOrNull(index)?.let { direct = true; openIndex = index; open(it.third()) }
    }

    /** Remember where the user was scrolled to; called when the screen is left. */
    fun saveScroll() {
        val y = scrollView?.scrollY ?: return
        sp.edit().putInt(key + "_" + openIndex, y).putInt(key + "_idx", openIndex).apply()
    }

    private fun restoreScroll(sv: android.widget.ScrollView) {
        val y = sp.getInt(key + "_" + openIndex, 0)
        if (y > 0) sv.post { sv.scrollTo(0, y) }
    }

    private fun menu() {
        cur?.stop(); cur = null
        val p = a.page()
        p.add(a.tv(title, 22f, C.TXT, true), 0)
        p.add(a.tv(sub, 12f, C.SUB), 2)
        for ((t, d, f) in items) {
            val c = a.card()
            c.addView(a.tv(t, 16f, C.ACC, true))
            c.addView(a.tv(d, 12f, C.SUB))
            c.setOnClickListener { open(f()) }
            p.add(c, 10)
        }
        val sv = a.scroll(p); scrollView = sv
        root.removeAllViews(); root.addView(sv); root.applyTypeface(a.tf)
    }

    private fun open(t: Tool) {
        cur?.stop(); cur = t
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        // Opened from a home tile: the header arrow / system Back leaves straight to the home screen.
        if (!direct) col.add(a.button("← Back") { menu() }.apply { setPadding(a.dp(14), 0, a.dp(14), 0) }, 0)
        col.addView(t.view(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val sv = a.scroll(col); scrollView = sv
        root.removeAllViews(); root.addView(sv); root.applyTypeface(a.tf)
        if (direct) restoreScroll(sv)
    }
}

class GuardHome(a: MainActivity) : ToolHome(a, "Guard Suite", "Six tools for your network, privacy and surroundings.", listOf(
    Triple("LAN Guard", "Every device on your Wi-Fi, with alerts for new ones") { LanGuardSection(a) },
    Triple("Bug Sweep", "Hidden camera / listening device suspicion score") { BugSweepSection(a) },
    Triple("Device Finder", "Hot / cold tracker for lost earbuds, watches, trackers") { DeviceFinderSection(a) },
    Triple("Network Toolkit", "Speed test, ping, traceroute, DNS, port check") { NetToolkitSection(a) },
    Triple("App Privacy Audit", "Dangerous permissions, background apps, ad trackers") { AppAuditSection(a) },
    Triple("Private Vault", "Fingerprint-locked encrypted photos, files, notes") { VaultSection(a) }
))

class LabsHome(a: MainActivity) : ToolHome(a, "Labs", "Mapping, offline AI, automation, scripting, sharing, forensics and money.", listOf(
    Triple("Coverage Mapper", "Walk around, map signal per carrier, find the best spot") { CoverageSection(a) },
    Triple("Tower Locator", "Offline tower database: true distance and bearing") { TowerLocatorSection(a) },
    Triple("Offline AI", "On-device model: explain Radar, draft, translate, voice") { OfflineAiSection(a) },
    Triple("Automation Rules", "When X happens, do Y across every tool") { AutomationSection(a) },
    Triple("Script Lab", "Python console that can read Radar data") { ScriptLabSection(a) },
    Triple("Phone-to-Phone Share", "Wi-Fi Direct files and chat, no internet") { WifiShareSection(a) },
    Triple("Media Forensics", "Photo metadata and tamper check") { MediaForensicsSection(a) },
    Triple("Money Tracker", "Bank/OPay/Moniepoint/PalmPay SMS into a local ledger") { MoneyTrackerSection(a) }
))
