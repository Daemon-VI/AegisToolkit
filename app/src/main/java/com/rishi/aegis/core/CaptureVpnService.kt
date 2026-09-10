package com.rishi.aegis.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream

/**
 * No-root packet capture over Android's [VpnService]. We stand up a local TUN interface that claims
 * the default route, so the OS hands us every IP packet the device tries to send. We parse each one
 * ([PacketParser]) into a flow record for the Traffic Monitor and drop it.
 *
 * PROTOTYPE SCOPE: this build is capture-only — it does not forward traffic back out, so while the
 * monitor is running the device has no working internet (packets are observed, then discarded). That
 * is deliberate: it proves the capture path end-to-end on this phone before we invest in the
 * userspace TCP/UDP forwarder that a real always-on monitor/firewall needs. See ROADMAP v1.4.
 */
class CaptureVpnService : VpnService() {

    @Volatile private var running = false
    private var tun: ParcelFileDescriptor? = null
    private var reader: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            return START_NOT_STICKY
        }
        return if (startCapture()) START_STICKY else START_NOT_STICKY
    }

    private fun startCapture(): Boolean {
        if (running) return true
        try {
            startAsForeground()
        } catch (e: Exception) {
            Log.w(TAG, "foreground start blocked: ${e.message}")
            stopSelf()
            return false
        }

        val fd = try {
            Builder()
                .setSession("Aegis Capture")
                .setMtu(MTU)
                .addAddress("10.10.0.2", 32)
                .addRoute("0.0.0.0", 0)       // capture all IPv4
                .addDnsServer("8.8.8.8")      // cosmetic; nothing is forwarded in this build
                .setBlocking(true)
                .establish()
        } catch (e: Exception) {
            Log.w(TAG, "establish() threw: ${e.message}")
            null
        }

        if (fd == null) {
            // Null means consent was lost or another VPN owns the tunnel.
            Log.w(TAG, "establish() returned null — TUN not created")
            stopForegroundCompat()
            stopSelf()
            return false
        }

        tun = fd
        running = true
        TrafficCapture.onServiceState(true)
        Log.i(TAG, "TUN established, fd=${fd.fd} — capture running")

        reader = Thread({ readLoop(fd) }, "aegis-capture").apply { start() }
        return true
    }

    private fun readLoop(fd: ParcelFileDescriptor) {
        val buf = ByteArray(MTU)
        var count = 0L
        try {
            FileInputStream(fd.fileDescriptor).use { input ->
                while (running) {
                    val n = input.read(buf)
                    if (n <= 0) {
                        if (n < 0) break   // fd closed
                        continue
                    }
                    val now = System.currentTimeMillis()
                    val flow = PacketParser.parse(buf, n, now)
                    if (flow != null) {
                        TrafficCapture.record(flow, now)
                        count++
                        // Heartbeat to logcat so the capture path is verifiable without the UI.
                        if (count % 25L == 0L) {
                            Log.i(TAG, "captured $count packets, latest ${flow.protoName} " +
                                "${flow.srcLabel} -> ${flow.dstLabel} (${flow.length}B)")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (running) Log.w(TAG, "read loop ended: ${e.message}")
        }
        Log.i(TAG, "read loop exit after $count packets")
    }

    private fun stopCapture() {
        running = false
        TrafficCapture.onServiceState(false)
        TrafficCapture.publish()
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        reader = null
        stopForegroundCompat()
        stopSelf()
        Log.i(TAG, "capture stopped")
    }

    override fun onRevoke() {
        // System or another VPN took over the tunnel.
        Log.i(TAG, "onRevoke — tunnel revoked by system")
        stopCapture()
    }

    override fun onDestroy() {
        running = false
        TrafficCapture.onServiceState(false)
        try { tun?.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun startAsForeground() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && mgr.getNotificationChannel(CHANNEL) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "Traffic Monitor", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Active while Aegis is capturing network traffic"
                    setShowBadge(false)
                }
            )
        }
        val notif: Notification = (if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this))
            .setContentTitle("Aegis Traffic Monitor")
            .setContentText("Capturing network traffic")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, 0)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun stopForegroundCompat() {
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "AegisVPN"
        private const val CHANNEL = "aegis_traffic"
        private const val NOTIF_ID = 4720
        private const val MTU = 32767
        const val ACTION_STOP = "com.rishi.aegis.STOP_CAPTURE"

        fun start(ctx: Context) {
            val i = Intent(ctx, CaptureVpnService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, CaptureVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
