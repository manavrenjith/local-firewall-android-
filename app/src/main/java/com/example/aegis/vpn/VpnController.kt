package com.example.aegis.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.example.aegis.vpn.telemetry.FlowSnapshot

/**
 * VpnController - Helper for VPN service intent routing.
 * Phase 1 — VPN Skeleton & Lifecycle
 * Phase 8.3 — Flow Snapshot Exposure & UI Bridge
 *
 * Provides clean separation between intent handling and VPN lifecycle.
 * Exposes read-only snapshot access for UI layer.
 */
object VpnController {

    private const val TAG = "VpnController"

    /**
     * Prepares VPN for activation. Must be called before startVpn().
     *
     * @return Intent to request VPN permission, or null if already granted
     */
    fun prepare(context: Context): Intent? {
        return VpnService.prepare(context)
    }

    /**
     * Starts the VPN service.
     * Safe to call multiple times.
     *
     * @param context Application or activity context
     */
    fun startVpn(context: Context) {
        Log.d(TAG, "Requesting VPN start")
        val intent = Intent(context, AegisVpnService::class.java).apply {
            action = VpnConstants.ACTION_START
        }
        context.startForegroundService(intent)
    }

    /**
     * Stops the VPN service.
     * Safe to call multiple times.
     *
     * @param context Application or activity context
     */
    fun stopVpn(context: Context) {
        Log.d(TAG, "Requesting VPN stop")
        val intent = Intent(context, AegisVpnService::class.java).apply {
            action = VpnConstants.ACTION_STOP
        }
        context.startService(intent)
    }

    // ========== Phase 8.3: Snapshot Access (Read-Only) ==========

    /**
     * Get current flow snapshots.
     * Phase 8.3: Thread-safe, UI-safe access to flow telemetry.
     *
     * @return List of immutable FlowSnapshot objects, empty if VPN stopped
     */
    fun getFlowSnapshots(): List<FlowSnapshot> {
        return try {
            AegisVpnService.getInstance()?.getSnapshotProvider()?.getFlowSnapshots() ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Error retrieving flow snapshots: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get total number of tracked flows.
     * Phase 8.3: Fast aggregate metric.
     *
     * @return Flow count, 0 if VPN stopped
     */
    fun getFlowCount(): Int {
        return try {
            AegisVpnService.getInstance()?.getSnapshotProvider()?.getFlowCount() ?: 0
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Get total forwarded bytes (uplink + downlink).
     * Phase 8.3: Aggregate metric.
     *
     * @return Total bytes forwarded, 0 if VPN stopped
     */
    fun getTotalForwardedBytes(): Long {
        return try {
            AegisVpnService.getInstance()?.getSnapshotProvider()?.getTotalForwardedBytes() ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Get total uplink bytes.
     * Phase 8.3: Aggregate uplink metric.
     *
     * @return Total bytes forwarded client → server, 0 if VPN stopped
     */
    fun getTotalUplinkBytes(): Long {
        return try {
            AegisVpnService.getInstance()?.getSnapshotProvider()?.getTotalUplinkBytes() ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Get total downlink bytes.
     * Phase 8.3: Aggregate downlink metric.
     *
     * @return Total bytes reinjected server → client, 0 if VPN stopped
     */
    fun getTotalDownlinkBytes(): Long {
        return try {
            AegisVpnService.getInstance()?.getSnapshotProvider()?.getTotalDownlinkBytes() ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Get count of actively forwarding flows.
     * Phase 8.3: Activity metric.
     *
     * @param idleThresholdMs Idle threshold (default 30s)
     * @return Count of active flows, 0 if VPN stopped
     */
    fun getActiveFlowCount(idleThresholdMs: Long = 30_000L): Int {
        return try {
            AegisVpnService.getInstance()?.getSnapshotProvider()?.getActiveFlowCount(idleThresholdMs) ?: 0
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Get total forwarded packets.
     * Phase 8.3: Aggregate packet metric.
     *
     * @return Total packets forwarded, 0 if VPN stopped
     */
    fun getTotalForwardedPackets(): Long {
        return try {
            AegisVpnService.getInstance()?.getSnapshotProvider()?.getTotalForwardedPackets() ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Get total forwarding errors.
     * Phase 8.3: Error metric.
     *
     * @return Total errors, 0 if VPN stopped
     */
    fun getTotalForwardingErrors(): Long {
        return try {
            AegisVpnService.getInstance()?.getSnapshotProvider()?.getTotalForwardingErrors() ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Check if snapshot provider is available.
     * Phase 8.3: Health check for UI.
     *
     * @return true if VPN running and provider operational
     */
    fun isSnapshotProviderAvailable(): Boolean {
        return try {
            AegisVpnService.getInstance()?.getSnapshotProvider()?.isAvailable() ?: false
        } catch (e: Exception) {
            false
        }
    }
}

