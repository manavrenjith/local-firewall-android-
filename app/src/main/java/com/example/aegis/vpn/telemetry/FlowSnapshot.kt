package com.example.aegis.vpn.telemetry

import com.example.aegis.vpn.enforcement.EnforcementState
import com.example.aegis.vpn.flow.FlowDecision
import com.example.aegis.vpn.packet.FlowKey

/**
 * FlowSnapshot - Phase 8.2: Forwarding Telemetry & Flow Metrics
 *
 * Immutable snapshot of a flow's state at a point in time.
 * Detached from live FlowEntry - safe for UI or logging consumption.
 *
 * Responsibilities:
 * - Provide read-only view of flow state
 * - Capture telemetry metrics
 * - Support debugging and analysis
 *
 * Non-responsibilities:
 * - No mutation
 * - No enforcement
 * - No UI logic
 */
data class FlowSnapshot(
    // Flow identity
    val flowKey: FlowKey,
    val protocol: Int,

    // UID attribution
    val uid: Int,

    // Decision and enforcement
    val decision: FlowDecision,
    val enforcementState: EnforcementState,

    // Flow timing
    val firstSeenTimestamp: Long,
    val lastSeenTimestamp: Long,
    val flowAge: Long,
    val lastActivityTime: Long,

    // Original flow counters (all packets, not just forwarded)
    val totalPacketCount: Long,
    val totalByteCount: Long,

    // Phase 8.2: Forwarding telemetry
    val uplinkPackets: Long,
    val uplinkBytes: Long,
    val downlinkPackets: Long,
    val downlinkBytes: Long,
    val firstForwardedAt: Long,
    val lastForwardedAt: Long,
    val forwardingErrors: Long,
    val tcpResetsSent: Long,
    val tcpFinsSent: Long,
    val lastActivityDirection: Direction,

    // Computed metrics
    val forwardingAge: Long,
    val forwardingIdleTime: Long
) {
    /**
     * Get total forwarded packets.
     */
    fun getTotalForwardedPackets(): Long = uplinkPackets + downlinkPackets

    /**
     * Get total forwarded bytes.
     */
    fun getTotalForwardedBytes(): Long = uplinkBytes + downlinkBytes

    /**
     * Check if flow has any forwarding activity.
     */
    fun hasForwardingActivity(): Boolean = getTotalForwardedPackets() > 0

    /**
     * Get forwarding efficiency (forwarded / total packets).
     * @return Ratio between 0.0 and 1.0, or 0.0 if no packets
     */
    fun getForwardingEfficiency(): Double {
        return if (totalPacketCount > 0) {
            getTotalForwardedPackets().toDouble() / totalPacketCount.toDouble()
        } else {
            0.0
        }
    }

    /**
     * Check if flow is actively forwarding.
     * @param idleThresholdMs Idle threshold in milliseconds
     * @return true if forwarding and not idle
     */
    fun isActivelyForwarding(idleThresholdMs: Long = 30_000L): Boolean {
        return hasForwardingActivity() && forwardingIdleTime < idleThresholdMs
    }
}

