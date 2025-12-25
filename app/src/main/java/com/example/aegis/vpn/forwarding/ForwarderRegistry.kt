package com.example.aegis.vpn.forwarding

import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.aegis.vpn.enforcement.EnforcementState
import com.example.aegis.vpn.flow.FlowEntry
import com.example.aegis.vpn.packet.FlowKey
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
    private val protectSocket: (Socket) -> Boolean
) {

    companion object {
        private const val TAG = "ForwarderRegistry"

        // Protocol numbers
        private const val PROTO_TCP = 6
    }

    // Active forwarders by FlowKey
    private val forwarders = ConcurrentHashMap<FlowKey, TcpForwarder>()

    // Statistics
    private var totalForwardersCreated = 0L
    private var totalForwardersClosed = 0L

    /**
     * Get or create forwarder for a flow.
     * Only creates forwarder if flow is ALLOW_READY.
     * Phase 8.1: Passes TUN interface for reinjection.
     *
     * @param flow FlowEntry to forward
     * @return TcpForwarder if eligible, null otherwise
     */
    fun getOrCreateForwarder(flow: FlowEntry): TcpForwarder? {
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
        val existing = forwarders[flow.flowKey]
        if (existing != null && existing.isActive()) {
            return existing
        }

        // Create new forwarder (Phase 8.1: with TUN interface)
        return try {
            val forwarder = TcpForwarder(flow, tunInterface, protectSocket)

            if (forwarder.initialize()) {
                forwarders[flow.flowKey] = forwarder
                totalForwardersCreated++

                Log.d(TAG, "Forwarder created for ${flow.flowKey} (total: ${forwarders.size})")
                forwarder
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating forwarder", e)
            null
        }
    }

    /**
     * Get existing forwarder for a flow.
     *
     * @param flowKey FlowKey to lookup
     * @return TcpForwarder if exists and active, null otherwise
     */
    fun getForwarder(flowKey: FlowKey): TcpForwarder? {
        val forwarder = forwarders[flowKey]
        return if (forwarder != null && forwarder.isActive()) {
            forwarder
        } else {
            null
        }
    }

    /**
     * Close forwarder for a flow.
     *
     * @param flowKey FlowKey to close
     */
    fun closeForwarder(flowKey: FlowKey) {
        val forwarder = forwarders.remove(flowKey)
        if (forwarder != null) {
            forwarder.close()
            totalForwardersClosed++

            Log.d(TAG, "Forwarder closed for $flowKey (total: ${forwarders.size})")
        }
    }

    /**
     * Clean up inactive forwarders.
     * Called periodically.
     */
    fun cleanup() {
        try {
            val iterator = forwarders.entries.iterator()
            var removed = 0

            while (iterator.hasNext()) {
                val entry = iterator.next()
                val forwarder = entry.value

                if (!forwarder.isActive()) {
                    forwarder.close()
                    iterator.remove()
                    removed++
                    totalForwardersClosed++
                }
            }

            if (removed > 0) {
                Log.d(TAG, "Cleaned up $removed inactive forwarders (total: ${forwarders.size})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during forwarder cleanup", e)
        }
    }

    /**
     * Close all forwarders.
     * Called on VPN stop.
     */
    fun closeAll() {
        try {
            val count = forwarders.size

            forwarders.values.forEach { it.close() }
            forwarders.clear()

            totalForwardersClosed += count

            Log.d(TAG, "All forwarders closed ($count total)")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing all forwarders", e)
        }
    }

    /**
     * Get statistics.
     * Phase 8.2: Enhanced with telemetry support.
     */
    fun getStatistics(): ForwarderStatistics {
        return ForwarderStatistics(
            activeForwarders = forwarders.size,
            totalForwardersCreated = totalForwardersCreated,
            totalForwardersClosed = totalForwardersClosed
        )
    }
}

/**
 * Forwarder registry statistics.
 * Phase 8.2: Aggregate statistics for observation.
 */
data class ForwarderStatistics(
    val activeForwarders: Int,
    val totalForwardersCreated: Long,
    val totalForwardersClosed: Long
)

