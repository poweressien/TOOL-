package com.toolkit.privacy

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** Notification helpers: one quiet channel for "service is running", one loud channel for pop-up alerts. */
object Notify {
    const val CH_ALERTS = "pt_alerts"
    const val CH_STATUS = "pt_status"

    fun channels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH_ALERTS, "Device alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Pop-ups about background apps, data use, weak signal, missed calls and events"
            enableVibration(true)
        })
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Toolkit running", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows while Site Guard or the Device Monitor is active"
            setShowBadge(false)
        })
    }

    fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun builder(ctx: Context, channel: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, channel) else Notification.Builder(ctx)

    fun open(ctx: Context, id: Int, tab: Int, sub: Int): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .putExtra("tab", tab).putExtra("sub", sub)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(ctx, id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun status(ctx: Context, title: String, text: String, tab: Int = 5, sub: Int = 0): Notification {
        channels(ctx)
        return builder(ctx, CH_STATUS).setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle(title).setContentText(text)
            .setOngoing(true).setContentIntent(open(ctx, 9000 + sub, tab, sub)).build()
    }

    /** Heads-up pop-up. Silently skipped when the user hasn't allowed notifications. */
    @Suppress("DEPRECATION")
    fun alert(ctx: Context, id: Int, title: String, text: String, urgent: Boolean, tab: Int = 5, sub: Int = 3) {
        if (!canPost(ctx)) return
        channels(ctx)
        val b = builder(ctx, CH_ALERTS).setSmallIcon(if (urgent) android.R.drawable.ic_dialog_alert else android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true).setContentIntent(open(ctx, id, tab, sub))
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL)
        b.setCategory(Notification.CATEGORY_STATUS)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try { nm.notify(id, b.build()) } catch (e: SecurityException) { }
    }
}
