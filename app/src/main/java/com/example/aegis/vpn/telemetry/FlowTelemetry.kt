package com.example.aegis.vpn.telemetry

/**
 * FlowTelemetry - Phase 8.2: Forwarding Telemetry & Flow Metrics
 *
 * Per-flow telemetry data for observing forwarding behavior.
 * Purely observational - does not affect forwarding logic.
 *
 * Mutable by design - updated by forwarding components.
 * Thread-safe when accessed within synchronized(flow) blocks.
 */
data class FlowTelemetry(
    // Uplink counters (client → server)
    var uplinkPackets: Long = 0,
    var uplinkBytes: Long = 0,

    // Downlink counters (server → client)
    var downlinkPackets: Long = 0,
    var downlinkBytes: Long = 0,

    // Timing
    var firstForwardedAt: Long = 0,
    var lastForwardedAt: Long = 0,

    // Error tracking
    var forwardingErrors: Long = 0,

    // TCP-specific
    var tcpResetsSent: Long = 0,
    var tcpFinsSent: Long = 0,

    // Last activity direction
    var lastActivityDirection: Direction = Direction.NONE
) {
    /**
     * Record successful uplink forward (client → server).
     * Phase 8.2: Best-effort telemetry update.
     *
     * @param bytes Bytes forwarded
     */
    fun recordUplinkForward(bytes: Int) {
        try {
            uplinkPackets++
            uplinkBytes += bytes

            val now = System.currentTimeMillis()
            if (firstForwardedAt == 0L) {
                firstForwardedAt = now
            }
            lastForwardedAt = now
            lastActivityDirection = Direction.UPLINK
        } catch (e: Exception) {
            // Silently ignore telemetry errors
        }
    }

    /**
     * Record successful downlink reinjection (server → client).
     * Phase 8.2: Best-effort telemetry update.
     *
     * @param bytes Bytes reinjected
     */
    fun recordDownlinkReinjection(bytes: Int) {
        try {
            downlinkPackets++
            downlinkBytes += bytes

            val now = System.currentTimeMillis()
            if (firstForwardedAt == 0L) {
                firstForwardedAt = now
            }
            lastForwardedAt = now
            lastActivityDirection = Direction.DOWNLINK
        } catch (e: Exception) {
            // Silently ignore telemetry errors
        }
    }

    /**
     * Record forwarding error.
     * Phase 8.2: Best-effort telemetry update.
     */
    fun recordError() {
        try {
            forwardingErrors++
        } catch (e: Exception) {
            // Silently ignore telemetry errors
        }
    }

    /**
     * Record TCP RST sent.
     * Phase 8.2: Best-effort telemetry update.
     */
    fun recordTcpReset() {
        try {
            tcpResetsSent++
        } catch (e: Exception) {
            // Silently ignore telemetry errors
        }
    }

    /**
     * Record TCP FIN sent.
     * Phase 8.2: Best-effort telemetry update.
     */
    fun recordTcpFin() {
        try {
            tcpFinsSent++
        } catch (e: Exception) {
            // Silently ignore telemetry errors
        }
    }

    /**
     * Get total forwarding activity (packets).
     */
    fun getTotalPackets(): Long {
        return uplinkPackets + downlinkPackets
    }

    /**
     * Get total forwarding activity (bytes).
     */
    fun getTotalBytes(): Long {
        return uplinkBytes + downlinkBytes
    }

    /**
     * Get forwarding age in milliseconds.
     * @return Age since first forward, or 0 if never forwarded
     */
    fun getForwardingAge(): Long {
        return if (firstForwardedAt > 0) {
            System.currentTimeMillis() - firstForwardedAt
        } else {
            0
        }
    }

    /**
     * Get idle time since last forward in milliseconds.
     * @return Idle time, or 0 if never forwarded
     */
    fun getIdleTime(): Long {
        return if (lastForwardedAt > 0) {
            System.currentTimeMillis() - lastForwardedAt
        } else {
            0
        }
    }
}

/**
 * Direction enum for tracking last activity.
 * Phase 8.2: Observation only.
 */
enum class Direction {
    NONE,       // No forwarding yet
    UPLINK,     // Last activity was client → server
    DOWNLINK    // Last activity was server → client
}

