package com.example.aegis.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.aegis.MainActivity

/**
 * Aegis VPN Service - Phase 1: VPN Skeleton & Lifecycle
 *
 * Responsibilities:
 * - VPN lifecycle management (start/stop)
 * - Foreground service with persistent notification
 * - VPN establishment and teardown
 * - Idempotent operation handling
 *
 * Non-responsibilities (Phase 1):
 * - No TUN reads or writes
 * - No packet parsing
 * - No socket operations
 * - No UID attribution
 * - No rules or enforcement
 */
class AegisVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false

    companion object {
        private const val TAG = "AegisVpnService"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            VpnConstants.ACTION_START -> handleStart()
            VpnConstants.ACTION_STOP -> handleStop()
            else -> Log.w(TAG, "Unknown action: ${intent?.action}")
        }

        // If system kills the service, do not auto-restart with null intent
        return START_NOT_STICKY
    }

    /**
     * Handles VPN start request.
     * Idempotent - multiple calls are safe.
     */
    private fun handleStart() {
        if (isRunning) {
            Log.d(TAG, "VPN already running, ignoring start request")
            return
        }

        Log.i(TAG, "Starting VPN service")

        // Start as foreground service with notification
        startForeground(VpnConstants.NOTIFICATION_ID, createNotification())

        // Establish VPN
        val success = establishVpn()
        if (success) {
            isRunning = true
            Log.i(TAG, "VPN started successfully")
        } else {
            Log.e(TAG, "Failed to establish VPN")
            stopSelf()
        }
    }

    /**
     * Handles VPN stop request.
     * Idempotent - multiple calls are safe.
     */
    private fun handleStop() {
        if (!isRunning) {
            Log.d(TAG, "VPN not running, ignoring stop request")
            stopSelf()
            return
        }

        Log.i(TAG, "Stopping VPN service")
        teardownVpn()
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Establishes VPN connection using VpnService.Builder.
     * Called exactly once per start.
     *
     * @return true if successful, false otherwise
     */
    private fun establishVpn(): Boolean {
        return try {
            val builder = Builder()
                .setSession("Aegis VPN")
                .addAddress(VpnConstants.VPN_ADDRESS, 32)
                .addRoute(VpnConstants.VPN_ROUTE, VpnConstants.VPN_PREFIX_LENGTH)
                .setMtu(VpnConstants.VPN_MTU)
                .setBlocking(false)

            // Establish and store the file descriptor
            vpnInterface = builder.establish()

            if (vpnInterface == null) {
                Log.e(TAG, "VpnService.Builder.establish() returned null")
                return false
            }

            Log.d(TAG, "VPN interface established")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish VPN", e)
            false
        }
    }

    /**
     * Tears down VPN connection and releases resources.
     * Safe to call multiple times.
     */
    private fun teardownVpn() {
        try {
            vpnInterface?.close()
            vpnInterface = null
            Log.d(TAG, "VPN interface closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface", e)
        }
    }

    /**
     * Creates foreground notification for the service.
     */
    private fun createNotification(): Notification {
        val notificationManager = getSystemService(NotificationManager::class.java)

        // Create notification channel (required for Android O+)
        val channel = NotificationChannel(
            VpnConstants.NOTIFICATION_CHANNEL_ID,
            VpnConstants.NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows VPN connection status"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)

        // Intent to open MainActivity when notification is tapped
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, VpnConstants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Aegis VPN")
            .setContentText("VPN is active")
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Temporary icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed")
        teardownVpn()
        isRunning = false
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN permission revoked by system")
        handleStop()
        super.onRevoke()
    }
}

