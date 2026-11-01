package com.toolkit.privacy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Feeds a test GPS position via Android's mock-location developer option. */
class MockLocationService : Service() {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null
    private val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val lat = intent?.getDoubleExtra("lat", 0.0) ?: 0.0
        val lon = intent?.getDoubleExtra("lon", 0.0) ?: 0.0

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("mock", "Mock location", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "mock")
            .setContentTitle("Mock location active")
            .setContentText("%.5f, %.5f".format(lat, lon))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)

        job?.cancel()
        job = scope.launch { run(lat, lon) }
        return START_NOT_STICKY
    }

    private suspend fun run(lat: Double, lon: Double) {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        try {
            for (p in providers) {
                try { lm.removeTestProvider(p) } catch (_: Exception) {}
                lm.addTestProvider(
                    p, p == LocationManager.NETWORK_PROVIDER, false, false, false,
                    true, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE
                )
                lm.setTestProviderEnabled(p, true)
            }
            state.value = "Running at %.5f, %.5f".format(lat, lon)
            while (scope.isActive && job?.isActive == true) {
                for (p in providers) {
                    val loc = Location(p).apply {
                        latitude = lat
                        longitude = lon
                        altitude = 0.0
                        accuracy = 3f
                        speed = 0f
                        time = System.currentTimeMillis()
                        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    }
                    lm.setTestProviderLocation(p, loc)
                }
                delay(1000)
            }
        } catch (e: SecurityException) {
            state.value = "Select this app under Developer options → Select mock location app"
            stopSelf()
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException) {
                state.value = "Error: ${e.message}"
                stopSelf()
            }
        } finally {
            for (p in providers) {
                try { lm.removeTestProvider(p) } catch (_: Exception) {}
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        if (!state.value.startsWith("Select") && !state.value.startsWith("Error")) state.value = "Stopped"
        super.onDestroy()
    }

    companion object {
        val state = MutableStateFlow("Stopped")
    }
}
