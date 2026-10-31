package com.inwekobong.ekpe

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.*
import java.text.DateFormat
import java.util.Date

class GallerySection(private val a: MainActivity) {
    private lateinit var status: TextView
    private lateinit var listBox: LinearLayout

    fun view(): View {
        val p = a.page()
        p.add(a.tv("Cloud Gallery → Google Drive", 22f, C.TXT, true), 0)
        p.add(a.tv("Watches for new photos/videos, pops up on screen, and pushes them to your Drive. Optionally frees the phone copy.", 12f, C.SUB), 2)
        status = a.tv("", 13f, C.TXT)
        p.add(status, 10)
        val c = a.card()
        c.add(a.button("1 · Allow media + notifications") { perms() }, 0)
        c.add(a.button("2 · Allow overlay pop-up") { if (Settings.canDrawOverlays(a)) a.toast("Already allowed") else a.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${a.packageName}"))) }, 8)
        c.add(a.button("3 · Sign in with Google") { a.signInDrive { updateStatus(); a.toast(if (it) "Signed in" else "Sign-in cancelled") } }, 8)
        c.add(a.button("Sign out") { a.signOutDrive { updateStatus() } }, 8)
        p.add(c, 12)
        val c2 = a.card()
        c2.add(a.sw("Watch gallery for new media", MediaWatcherService.running(a)) { on ->
            a.prefs().edit().putBoolean("watch_on", on).apply()
            val i = Intent(a, MediaWatcherService::class.java)
            if (on) { a.prefs().edit().remove("last_image").remove("last_video").apply(); a.startForegroundService(i) } else a.stopService(i)
            updateStatus()
        }, 0)
        c2.add(a.sw("Auto-upload (skip the pop-up)", a.prefs().getBoolean("auto_upload", false)) { a.prefs().edit().putBoolean("auto_upload", it).apply() }, 10)
        c2.add(a.sw("Offer to free phone storage after upload", a.prefs().getBoolean("free_local", false)) { a.prefs().edit().putBoolean("free_local", it).apply() }, 10)
        p.add(c2, 12)
        p.add(a.tv("Uploaded to Drive (tap to open)", 15f, C.ACC, true), 16)
        listBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        p.add(listBox, 6)
        p.add(a.button("Refresh list") { updateStatus() }, 10)
        updateStatus()
        return a.scroll(p)
    }

    private fun perms() {
        val l = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33) { l += Manifest.permission.READ_MEDIA_IMAGES; l += Manifest.permission.READ_MEDIA_VIDEO; l += Manifest.permission.POST_NOTIFICATIONS }
        else l += Manifest.permission.READ_EXTERNAL_STORAGE
        a.ask(l.toTypedArray()) { updateStatus() }
    }

    private fun updateStatus() {
        val acc = DriveUploader.signedIn(a)
        status.text = "Google: ${acc ?: "not signed in"}\nWatcher: ${if (MediaWatcherService.running(a)) "ON" else "OFF"}\nOverlay pop-up: ${if (Settings.canDrawOverlays(a)) "allowed" else "not allowed"}"
        listBox.removeAllViews()
        val ups = DriveUploader.uploads(a)
        if (ups.isEmpty()) listBox.add(a.tv("Nothing uploaded yet.", 13f, C.SUB), 0)
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        ups.take(60).forEach {
            val t = a.tv("${it.getString("name")}\n${df.format(Date(it.getLong("t")))}", 13f, C.TXT).apply {
                background = round(C.CARD, a.dp(8)); setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8))
                setOnClickListener { _ -> a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://drive.google.com/file/d/${it.getString("id")}/view"))) }
            }
            listBox.add(t, 6)
        }
        listBox.applyTypeface(a.tf)
    }
}
