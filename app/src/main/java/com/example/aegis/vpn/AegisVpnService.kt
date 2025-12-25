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
import com.example.aegis.vpn.decision.DecisionEvaluator
import com.example.aegis.vpn.enforcement.EnforcementController
import com.example.aegis.vpn.flow.FlowTable
import com.example.aegis.vpn.forwarding.ForwarderRegistry
import com.example.aegis.vpn.telemetry.FlowSnapshotProvider
import com.example.aegis.vpn.uid.UidResolver

/**
 * Aegis VPN Service - Phase 2: TUN Interface & Routing
 *                      Phase 3: Packet Parsing (Observation Only)
 *                      Phase 4: Flow Table & Metadata (Read-Only)
 *                      Phase 5: UID Attribution (Best-Effort, Metadata Only)
 *                      Phase 6: Decision Engine (Decision-Only, No Enforcement)
 *                      Phase 7: Enforcement Controller (Gatekeeper, No Forwarding)
 *                      Phase 8: TCP Socket Forwarding (Connectivity Restore)
 *                      Phase 8.2: Forwarding Telemetry & Flow Metrics
 *
 * Responsibilities:
 * - VPN lifecycle management (start/stop)
 * - Foreground service with persistent notification
 * - VPN establishment and teardown
 * - Idempotent operation handling
 * - TUN packet reading (Phase 2+)
 * - Read thread lifecycle management
 * - Flow table management (Phase 4)
 * - UID resolution (Phase 5)
 * - Decision evaluation (Phase 6)
 * - Enforcement readiness evaluation (Phase 7)
 * - TCP forwarding for ALLOW_READY flows (Phase 8)
 * - Telemetry tracking (Phase 8.2, observation only)
 *
 * Non-responsibilities (Phase 8.2):
 * - UDP forwarding (Phase 9)
 * - Packet blocking
 * - UI control
 * - Telemetry enforcement
 */
class AegisVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunReader: TunReader? = null
    private var flowTable: FlowTable? = null
    private var uidResolver: UidResolver? = null
    private var decisionEvaluator: DecisionEvaluator? = null
    private var enforcementController: EnforcementController? = null
    private var forwarderRegistry: ForwarderRegistry? = null
    private var snapshotProvider: FlowSnapshotProvider? = null
    private var isRunning = false

    companion object {
        private const val TAG = "AegisVpnService"

        // Phase 8.3: Singleton reference for UI bridge access
        @Volatile
        private var instance: AegisVpnService? = null

        /**
         * Get current service instance.
         * Phase 8.3: Allows VpnController to access snapshot provider.
         *
         * @return Service instance if running, null otherwise
         */
        internal fun getInstance(): AegisVpnService? = instance
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
     * Phase 4: Creates flow table for tracking.
     * Phase 5: Creates UID resolver for attribution.
     * Phase 6: Creates decision evaluator.
     * Phase 7: Creates enforcement controller.
     * Phase 8: Creates forwarder registry.
     */
    private fun startTunReader() {
        val iface = vpnInterface
        if (iface == null) {
            Log.e(TAG, "Cannot start TunReader: vpnInterface is null")
            return
        }

        try {
            // Phase 4: Create flow table
            flowTable = FlowTable()

            // Phase 8.3: Create snapshot provider for UI bridge
            snapshotProvider = FlowSnapshotProvider(flowTable!!)

            // Phase 5: Create UID resolver
            uidResolver = UidResolver(flowTable!!)

            // Phase 6: Create decision evaluator
            decisionEvaluator = DecisionEvaluator(flowTable!!)

            // Phase 7: Create enforcement controller
            enforcementController = EnforcementController(flowTable!!)

            // Phase 8/8.1: Create forwarder registry with TUN interface and socket protection
            forwarderRegistry = ForwarderRegistry(iface) { socket ->
                // Protect socket to prevent routing loop
                protect(socket)
            }

            tunReader = TunReader(iface, flowTable!!, uidResolver!!, decisionEvaluator!!,
                                  enforcementController!!, forwarderRegistry!!) {
                // Error callback - triggered on unrecoverable read errors
                Log.e(TAG, "TunReader reported error, initiating VPN teardown")
                handleStop()
            }
            tunReader?.start()
            Log.d(TAG, "TunReader started with flow tracking, UID resolution, decision evaluation, " +
                    "enforcement control, and bidirectional TCP forwarding")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start TunReader", e)
            handleStop()
        }
    }

    /**
     * Stops TUN packet reader.
     * Called before VPN teardown.
     * Safe to call multiple times.
     * Phase 4: Also clears flow table.
     * Phase 5: Also nullifies UID resolver.
     * Phase 6: Also nullifies decision evaluator.
     * Phase 7: Also nullifies enforcement controller.
     * Phase 8: Also closes all forwarders.
     * Phase 8.3: Also nullifies snapshot provider.
     */
    private fun stopTunReader() {
        try {
            tunReader?.stop()
            tunReader = null

            // Phase 8: Close all forwarders
            forwarderRegistry?.closeAll()
            forwarderRegistry = null

            // Phase 8.3: Clear snapshot provider
            snapshotProvider = null

            // Phase 4: Clear flow table
            flowTable?.clear()
            flowTable = null

            // Phase 5: Clear UID resolver
            uidResolver = null

            // Phase 6: Clear decision evaluator
            decisionEvaluator = null

            // Phase 7: Clear enforcement controller
            enforcementController = null

            Log.d(TAG, "TunReader stopped, forwarders closed, snapshot provider released, " +
                    "flow table cleared, components released")
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

        // Phase 8.3: Clear instance reference
        instance = null

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

    /**
     * Get snapshot provider for UI bridge.
     * Phase 8.3: Read-only access to flow snapshots.
     *
     * @return FlowSnapshotProvider if VPN running, null otherwise
     */
    fun getSnapshotProvider(): FlowSnapshotProvider? {
        return snapshotProvider
    }
}

