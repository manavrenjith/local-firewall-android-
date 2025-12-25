package com.example.aegis.vpn.forwarding

import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.aegis.vpn.enforcement.EnforcementState
import com.example.aegis.vpn.flow.FlowEntry
import com.example.aegis.vpn.packet.FlowKey
import java.net.DatagramSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * ForwarderRegistry - Phase 8: TCP Socket Forwarding
 *                     Phase 8.1: TCP Downlink Reinjection
 *                     Phase 8.2: Forwarding Telemetry & Flow Metrics
 *
 * Central registry for managing TCP forwarders.
 * Maintains one forwarder per flow.
 *
 * Responsibilities:
 * - Create forwarders for ALLOW_READY flows
 * - Track active forwarders
 * - Clean up terminated forwarders
 * - Provide socket protection
 * - Provide TUN interface for reinjection
 * - Optional aggregate statistics (Phase 8.2)
 *
 * Non-responsibilities (Phase 8.2):
 * - UDP forwarding
 * - Decision making
 * - Enforcement
 * - UI control
 */
class ForwarderRegistry(
    private val tunInterface: ParcelFileDescriptor,
    private val protectSocket: (Socket) -> Boolean,
    private val protectDatagramSocket: (DatagramSocket) -> Boolean
) {

    companion object {
        private const val TAG = "ForwarderRegistry"

        // Protocol numbers
        private const val PROTO_TCP = 6
        private const val PROTO_UDP = 17

        // Phase 9: UDP idle timeout
        private const val UDP_IDLE_TIMEOUT_MS = 60_000L  // 60 seconds

        // Phase 10: Soft limits to prevent unbounded growth (fail-open)
        private const val MAX_TCP_FORWARDERS = 1000
        private const val MAX_UDP_FORWARDERS = 2000  // Higher for stateless UDP
    }

    // Active TCP forwarders by FlowKey
    private val tcpForwarders = ConcurrentHashMap<FlowKey, TcpForwarder>()

    // Phase 9: Active UDP forwarders by FlowKey
    private val udpForwarders = ConcurrentHashMap<FlowKey, UdpForwarder>()

    // Statistics
    private var totalTcpForwardersCreated = 0L
    private var totalTcpForwardersClosed = 0L
    private var totalUdpForwardersCreated = 0L
    private var totalUdpForwardersClosed = 0L

    /**
     * Get or create TCP forwarder for a flow.
     * Only creates forwarder if flow is ALLOW_READY.
     * Phase 8.1: Passes TUN interface for reinjection.
     *
     * @param flow FlowEntry to forward
     * @return TcpForwarder if eligible, null otherwise
     */
    fun getOrCreateTcpForwarder(flow: FlowEntry): TcpForwarder? {
        // Phase 8: Only forward TCP flows
        if (flow.protocol != PROTO_TCP) {
            return null
        }

        // Phase 8: Only forward ALLOW_READY flows
        synchronized(flow) {
            if (flow.enforcementState != EnforcementState.ALLOW_READY) {
                return null
            }
        }

        // Check if forwarder already exists
        val existing = tcpForwarders[flow.flowKey]
        if (existing != null && existing.isActive()) {
            return existing
        }

        // Phase 10: Soft limit check (fail-open: drop tracking, not traffic)
        if (tcpForwarders.size >= MAX_TCP_FORWARDERS) {
            Log.w(TAG, "TCP forwarder limit reached (${tcpForwarders.size}), dropping new forwarder creation")
            return null
        }

        // Create new forwarder (Phase 8.1: with TUN interface)
        return try {
            val forwarder = TcpForwarder(flow, tunInterface, protectSocket)

            if (forwarder.initialize()) {
                tcpForwarders[flow.flowKey] = forwarder
                totalTcpForwardersCreated++

                Log.d(TAG, "TCP forwarder created for ${flow.flowKey} (total: ${tcpForwarders.size})")
                forwarder
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating TCP forwarder", e)
            null
        }
    }

    /**
     * Get or create UDP forwarder for a flow.
     * Phase 9: UDP socket forwarding.
     * Only creates forwarder if flow is ALLOW_READY.
     *
     * @param flow FlowEntry to forward
     * @return UdpForwarder if eligible, null otherwise
     */
    fun getOrCreateUdpForwarder(flow: FlowEntry): UdpForwarder? {
        // Phase 9: Only forward UDP flows
        if (flow.protocol != PROTO_UDP) {
            return null
        }

        // Phase 9: Only forward ALLOW_READY flows
        synchronized(flow) {
            if (flow.enforcementState != EnforcementState.ALLOW_READY) {
                return null
            }
        }

        // Check if forwarder already exists
        val existing = udpForwarders[flow.flowKey]
        if (existing != null && existing.isActive()) {
            return existing
        }

        // Phase 10: Soft limit check (fail-open: drop tracking, not traffic)
        if (udpForwarders.size >= MAX_UDP_FORWARDERS) {
            Log.w(TAG, "UDP forwarder limit reached (${udpForwarders.size}), dropping new forwarder creation")
            return null
        }

        // Create new UDP forwarder
        return try {
            val forwarder = UdpForwarder(flow, tunInterface, protectDatagramSocket)

            if (forwarder.initialize()) {
                udpForwarders[flow.flowKey] = forwarder
                totalUdpForwardersCreated++

                Log.d(TAG, "UDP forwarder created for ${flow.flowKey} (total: ${udpForwarders.size})")
                forwarder
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating UDP forwarder", e)
            null
        }
    }

    /**
     * Get existing TCP forwarder for a flow.
     *
     * @param flowKey FlowKey to lookup
     * @return TcpForwarder if exists and active, null otherwise
     */
    fun getTcpForwarder(flowKey: FlowKey): TcpForwarder? {
        val forwarder = tcpForwarders[flowKey]
        return if (forwarder != null && forwarder.isActive()) {
            forwarder
        } else {
            null
        }
    }

    /**
     * Get existing UDP forwarder for a flow.
     * Phase 9: UDP forwarder lookup.
     *
     * @param flowKey FlowKey to lookup
     * @return UdpForwarder if exists and active, null otherwise
     */
    fun getUdpForwarder(flowKey: FlowKey): UdpForwarder? {
        val forwarder = udpForwarders[flowKey]
        return if (forwarder != null && forwarder.isActive()) {
            forwarder
        } else {
            null
        }
    }

    /**
     * Close TCP forwarder for a flow.
     *
     * @param flowKey FlowKey to close
     */
    fun closeTcpForwarder(flowKey: FlowKey) {
        val forwarder = tcpForwarders.remove(flowKey)
        if (forwarder != null) {
            forwarder.close()
            totalTcpForwardersClosed++

            Log.d(TAG, "TCP forwarder closed for $flowKey (total: ${tcpForwarders.size})")
        }
    }

    /**
     * Close UDP forwarder for a flow.
     * Phase 9: UDP forwarder cleanup.
     *
     * @param flowKey FlowKey to close
     */
    fun closeUdpForwarder(flowKey: FlowKey) {
        val forwarder = udpForwarders.remove(flowKey)
        if (forwarder != null) {
            forwarder.close()
            totalUdpForwardersClosed++

            Log.d(TAG, "UDP forwarder closed for $flowKey (total: ${udpForwarders.size})")
        }
    }

    /**
     * Clean up inactive forwarders.
     * Called periodically.
     * Phase 9: Also cleans up idle UDP forwarders.
     * Phase 10: Defensive error handling per forwarder.
     */
    fun cleanup() {
        // Phase 10: Cleanup TCP forwarders with per-forwarder error containment
        var tcpRemoved = 0
        try {
            val tcpIterator = tcpForwarders.entries.iterator()

            while (tcpIterator.hasNext()) {
                try {
                    val entry = tcpIterator.next()
                    val forwarder = entry.value

                    if (!forwarder.isActive()) {
                        forwarder.close()
                        tcpIterator.remove()
                        tcpRemoved++
                        totalTcpForwardersClosed++
                    }
                } catch (e: Exception) {
                    // Phase 10: Defensive - continue cleanup even if one fails
                    Log.w(TAG, "Error cleaning up individual TCP forwarder: ${e.message}")
                }
            }

            if (tcpRemoved > 0) {
                Log.d(TAG, "Cleaned up $tcpRemoved inactive TCP forwarders (total: ${tcpForwarders.size})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during TCP forwarder cleanup", e)
        }

        // Phase 10: Cleanup UDP forwarders with per-forwarder error containment
        var udpRemoved = 0
        try {
            val udpIterator = udpForwarders.entries.iterator()

            while (udpIterator.hasNext()) {
                try {
                    val entry = udpIterator.next()
                    val forwarder = entry.value

                    if (!forwarder.isActive() || forwarder.isIdle(UDP_IDLE_TIMEOUT_MS)) {
                        forwarder.close()
                        udpIterator.remove()
                        udpRemoved++
                        totalUdpForwardersClosed++
                    }
                } catch (e: Exception) {
                    // Phase 10: Defensive - continue cleanup even if one fails
                    Log.w(TAG, "Error cleaning up individual UDP forwarder: ${e.message}")
                }
            }

            if (udpRemoved > 0) {
                Log.d(TAG, "Cleaned up $udpRemoved idle UDP forwarders (total: ${udpForwarders.size})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during UDP forwarder cleanup", e)
        }
    }

    /**
     * Close all forwarders.
     * Called on VPN stop.
     * Phase 9: Closes both TCP and UDP forwarders.
     * Phase 10: Defensive per-forwarder error handling.
     */
    fun closeAll() {
        val tcpCount = tcpForwarders.size
        val udpCount = udpForwarders.size

        // Phase 10: Close TCP forwarders with per-forwarder error containment
        try {
            tcpForwarders.values.forEach { forwarder ->
                try {
                    forwarder.close()
                } catch (e: Exception) {
                    // Defensive: continue closing others
                }
            }
            tcpForwarders.clear()
            totalTcpForwardersClosed += tcpCount
        } catch (e: Exception) {
            Log.e(TAG, "Error closing TCP forwarders", e)
            // Defensive: try to clear anyway
            try {
                tcpForwarders.clear()
            } catch (ignored: Exception) {
            }
        }

        // Phase 10: Close UDP forwarders with per-forwarder error containment
        try {
            udpForwarders.values.forEach { forwarder ->
                try {
                    forwarder.close()
                } catch (e: Exception) {
                    // Defensive: continue closing others
                }
            }
            udpForwarders.clear()
            totalUdpForwardersClosed += udpCount
        } catch (e: Exception) {
            Log.e(TAG, "Error closing UDP forwarders", e)
            // Defensive: try to clear anyway
            try {
                udpForwarders.clear()
            } catch (ignored: Exception) {
            }
        }

        Log.d(TAG, "All forwarders closed (TCP: $tcpCount, UDP: $udpCount)")
    }

    /**
     * Get statistics.
     * Phase 8.2: Enhanced with telemetry support.
     * Phase 9: Includes UDP forwarder statistics.
     */
    fun getStatistics(): ForwarderStatistics {
        return ForwarderStatistics(
            activeTcpForwarders = tcpForwarders.size,
            activeUdpForwarders = udpForwarders.size,
            totalTcpForwardersCreated = totalTcpForwardersCreated,
            totalTcpForwardersClosed = totalTcpForwardersClosed,
            totalUdpForwardersCreated = totalUdpForwardersCreated,
            totalUdpForwardersClosed = totalUdpForwardersClosed
        )
    }
}

/**
 * Forwarder registry statistics.
 * Phase 8.2: Aggregate statistics for observation.
 * Phase 9: Includes UDP statistics.
 */
data class ForwarderStatistics(
    val activeTcpForwarders: Int,
    val activeUdpForwarders: Int,
    val totalTcpForwardersCreated: Long,
    val totalTcpForwardersClosed: Long,
    val totalUdpForwardersCreated: Long,
    val totalUdpForwardersClosed: Long
)

