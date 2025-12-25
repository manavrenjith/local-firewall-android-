package com.example.aegis.vpn.flow

import com.example.aegis.vpn.packet.FlowKey
import com.example.aegis.vpn.packet.TransportHeader

/**
 * FlowEntry - Phase 4: Flow Table & Metadata
 *
 * Mutable entry representing a network flow.
 * Contains counters, timestamps, and metadata.
 * Observation-only - does not enforce policy.
 */
data class FlowEntry(
    val flowKey: FlowKey,
    val protocol: Int,
    val firstSeenTimestamp: Long = System.currentTimeMillis(),
    var lastSeenTimestamp: Long = System.currentTimeMillis(),
    var packetCount: Long = 0,
    var byteCount: Long = 0,
    var transportMetadata: TransportMetadata? = null,

    // Phase 4: Placeholder fields (not yet implemented)
    var uid: Int = UID_UNKNOWN,
    var decision: FlowDecision = FlowDecision.UNDECIDED
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

