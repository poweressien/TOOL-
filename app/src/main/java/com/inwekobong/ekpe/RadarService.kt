package com.inwekobong.ekpe

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class RadarService : Service() {
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        RadarEngine.mkChannel(this)
        val open = PendingIntent.getActivity(
            this, 40,
            Intent(this, MainActivity::class.java).putExtra("tab", 7).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, "radar_svc")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("EKPE Radar is monitoring")
            .setContentText("Watching towers, Wi-Fi, Bluetooth and signal")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    startForeground(40, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                } catch (e: Exception) {
                    startForeground(40, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                }
            } else {
                startForeground(40, n)
            }
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        RadarEngine.start(this)
        return START_STICKY
    }
}
