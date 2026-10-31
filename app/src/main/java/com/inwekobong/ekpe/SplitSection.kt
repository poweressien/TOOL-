package com.inwekobong.ekpe

import android.app.ActivityOptions
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.widget.*

class SplitSection(private val a: MainActivity) {
    fun view(): View {
        val p = a.page()
        p.add(a.tv("Split Screen & Floating Windows", 22f, C.TXT, true), 0)
        p.add(a.tv("Move and resize windows anywhere on the screen, like a desktop. Windows float over every app.", 12f, C.SUB), 2)

        val c1 = a.card()
        c1.add(a.tv("1 · Floating windows (drag title bar, drag ◢ corner to resize)", 14f, C.ACC, true), 0)
        c1.add(a.button("Overlay permission") { overlayPerm() }, 8)
        c1.add(a.button("New browser window") { start("web") }, 8)
        c1.add(a.button("New notepad window") { start("notes") }, 8)
        c1.add(a.button("Tile 2 browsers side by side") { start("tile") }, 8)
        c1.add(a.button("Close all floating windows") { a.stopService(Intent(a, FloatingWindowService::class.java)) }, 8)
        p.add(c1, 14)

        val c2 = a.card()
        c2.add(a.tv("2 · Real Android split-screen with other apps", 14f, C.ACC, true), 0)
        c2.add(a.tv("Enable the accessibility service once. Then open the app you want on top, tap Toggle split, and pick the second app below.", 12f, C.SUB), 4)
        c2.add(a.button("Enable split service (Accessibility)") { a.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, 8)
        c2.add(a.button("Toggle system split screen") {
            val s = SplitAccessibilityService.inst
            if (s == null) a.toast("Enable “EKPE” in Accessibility settings first") else s.toggleSplit()
        }, 8)
        val apps = a.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(a.packageManager).toString() to it.activityInfo.packageName }.sortedBy { it.first.lowercase() }
        val sp = Spinner(a); sp.adapter = ArrayAdapter(a, android.R.layout.simple_spinner_dropdown_item, apps.map { it.first })
        c2.add(sp, 8)
        c2.add(a.button("Launch selected app in the other half") {
            val pkg = apps.getOrNull(sp.selectedItemPosition)?.second ?: return@button
            val i = a.packageManager.getLaunchIntentForPackage(pkg) ?: return@button
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            a.startActivity(i, ActivityOptions.makeBasic().toBundle())
        }, 8)
        p.add(c2, 14)
        return a.scroll(p)
    }

    private fun overlayPerm() {
        if (Settings.canDrawOverlays(a)) a.toast("Overlay permission already granted")
        else a.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${a.packageName}")))
    }

    private fun start(type: String) {
        if (!Settings.canDrawOverlays(a)) { overlayPerm(); return }
        a.startForegroundService(Intent(a, FloatingWindowService::class.java).putExtra("type", type))
    }
}
