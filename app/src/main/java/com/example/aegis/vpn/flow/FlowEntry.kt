package com.example.aegis.vpn.flow

import com.example.aegis.vpn.enforcement.EnforcementState
import com.example.aegis.vpn.packet.FlowKey
import com.example.aegis.vpn.packet.TransportHeader
import com.example.aegis.vpn.telemetry.FlowTelemetry

/**
 * FlowEntry - Phase 4: Flow Table & Metadata
 *             Phase 7: Enforcement Controller
 *             Phase 8.2: Forwarding Telemetry & Flow Metrics
 *
 * Mutable entry representing a network flow.
 * Contains counters, timestamps, metadata, and enforcement state.
 * Observation-only - does not enforce policy yet.
 */
data class FlowEntry(
    val flowKey: FlowKey,
    val protocol: Int,
    val firstSeenTimestamp: Long = System.currentTimeMillis(),
    var lastSeenTimestamp: Long = System.currentTimeMillis(),
    var packetCount: Long = 0,
    var byteCount: Long = 0,
    var transportMetadata: TransportMetadata? = null,

    // Phase 5: UID and decision
    var uid: Int = UID_UNKNOWN,
    var decision: FlowDecision = FlowDecision.UNDECIDED,

    // Phase 7: Enforcement state (metadata only)
    var enforcementState: EnforcementState = EnforcementState.NONE,

    // Phase 8.2: Forwarding telemetry (observation only)
    var telemetry: FlowTelemetry = FlowTelemetry()
) {
    companion object {
        const val UID_UNKNOWN = -1
    }

    /**
     * Update flow counters and timestamp.
     * Thread-safe when called within synchronized block.
     */
    fun update(packetLength: Int) {
        lastSeenTimestamp = System.currentTimeMillis()
        packetCount++
        byteCount += packetLength
    }

    /**
     * Check if flow is idle based on timeout.
     */
    fun isIdle(timeoutMillis: Long): Boolean {
        return System.currentTimeMillis() - lastSeenTimestamp > timeoutMillis
    }

    /**
     * Get flow age in milliseconds.
     * Phase 7: Used for confidence checks.
     */
    fun getAge(): Long {
        return System.currentTimeMillis() - firstSeenTimestamp
    }
}

/**
 * Transport-specific metadata (optional).
 */
sealed class TransportMetadata {
    data class TcpMetadata(
        val initialSeq: Long,
        val initialAck: Long,
        var synSeen: Boolean = false,
        var finSeen: Boolean = false,
        var rstSeen: Boolean = false
    ) : TransportMetadata()

    data class UdpMetadata(
        val typicalPacketSize: Int
    ) : TransportMetadata()

    data class IcmpMetadata(
        val type: Int,
        val code: Int
    ) : TransportMetadata()
}

/**
 * Flow decision placeholder (Phase 5+).
 */
enum class FlowDecision {
    UNDECIDED,  // Phase 4: Default state
    ALLOW,      // Phase 5+: Not yet implemented
    BLOCK       // Phase 5+: Not yet implemented
}

