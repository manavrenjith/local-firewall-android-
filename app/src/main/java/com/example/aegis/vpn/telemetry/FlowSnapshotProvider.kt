package com.example.aegis.vpn.telemetry

import android.util.Log
import com.example.aegis.vpn.flow.FlowTable

/**
 * FlowSnapshotProvider - Phase 8.3: Flow Snapshot Exposure & UI Bridge
 *
 * Read-only bridge between VPN engine and UI layer.
 * Provides thread-safe, lifecycle-safe access to flow snapshots.
 *
 * Responsibilities:
 * - Expose immutable flow snapshots
 * - Provide aggregate statistics
 * - Fast, non-blocking access
 * - Safe to call from UI thread
 * - Fail-safe when VPN stopped
 *
 * Non-responsibilities:
 * - No UI rendering
 * - No observers/listeners
 * - No background threads
 * - No persistence
 * - No sorting/filtering
 */
class FlowSnapshotProvider(
    private val flowTable: FlowTable
) {

    companion object {
        private const val TAG = "FlowSnapshotProvider"
    }

    /**
     * Get current flow snapshots.
     * Phase 8.3: Thread-safe, immutable snapshots.
     *
     * @return List of immutable FlowSnapshot objects, empty if VPN stopped
     */
    fun getFlowSnapshots(): List<FlowSnapshot> {
        return try {
            flowTable.snapshotFlows()
        } catch (e: Exception) {
            // Fail-safe: return empty list on any error
            Log.w(TAG, "Error retrieving flow snapshots: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get total number of tracked flows.
     * Phase 8.3: Fast aggregate statistic.
     *
     * @return Flow count, 0 if VPN stopped
     */
    fun getFlowCount(): Int {
        return try {
            val snapshots = flowTable.snapshotFlows()
            snapshots.size
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Get total forwarded bytes (uplink + downlink).
     * Phase 8.3: Aggregate metric from snapshots.
     *
     * @return Total bytes forwarded across all flows
     */
    fun getTotalForwardedBytes(): Long {
        return try {
            val snapshots = flowTable.snapshotFlows()
            snapshots.sumOf { it.uplinkBytes + it.downlinkBytes }
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Get total uplink bytes.
     * Phase 8.3: Aggregate uplink metric.
     *
     * @return Total bytes forwarded client → server
     */
    fun getTotalUplinkBytes(): Long {
        return try {
            val snapshots = flowTable.snapshotFlows()
            snapshots.sumOf { it.uplinkBytes }
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Get total downlink bytes.
     * Phase 8.3: Aggregate downlink metric.
     *
     * @return Total bytes reinjected server → client
     */
    fun getTotalDownlinkBytes(): Long {
        return try {
            val snapshots = flowTable.snapshotFlows()
            snapshots.sumOf { it.downlinkBytes }
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Get count of actively forwarding flows.
     * Phase 8.3: Aggregate activity metric.
     *
     * @param idleThresholdMs Idle threshold in milliseconds (default 30s)
     * @return Count of flows actively forwarding
     */
    fun getActiveFlowCount(idleThresholdMs: Long = 30_000L): Int {
        return try {
            val snapshots = flowTable.snapshotFlows()
            snapshots.count { it.isActivelyForwarding(idleThresholdMs) }
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Get total forwarded packets (uplink + downlink).
     * Phase 8.3: Aggregate packet metric.
     *
     * @return Total packets forwarded across all flows
     */
    fun getTotalForwardedPackets(): Long {
        return try {
            val snapshots = flowTable.snapshotFlows()
            snapshots.sumOf { it.uplinkPackets + it.downlinkPackets }
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Get total forwarding errors across all flows.
     * Phase 8.3: Aggregate error metric.
     *
     * @return Total forwarding errors
     */
    fun getTotalForwardingErrors(): Long {
        return try {
            val snapshots = flowTable.snapshotFlows()
            snapshots.sumOf { it.forwardingErrors }
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Check if provider is operational.
     * Phase 8.3: Health check for UI.
     *
     * @return true if provider can retrieve snapshots
     */
    fun isAvailable(): Boolean {
        return try {
            flowTable.snapshotFlows()
            true
        } catch (e: Exception) {
            false
        }
    }
}

