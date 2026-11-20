package com.example.netfreeze

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean

class NetBlockerVpnService : VpnService() {
    @Volatile private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile private var worker: Thread? = null
    private val stopping = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        stopVpn()

        val frozenApps = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getStringSet(KEY_FROZEN, emptySet())?.toSet().orEmpty()
        if (frozenApps.isEmpty()) {
            stopForeground(true)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        stopping.set(false)
        worker = Thread({ runVpn(frozenApps) }, "NetFreeze-VPN").also { it.start() }
        return START_STICKY
    }

    private fun runVpn(frozenApps: Set<String>) {
        try {
            val builder = Builder()
                .setSession("Net Freeze")
                .setMtu(1500)
                .addAddress("10.77.0.1", 32)
                .addRoute("0.0.0.0", 0)
                // Route IPv6 too, so blocked apps cannot bypass the filter over IPv6.
                .addAddress("fd77:77::1", 128)
                .addRoute("::", 0)

            frozenApps.forEach { packageName ->
                try {
                    builder.addAllowedApplication(packageName)
                } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                    // Ignore apps that have since been uninstalled.
                }
            }

            val descriptor = builder.establish() ?: return
            vpnInterface = descriptor
            try {
                // The VPN is intentionally a blackhole for selected apps: packets are read and discarded.
                FileInputStream(descriptor.fileDescriptor).use { input ->
                    val packet = ByteArray(32767)
                    while (!stopping.get()) {
                        val count = input.read(packet)
                        if (count < 0) break
                    }
                }
            } finally {
                try { descriptor.close() } catch (e: Exception) { }
                if (vpnInterface === descriptor) vpnInterface = null
            }
        } catch (e: Exception) {
            // Service can be stopped by Android or when the VPN permission is revoked.
        }
    }

    private fun stopVpn() {
        stopping.set(true)
        try { vpnInterface?.close() } catch (e: Exception) { }
        vpnInterface = null
        worker?.interrupt()
        worker = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Net Freeze", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Net Freeze is active")
            .setContentText("Internet is blocked for selected apps")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    companion object {
        private const val PREFS_NAME = "NetFreezePrefs"
        private const val KEY_FROZEN = "frozen_apps"
        private const val CHANNEL_ID = "net_freeze_vpn"
        private const val NOTIFICATION_ID = 701
    }
}
