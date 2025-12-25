package com.example.aegis.vpn.uid

import android.util.Log
import com.example.aegis.vpn.flow.FlowEntry
import com.example.aegis.vpn.flow.FlowTable

/**
 * UidResolver - Phase 5: UID Attribution
 *
 * Resolves UIDs for network flows using kernel socket tables.
 * Best-effort attribution - failures are graceful.
 *
 * Responsibilities:
 * - Read proc net files
 * - Match socket entries to flows
 * - Assign UIDs to FlowEntry objects
 * - Cache socket table snapshots
 *
 * Non-responsibilities (Phase 5):
 * - No blocking or enforcement
 * - No packet modification
 * - No traffic control
 * - No new threads (uses existing timing)
 */
class UidResolver(private val flowTable: FlowTable) {

    companion object {
        private const val TAG = "UidResolver"

        // Protocol numbers
        private const val PROTO_TCP = 6
        private const val PROTO_UDP = 17

        // Cache TTL
        private const val CACHE_TTL_MS = 5000L  // 5 seconds

        // Retry timing
        private const val RETRY_INTERVAL_MS = 10000L  // 10 seconds
    }

    // Cached socket entries
    private var cachedTcpSockets: List<SocketEntry> = emptyList()
    private var cachedUdpSockets: List<SocketEntry> = emptyList()
    private var lastCacheTime = 0L

    // Last retry time
    private var lastRetryTime = 0L

    // Error logging throttle
    private var lastErrorLogTime = 0L
    private val ERROR_LOG_INTERVAL_MS = 60000L  // 1 minute

    /**
     * Attempt to resolve UIDs for flows that don't have attribution yet.
     * Called periodically from existing timing mechanisms.
     * Non-blocking and best-effort.
     */
    fun resolveUids() {
        try {
            val now = System.currentTimeMillis()

            // Check if we should retry
            if (now - lastRetryTime < RETRY_INTERVAL_MS) {
                return
            }
            lastRetryTime = now

            // Refresh cache if needed
            if (now - lastCacheTime > CACHE_TTL_MS) {
                refreshCache()
                lastCacheTime = now
            }

            // Get statistics to find flows needing attribution
            val stats = flowTable.getStatistics()
            if (stats.totalFlows == 0) {
                return
            }

            // Attempt attribution for unknown UIDs
            attributeFlows()

        } catch (e: Exception) {
            // Swallow errors - never break VPN
            logError("Error in UID resolution", e)
        }
    }

    /**
     * Refresh cached socket entries from proc net files.
     */
    private fun refreshCache() {
        try {
            cachedTcpSockets = ProcNetParser.parseTcp()
            cachedUdpSockets = ProcNetParser.parseUdp()

            Log.d(TAG, "Socket cache refreshed: TCP=${cachedTcpSockets.size}, UDP=${cachedUdpSockets.size}")

        } catch (e: Exception) {
            logError("Error refreshing socket cache", e)
            // Keep old cache
        }
    }

    /**
     * Attempt to attribute UIDs to flows.
     * Uses cached socket entries.
     */
    private fun attributeFlows() {
        try {
            // Get all flows (thread-safe snapshot via statistics)
            // We'll iterate through the internal flow map via FlowTable
            flowTable.attributeUids { flow ->
                if (flow.uid == -1) {  // UID_UNKNOWN
                    resolveFlowUid(flow)
                }
            }
        } catch (e: Exception) {
            logError("Error attributing flows", e)
        }
    }

    /**
     * Attempt to resolve UID for a single flow.
     */
    private fun resolveFlowUid(flow: FlowEntry) {
        try {
            val socketEntries = when (flow.protocol) {
                PROTO_TCP -> cachedTcpSockets
                PROTO_UDP -> cachedUdpSockets
                else -> return  // Can't attribute other protocols
            }

            // Match flow to socket entry
            val matchedEntry = findMatchingSocket(flow, socketEntries)

            if (matchedEntry != null) {
                synchronized(flow) {
                    // Only assign if still unknown (monotonic)
                    if (flow.uid == -1) {
                        flow.uid = matchedEntry.uid
                        Log.d(TAG, "Attributed UID ${matchedEntry.uid} to flow ${flow.flowKey}")
                    }
                }
            }

        } catch (e: Exception) {
            // Skip this flow on error
        }
    }

    /**
     * Find matching socket entry for a flow.
     * Matching strategy:
     * 1. Match local port (most reliable)
     * 2. Match remote port
     * 3. Match remote address (if available)
     * 4. Check socket state (TCP only)
     */
    private fun findMatchingSocket(flow: FlowEntry, socketEntries: List<SocketEntry>): SocketEntry? {
        val flowKey = flow.flowKey

        for (entry in socketEntries) {
            // Match local port (source port from device perspective)
            if (entry.localPort != flowKey.sourcePort) {
                continue
            }

            // Match remote port (destination port)
            if (entry.remotePort != flowKey.destinationPort) {
                continue
            }

            // Match remote address if not 0.0.0.0 (connected socket)
            if (entry.remoteAddress != "0.0.0.0" &&
                entry.remoteAddress != flowKey.destinationAddress) {
                continue
            }

            // TCP: Check state (should be established or related)
            if (flow.protocol == PROTO_TCP) {
                if (entry.state != SocketEntry.TCP_ESTABLISHED &&
                    entry.state != SocketEntry.TCP_SYN_SENT &&
                    entry.state != SocketEntry.TCP_SYN_RECV &&
                    entry.state != SocketEntry.TCP_CLOSE_WAIT) {
                    continue
                }
            }

            // Match found
            return entry
        }

        return null
    }

    /**
     * Log error with rate limiting.
     */
    private fun logError(message: String, e: Exception?) {
        val now = System.currentTimeMillis()
        if (now - lastErrorLogTime > ERROR_LOG_INTERVAL_MS) {
            Log.w(TAG, "$message: ${e?.message}")
            lastErrorLogTime = now
        }
    }
}

