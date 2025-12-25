package com.example.aegis.vpn.flow

import android.util.Log
import com.example.aegis.vpn.packet.FlowKey
import com.example.aegis.vpn.packet.ParsedPacket
import com.example.aegis.vpn.packet.TransportHeader
import com.example.aegis.vpn.telemetry.FlowSnapshot
import java.util.concurrent.ConcurrentHashMap

/**
 * FlowTable - Phase 4: Flow Table & Metadata
 *             Phase 5: UID Attribution
 *             Phase 6: Decision Engine (Decision-Only, No Enforcement)
 *             Phase 8.2: Forwarding Telemetry & Flow Metrics
 *
 * Thread-safe in-memory flow tracking table.
 * Maps FlowKey to FlowEntry for active connections.
 *
 * Responsibilities:
 * - Track active flows
 * - Update counters and timestamps
 * - Clean up idle flows
 * - Extract transport metadata
 * - Support UID attribution (Phase 5)
 * - Support decision evaluation (Phase 6)
 * - Provide flow snapshots (Phase 8.2)
 *
 * Non-responsibilities (Phase 6):
 * - No rule enforcement
 * - No packet forwarding
 * - No persistent storage
 */
class FlowTable {

    companion object {
        private const val TAG = "FlowTable"

        // Protocol numbers
        private const val PROTO_TCP = 6
        private const val PROTO_UDP = 17
        private const val PROTO_ICMP = 1

        // Idle timeouts (milliseconds)
        private const val TCP_TIMEOUT_MS = 300_000L      // 5 minutes
        private const val UDP_TIMEOUT_MS = 60_000L       // 1 minute
        private const val ICMP_TIMEOUT_MS = 10_000L      // 10 seconds
        private const val DEFAULT_TIMEOUT_MS = 60_000L   // 1 minute

        // Cleanup interval
        private const val CLEANUP_INTERVAL_MS = 30_000L  // 30 seconds
    }

    // Thread-safe flow map
    private val flows = ConcurrentHashMap<FlowKey, FlowEntry>()

    // Last cleanup timestamp
    private var lastCleanupTime = System.currentTimeMillis()

    /**
     * Process a parsed packet and update flow table.
     * Creates flow entry if needed, updates counters.
     *
     * @param packet Parsed packet
     * @param packetLength Original packet length
     */
    fun processPacket(packet: ParsedPacket, packetLength: Int) {
        try {
            val flowKey = packet.flowKey

            // Get or create flow entry
            val flow = flows.getOrPut(flowKey) {
                createFlowEntry(packet)
            }

            // Update flow counters (synchronized for consistency)
            synchronized(flow) {
                flow.update(packetLength)

                // Update transport metadata if needed
                updateTransportMetadata(flow, packet.transportHeader)
            }

            // Periodic cleanup check (time-based)
            checkCleanup()

        } catch (e: Exception) {
            // Never crash VPN on flow table errors
            Log.e(TAG, "Error processing packet in flow table", e)
        }
    }

    /**
     * Get existing flow by FlowKey.
     * Phase 8: Used for forwarding lookup.
     *
     * @param flowKey FlowKey to lookup
     * @return FlowEntry if exists, null otherwise
     */
    fun getFlow(flowKey: FlowKey): FlowEntry? {
        return flows[flowKey]
    }

    /**
     * Create new flow entry from parsed packet.
     */
    private fun createFlowEntry(packet: ParsedPacket): FlowEntry {
        val metadata = extractInitialMetadata(packet.transportHeader)

        return FlowEntry(
            flowKey = packet.flowKey,
            protocol = packet.ipHeader.protocol,
            transportMetadata = metadata
        )
    }

    /**
     * Extract initial transport metadata.
     */
    private fun extractInitialMetadata(header: TransportHeader): TransportMetadata? {
        return when (header) {
            is TransportHeader.Tcp -> {
                TransportMetadata.TcpMetadata(
                    initialSeq = header.sequenceNumber,
                    initialAck = header.acknowledgmentNumber,
                    synSeen = header.flags.syn,
                    finSeen = header.flags.fin,
                    rstSeen = header.flags.rst
                )
            }
            is TransportHeader.Udp -> {
                TransportMetadata.UdpMetadata(
                    typicalPacketSize = header.length
                )
            }
            is TransportHeader.Icmp -> {
                TransportMetadata.IcmpMetadata(
                    type = header.type,
                    code = header.code
                )
            }
            else -> null
        }
    }

    /**
     * Update transport metadata for existing flow.
     */
    private fun updateTransportMetadata(flow: FlowEntry, header: TransportHeader) {
        when (header) {
            is TransportHeader.Tcp -> {
                val metadata = flow.transportMetadata as? TransportMetadata.TcpMetadata
                metadata?.apply {
                    synSeen = synSeen || header.flags.syn
                    finSeen = finSeen || header.flags.fin
                    rstSeen = rstSeen || header.flags.rst
                }
            }
            else -> {
                // UDP/ICMP metadata doesn't need updates
            }
        }
    }

    /**
     * Check if cleanup should run and execute if needed.
     * Time-based, not packet-count based.
     */
    private fun checkCleanup() {
        val now = System.currentTimeMillis()

        if (now - lastCleanupTime > CLEANUP_INTERVAL_MS) {
            lastCleanupTime = now
            cleanup()
        }
    }

    /**
     * Remove idle flows based on protocol-specific timeouts.
     * Deterministic and bounded operation.
     */
    private fun cleanup() {
        try {
            val now = System.currentTimeMillis()
            val before = flows.size

            // Iterate and remove idle flows
            val iterator = flows.entries.iterator()
            var removed = 0

            while (iterator.hasNext()) {
                val entry = iterator.next()
                val flow = entry.value

                val timeout = getTimeoutForProtocol(flow.protocol)

                synchronized(flow) {
                    if (flow.isIdle(timeout)) {
                        iterator.remove()
                        removed++
                    }
                }
            }

            if (removed > 0) {
                Log.d(TAG, "Flow cleanup: removed $removed idle flows ($before -> ${flows.size})")
            }

        } catch (e: Exception) {
            // Log cleanup errors but don't crash
            Log.e(TAG, "Error during flow cleanup", e)
        }
    }

    /**
     * Get timeout for protocol.
     */
    private fun getTimeoutForProtocol(protocol: Int): Long {
        return when (protocol) {
            PROTO_TCP -> TCP_TIMEOUT_MS
            PROTO_UDP -> UDP_TIMEOUT_MS
            PROTO_ICMP -> ICMP_TIMEOUT_MS
            else -> DEFAULT_TIMEOUT_MS
        }
    }

    /**
     * Get current flow count (for statistics).
     */
    fun getFlowCount(): Int = flows.size

    /**
     * Get statistics snapshot.
     */
    fun getStatistics(): FlowTableStats {
        var tcpFlows = 0
        var udpFlows = 0
        var icmpFlows = 0
        var otherFlows = 0
        var totalPackets = 0L
        var totalBytes = 0L

        flows.values.forEach { flow ->
            synchronized(flow) {
                when (flow.protocol) {
                    PROTO_TCP -> tcpFlows++
                    PROTO_UDP -> udpFlows++
                    PROTO_ICMP -> icmpFlows++
                    else -> otherFlows++
                }
                totalPackets += flow.packetCount
                totalBytes += flow.byteCount
            }
        }

        return FlowTableStats(
            totalFlows = flows.size,
            tcpFlows = tcpFlows,
            udpFlows = udpFlows,
            icmpFlows = icmpFlows,
            otherFlows = otherFlows,
            totalPackets = totalPackets,
            totalBytes = totalBytes
        )
    }

    /**
     * Clear all flows (for testing or cleanup).
     */
    fun clear() {
        flows.clear()
        Log.d(TAG, "Flow table cleared")
    }

    /**
     * Iterate through flows for UID attribution.
     * Phase 5: Allows UidResolver to process flows without exposing internal map.
     *
     * @param action Function to apply to each flow
     */
    fun attributeUids(action: (FlowEntry) -> Unit) {
        try {
            flows.values.forEach { flow ->
                try {
                    action(flow)
                } catch (e: Exception) {
                    // Skip this flow on error
                }
            }
        } catch (e: Exception) {
            // Swallow errors - never break flow table
            Log.e(TAG, "Error during UID attribution iteration", e)
        }
    }

    /**
     * Iterate through flows for decision evaluation.
     * Phase 6: Allows DecisionEngine to process flows without exposing internal map.
     *
     * @param action Function to apply to each flow
     */
    fun evaluateDecisions(action: (FlowEntry) -> Unit) {
        try {
            flows.values.forEach { flow ->
                try {
                    action(flow)
                } catch (e: Exception) {
                    // Skip this flow on error
                }
            }
        } catch (e: Exception) {
            // Swallow errors - never break flow table
            Log.e(TAG, "Error during decision evaluation iteration", e)
        }
    }

    /**
     * Iterate through flows for enforcement evaluation.
     * Phase 7: Allows EnforcementController to process flows without exposing internal map.
     *
     * @param action Function to apply to each flow
     */
    fun evaluateEnforcement(action: (FlowEntry) -> Unit) {
        try {
            flows.values.forEach { flow ->
                try {
                    action(flow)
                } catch (e: Exception) {
                    // Skip this flow on error
                }
            }
        } catch (e: Exception) {
            // Swallow errors - never break flow table
            Log.e(TAG, "Error during enforcement evaluation iteration", e)
        }
    }

    /**
     * Create snapshot of all flows.
     * Phase 8.2: Returns immutable snapshots for observation/debugging.
     *
     * @return List of immutable FlowSnapshot objects
     */
    fun snapshotFlows(): List<FlowSnapshot> {
        return try {
            flows.values.mapNotNull { flow ->
                try {
                    synchronized(flow) {
                        val now = System.currentTimeMillis()
                        FlowSnapshot(
                            flowKey = flow.flowKey,
                            protocol = flow.protocol,
                            uid = flow.uid,
                            decision = flow.decision,
                            enforcementState = flow.enforcementState,
                            firstSeenTimestamp = flow.firstSeenTimestamp,
                            lastSeenTimestamp = flow.lastSeenTimestamp,
                            flowAge = flow.getAge(),
                            lastActivityTime = now - flow.lastSeenTimestamp,
                            totalPacketCount = flow.packetCount,
                            totalByteCount = flow.byteCount,
                            uplinkPackets = flow.telemetry.uplinkPackets,
                            uplinkBytes = flow.telemetry.uplinkBytes,
                            downlinkPackets = flow.telemetry.downlinkPackets,
                            downlinkBytes = flow.telemetry.downlinkBytes,
                            firstForwardedAt = flow.telemetry.firstForwardedAt,
                            lastForwardedAt = flow.telemetry.lastForwardedAt,
                            forwardingErrors = flow.telemetry.forwardingErrors,
                            tcpResetsSent = flow.telemetry.tcpResetsSent,
                            tcpFinsSent = flow.telemetry.tcpFinsSent,
                            lastActivityDirection = flow.telemetry.lastActivityDirection,
                            forwardingAge = flow.telemetry.getForwardingAge(),
                            forwardingIdleTime = flow.telemetry.getIdleTime()
                        )
                    }
                } catch (e: Exception) {
                    // Skip this flow on snapshot error
                    null
                }
            }
        } catch (e: Exception) {
            // Return empty list on error
            Log.e(TAG, "Error creating flow snapshots", e)
            emptyList()
        }
    }
}

/**
 * Flow table statistics snapshot.
 */
data class FlowTableStats(
    val totalFlows: Int,
    val tcpFlows: Int,
    val udpFlows: Int,
    val icmpFlows: Int,
    val otherFlows: Int,
    val totalPackets: Long,
    val totalBytes: Long
)

