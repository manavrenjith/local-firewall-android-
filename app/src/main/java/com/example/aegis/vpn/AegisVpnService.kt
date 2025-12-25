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
 * Aegis VPN Service - Phase 2: TUN Interface & Routing
 *
 * Responsibilities:
 * - VPN lifecycle management (start/stop)
 * - Foreground service with persistent notification
 * - VPN establishment and teardown
 * - Idempotent operation handling
 * - TUN packet reading (observation only)
 * - Read thread lifecycle management
 *
 * Non-responsibilities (Phase 2):
 * - No packet parsing (no IP/TCP/UDP headers)
 * - No packet modification
 * - No packet forwarding
 * - No socket operations
 * - No UID attribution
 * - No rules or enforcement
 */
class AegisVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunReader: TunReader? = null
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
            // Start TUN read loop after successful establishment
            startTunReader()
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
        stopTunReader()
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
     * Starts TUN packet reader.
     * Called after VPN establishment.
     */
    private fun startTunReader() {
        val iface = vpnInterface
        if (iface == null) {
            Log.e(TAG, "Cannot start TunReader: vpnInterface is null")
            return
        }

        try {
            tunReader = TunReader(iface) {
                // Error callback - triggered on unrecoverable read errors
                Log.e(TAG, "TunReader reported error, initiating VPN teardown")
                handleStop()
            }
            tunReader?.start()
            Log.d(TAG, "TunReader started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start TunReader", e)
            handleStop()
        }
    }

    /**
     * Stops TUN packet reader.
     * Called before VPN teardown.
     * Safe to call multiple times.
     */
    private fun stopTunReader() {
        try {
            tunReader?.stop()
            tunReader = null
            Log.d(TAG, "TunReader stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping TunReader", e)
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
        stopTunReader()
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

