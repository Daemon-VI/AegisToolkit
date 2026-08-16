package com.rishi.aegis.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Short-lived camera foreground service that takes one silent front-camera photo and stops.
 * Running as a foreground service with a camera type is what lets the capture work while the app
 * is in the background / the screen is locked, within Android's rules.
 */
class IntruderService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startAsForeground()
        } catch (_: Exception) {
            // Background camera-FGS start can be blocked on some OEM builds; nothing we can do.
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // Ignore overlapping triggers (rapid repeated failed attempts) while one shot is in flight.
        if (!busy.compareAndSet(false, true)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val attempts = intent?.getIntExtra(EXTRA_ATTEMPTS, 1) ?: 1
        if (!Guardian.hasCameraPermission(this)) {
            finish(startId)
            return START_NOT_STICKY
        }

        val outFile = Guardian.newCaptureFile(this, attempts)
        try {
            IntruderCamera.capture(this, outFile) {
                finish(startId)
            }
        } catch (_: Exception) {
            finish(startId)
        }
        return START_NOT_STICKY
    }

    private fun finish(startId: Int) {
        busy.set(false)
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        stopSelf(startId)
    }

    private fun startAsForeground() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val existing = mgr.getNotificationChannel(CHANNEL)
            if (existing == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Intruder Watch", NotificationManager.IMPORTANCE_MIN).apply {
                        description = "Active while Aegis captures a failed-unlock photo"
                        setShowBadge(false)
                    }
                )
            }
        }
        val notif: Notification = (if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this))
            .setContentTitle("Aegis")
            .setContentText("Security check")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    companion object {
        const val EXTRA_ATTEMPTS = "attempts"
        private const val CHANNEL = "aegis_guardian"
        private const val NOTIF_ID = 4711
        private val busy = AtomicBoolean(false)
    }
}
