package com.example.aegis.vpn.telemetry

import android.util.Log
import com.example.aegis.vpn.flow.FlowTable
import com.example.aegis.vpn.forwarding.ForwarderRegistry
import java.util.concurrent.atomic.AtomicLong

/**
 * TelemetryLogger - Phase 8.2: Forwarding Telemetry & Flow Metrics
 *
 * Optional rate-limited aggregate telemetry logger.
 * Disabled by default - for debugging only.
 *
 * Responsibilities:
 * - Log aggregate statistics periodically
 * - Rate-limit logging to avoid spam
 * - Never interfere with forwarding
 *
 * Non-responsibilities:
 * - Per-packet logging
 * - Control flow
 * - UI updates
 * - Enforcement
 */
object TelemetryLogger {

    private const val TAG = "TelemetryLogger"

    // Debug flag - disabled by default
    private const val DEBUG_ENABLED = false

    // Minimum interval between logs (milliseconds)
    private const val MIN_LOG_INTERVAL_MS = 30_000L  // 30 seconds

    private val lastLogTime = AtomicLong(0)

    /**
     * Log aggregate telemetry if enabled and not rate-limited.
     * Phase 8.2: Best-effort, never throws.
     *
     * @param flowTable FlowTable to snapshot
     * @param forwarderRegistry ForwarderRegistry for statistics
     */
    fun logAggregateTelemetry(
        flowTable: FlowTable,
        forwarderRegistry: ForwarderRegistry
    ) {
        if (!DEBUG_ENABLED) {
            return
        }

        try {
            val now = System.currentTimeMillis()
            val lastLog = lastLogTime.get()

            // Rate limiting
            if (now - lastLog < MIN_LOG_INTERVAL_MS) {
                return
            }

            if (!lastLogTime.compareAndSet(lastLog, now)) {
                // Another thread updated, skip
                return
            }

            // Gather statistics
            val flowStats = flowTable.getStatistics()
            val forwarderStats = forwarderRegistry.getStatistics()
            val snapshots = flowTable.snapshotFlows()

            // Aggregate telemetry
            var totalUplinkBytes = 0L
            var totalDownlinkBytes = 0L
            var totalForwardedPackets = 0L
            var totalErrors = 0L
            var activeForwarding = 0

            snapshots.forEach { snapshot ->
                totalUplinkBytes += snapshot.uplinkBytes
                totalDownlinkBytes += snapshot.downlinkBytes
                totalForwardedPackets += snapshot.getTotalForwardedPackets()
                totalErrors += snapshot.forwardingErrors

                if (snapshot.isActivelyForwarding()) {
                    activeForwarding++
                }
            }

            // Log aggregate data
            Log.d(TAG, "=== Telemetry Snapshot ===")
            Log.d(TAG, "Flows: ${flowStats.totalFlows} (TCP: ${flowStats.tcpFlows})")
            Log.d(TAG, "Forwarders: ${forwarderStats.activeForwarders} active, " +
                    "${forwarderStats.totalForwardersCreated} created, " +
                    "${forwarderStats.totalForwardersClosed} closed")
            Log.d(TAG, "Forwarding: $activeForwarding active flows")
            Log.d(TAG, "Traffic: ${formatBytes(totalUplinkBytes)} ↑ / ${formatBytes(totalDownlinkBytes)} ↓")
            Log.d(TAG, "Packets: $totalForwardedPackets forwarded")
            Log.d(TAG, "Errors: $totalErrors")
            Log.d(TAG, "=========================")

        } catch (e: Exception) {
            // Silently ignore telemetry logging errors
        }
    }

    /**
     * Format bytes for human-readable output.
     */
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }

    /**
     * Check if debug logging is enabled.
     */
    fun isDebugEnabled(): Boolean = DEBUG_ENABLED
}

