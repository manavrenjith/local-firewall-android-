package com.example.aegis.vpn

import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.aegis.vpn.decision.DecisionEvaluator
import com.example.aegis.vpn.enforcement.EnforcementController
import com.example.aegis.vpn.enforcement.EnforcementState
import com.example.aegis.vpn.flow.FlowTable
import com.example.aegis.vpn.forwarding.ForwarderRegistry
import com.example.aegis.vpn.packet.PacketParser
import com.example.aegis.vpn.packet.TransportHeader
import com.example.aegis.vpn.telemetry.TelemetryLogger
import com.example.aegis.vpn.uid.UidResolver
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * TunReader - Phase 2: TUN Interface & Routing
 *             Phase 3: Packet Parsing (Observation Only)
 *             Phase 4: Flow Table & Metadata (Read-Only)
 *             Phase 5: UID Attribution (Best-Effort, Metadata Only)
 *             Phase 6: Decision Engine (Decision-Only, No Enforcement)
 *             Phase 7: Enforcement Controller (Gatekeeper, No Forwarding)
 *             Phase 8: TCP Socket Forwarding (Connectivity Restore)
 *             Phase 8.2: Forwarding Telemetry & Flow Metrics
 *             Phase 9: UDP Socket Forwarding
 *
 * Responsibilities:
 * - Read raw IP packets from VPN TUN interface
 * - Parse packets into structured metadata (Phase 3)
 * - Track flows in flow table (Phase 4)
 * - Attribute UIDs to flows (Phase 5)
 * - Evaluate flow decisions (Phase 6)
 * - Evaluate enforcement readiness (Phase 7)
 * - Forward TCP traffic for ALLOW_READY flows (Phase 8)
 * - Forward UDP traffic for ALLOW_READY flows (Phase 9)
 * - Optional telemetry logging (Phase 8.2, debug only)
 * - Thread lifecycle management
 * - Graceful error handling
 *
 * Non-responsibilities (Phase 9):
 * - Packet blocking
 * - UI control
 * - Telemetry enforcement
 */
class TunReader(
    private val vpnInterface: ParcelFileDescriptor,
    private val flowTable: FlowTable,
    private val uidResolver: UidResolver,
    private val decisionEvaluator: DecisionEvaluator,
    private val enforcementController: EnforcementController,
    private val forwarderRegistry: ForwarderRegistry,
    private val onError: () -> Unit
) {

    companion object {
        private const val TAG = "TunReader"
        private const val PACKET_BUFFER_SIZE = 32 * 1024 // 32KB buffer

        // UID resolution interval
        private const val UID_RESOLUTION_INTERVAL_MS = 10000L  // 10 seconds

        // Decision evaluation interval
        private const val DECISION_EVALUATION_INTERVAL_MS = 15000L  // 15 seconds

        // Enforcement evaluation interval
        private const val ENFORCEMENT_EVALUATION_INTERVAL_MS = 20000L  // 20 seconds

        // Forwarder cleanup interval
        private const val FORWARDER_CLEANUP_INTERVAL_MS = 30000L  // 30 seconds

        // Phase 8.2: Telemetry logging interval (debug only)
        private const val TELEMETRY_LOGGING_INTERVAL_MS = 30000L  // 30 seconds

        // Protocol numbers
        private const val PROTO_TCP = 6
        private const val PROTO_UDP = 17  // Phase 9: UDP forwarding
    }

    private var readThread: Thread? = null
    private val isRunning = AtomicBoolean(false)

    // Statistics (observation only)
    private val totalPacketsRead = AtomicLong(0)
    private val totalBytesRead = AtomicLong(0)

    // Phase 3: Parsing statistics
    private val totalPacketsParsed = AtomicLong(0)
    private val totalParseFailures = AtomicLong(0)

    // Phase 5: UID resolution timing
    private var lastUidResolutionTime = 0L

    // Phase 6: Decision evaluation timing
    private var lastDecisionEvaluationTime = 0L

    // Phase 7: Enforcement evaluation timing
    private var lastEnforcementEvaluationTime = 0L

    // Phase 8: Forwarder cleanup timing
    private var lastForwarderCleanupTime = 0L

    // Phase 8.2: Telemetry logging timing (debug only)
    private var lastTelemetryLoggingTime = 0L

    // Phase 8: Forwarding statistics
    private val totalPacketsForwarded = AtomicLong(0)
    private val totalPacketsDropped = AtomicLong(0)

    /**
     * Starts the packet read loop.
     * Safe to call only once - subsequent calls are ignored.
     */
    fun start() {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "TunReader already running, ignoring start request")
            return
        }

        Log.i(TAG, "Starting TUN read loop")

        readThread = Thread({
            runReadLoop()
        }, "AegisTunReader").apply {
            isDaemon = false
            start()
        }
    }

    /**
     * Stops the packet read loop.
     * Blocks until thread terminates.
     * Safe to call multiple times.
     */
    fun stop() {
        if (!isRunning.getAndSet(false)) {
            Log.d(TAG, "TunReader not running, ignoring stop request")
            return
        }

        Log.i(TAG, "Stopping TUN read loop")

        // Interrupt the thread to unblock read()
        readThread?.interrupt()

        // Wait for thread to finish
        try {
            readThread?.join(5000) // 5 second timeout
            if (readThread?.isAlive == true) {
                Log.w(TAG, "Read thread did not terminate within timeout")
            }
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while waiting for read thread", e)
            Thread.currentThread().interrupt()
        }

        readThread = null

        val flowStats = flowTable.getStatistics()
        Log.i(TAG, "TUN read loop stopped. Stats: packets=$totalPacketsRead, bytes=$totalBytesRead, " +
                "parsed=$totalPacketsParsed, parseFailures=$totalParseFailures, flows=${flowStats.totalFlows}")
    }

    /**
     * Main read loop - runs on background thread.
     * Reads packets until interrupted or error occurs.
     */
    private fun runReadLoop() {
        Log.d(TAG, "Read loop started on thread ${Thread.currentThread().name}")

        val inputStream = FileInputStream(vpnInterface.fileDescriptor)
        val buffer = ByteArray(PACKET_BUFFER_SIZE)

        try {
            while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                // Blocking read from TUN interface
                val length = inputStream.read(buffer)

                if (length > 0) {
                    // Packet successfully read
                    handlePacket(buffer, length)
                } else if (length < 0) {
                    // End of stream - VPN interface closed
                    Log.w(TAG, "TUN interface EOF reached")
                    break
                }
                // length == 0 should not happen with blocking read, but continue if it does
            }
        } catch (e: IOException) {
            if (isRunning.get()) {
                // Unexpected IO error while still running
                Log.e(TAG, "IO error reading from TUN interface", e)
                triggerError()
            } else {
                // Expected error during shutdown
                Log.d(TAG, "IO exception during shutdown (expected)")
            }
        } catch (e: Exception) {
            // Unexpected error
            Log.e(TAG, "Unexpected error in read loop", e)
            triggerError()
        } finally {
            Log.d(TAG, "Read loop exiting")
        }
    }

    /**
     * Handles a packet read from TUN interface.
     * Phase 2: Observation only - no parsing, no forwarding.
     * Phase 3: Parse packet into structured metadata (observation only).
     * Phase 4: Track flow in flow table.
     * Phase 5: Periodically resolve UIDs.
     * Phase 6: Periodically evaluate decisions.
     * Phase 7: Periodically evaluate enforcement.
     * Phase 8: Forward TCP packets for ALLOW_READY flows.
     *
     * @param buffer Byte array containing packet data
     * @param length Number of valid bytes in buffer
     */
    private fun handlePacket(buffer: ByteArray, length: Int) {
        // Update statistics
        totalPacketsRead.incrementAndGet()
        totalBytesRead.addAndGet(length.toLong())

        // Phase 3+: Parse packet if enabled
        if (VpnConstants.ENABLE_PACKET_PARSING) {
            val parsedPacket = PacketParser.parse(buffer, length)

            if (parsedPacket != null) {
                totalPacketsParsed.incrementAndGet()

                // Phase 4: Track flow in flow table
                flowTable.processPacket(parsedPacket, length)

                val now = System.currentTimeMillis()

                // Phase 5: Periodically resolve UIDs (time-based, not per-packet)
                if (now - lastUidResolutionTime > UID_RESOLUTION_INTERVAL_MS) {
                    lastUidResolutionTime = now
                    uidResolver.resolveUids()
                }

                // Phase 6: Periodically evaluate decisions (time-based, not per-packet)
                if (now - lastDecisionEvaluationTime > DECISION_EVALUATION_INTERVAL_MS) {
                    lastDecisionEvaluationTime = now
                    decisionEvaluator.evaluateFlows()
                }

                // Phase 7: Periodically evaluate enforcement (time-based, not per-packet)
                if (now - lastEnforcementEvaluationTime > ENFORCEMENT_EVALUATION_INTERVAL_MS) {
                    lastEnforcementEvaluationTime = now
                    enforcementController.evaluateEnforcement()
                }

                // Phase 8: Periodically cleanup forwarders (time-based, not per-packet)
                if (now - lastForwarderCleanupTime > FORWARDER_CLEANUP_INTERVAL_MS) {
                    lastForwarderCleanupTime = now
                    forwarderRegistry.cleanup()
                }

                // Phase 8.2: Periodically log telemetry (debug only, time-based)
                if (now - lastTelemetryLoggingTime > TELEMETRY_LOGGING_INTERVAL_MS) {
                    lastTelemetryLoggingTime = now
                    TelemetryLogger.logAggregateTelemetry(flowTable, forwarderRegistry)
                }

                // Phase 8: Attempt TCP forwarding for eligible flows
                val forwarded = attemptForwarding(parsedPacket, buffer, length)
                if (forwarded) {
                    totalPacketsForwarded.incrementAndGet()
                } else {
                    totalPacketsDropped.incrementAndGet()
                }

                // Log parsed packet info (rate-limited)
                if (VpnConstants.LOG_PARSED_PACKETS && totalPacketsParsed.get() % 1000 == 1L) {
                    logParsedPacket(parsedPacket)
                }
            } else {
                totalParseFailures.incrementAndGet()

                // Log parse failures occasionally
                if (totalParseFailures.get() % 100 == 1L) {
                    Log.d(TAG, "Packet parsing failed (total failures: ${totalParseFailures.get()})")
                }
            }
        } else {
            // Phase 2: Observation only (no parsing)
            if (totalPacketsRead.get() % 1000 == 1L) {
                Log.d(TAG, "Packet read: length=$length bytes (total packets: ${totalPacketsRead.get()})")
            }
        }

        // Future phases will:
        // - UDP forwarding (Phase 9+)
        // - Production hardening (Phase 10+)

        // Phase 8: TCP forwarding active for ALLOW_READY flows
    }

    /**
     * Attempt to forward a packet via TCP forwarder.
     * Phase 8: Only forwards TCP packets for ALLOW_READY flows.
     *
     * @param parsedPacket Parsed packet metadata
     * @param buffer Raw packet buffer
     * @param length Packet length
     * @return true if forwarded, false if dropped
     */
    /**
     * Attempt to forward a packet via forwarder.
     * Phase 8: TCP forwarding for ALLOW_READY flows.
     * Phase 9: UDP forwarding for ALLOW_READY flows.
     *
     * @param parsedPacket Parsed packet metadata
     * @param buffer Raw packet buffer
     * @param length Packet length
     * @return true if forwarded, false if dropped
     */
    private fun attemptForwarding(
        parsedPacket: com.example.aegis.vpn.packet.ParsedPacket,
        buffer: ByteArray,
        length: Int
    ): Boolean {
        try {
            val protocol = parsedPacket.ipHeader.protocol

            // Phase 8: TCP forwarding
            if (protocol == PROTO_TCP) {
                return attemptTcpForwarding(parsedPacket, buffer, length)
            }

            // Phase 9: UDP forwarding
            if (protocol == PROTO_UDP) {
                return attemptUdpForwarding(parsedPacket, buffer, length)
            }

            // Other protocols not forwarded
            return false

        } catch (e: Exception) {
            Log.w(TAG, "Error attempting forwarding: ${e.message}")
            return false
        }
    }

    /**
     * Attempt TCP forwarding.
     * Phase 8: TCP socket forwarding.
     */
    private fun attemptTcpForwarding(
        parsedPacket: com.example.aegis.vpn.packet.ParsedPacket,
        buffer: ByteArray,
        length: Int
    ): Boolean {
        try {
            // Get flow entry
            val flowEntry = flowTable.getFlow(parsedPacket.flowKey) ?: return false

            // Only forward ALLOW_READY flows
            synchronized(flowEntry) {
                if (flowEntry.enforcementState != EnforcementState.ALLOW_READY) {
                    return false
                }
            }

            // Get or create TCP forwarder
            val forwarder = forwarderRegistry.getOrCreateTcpForwarder(flowEntry) ?: return false

            // Extract TCP payload and sequence number
            val tcpHeader = parsedPacket.transportHeader as? TransportHeader.Tcp ?: return false
            val payload = extractTcpPayload(buffer, length, parsedPacket.ipHeader.headerLength,
                                           tcpHeader.headerLength)

            if (payload.isEmpty()) {
                // No payload to forward (SYN, ACK, etc.)
                // Phase 8.1: Still update sequences for ACK generation
                forwarder.updateSequences(tcpHeader.sequenceNumber, 0)
                return true
            }

            // Phase 8.1: Forward uplink data with sequence number
            return forwarder.forwardUplink(payload, tcpHeader.sequenceNumber)

        } catch (e: Exception) {
            Log.w(TAG, "Error attempting TCP forwarding: ${e.message}")
            return false
        }
    }

    /**
     * Attempt UDP forwarding.
     * Phase 9: UDP socket forwarding.
     */
    private fun attemptUdpForwarding(
        parsedPacket: com.example.aegis.vpn.packet.ParsedPacket,
        buffer: ByteArray,
        length: Int
    ): Boolean {
        try {
            // Get flow entry
            val flowEntry = flowTable.getFlow(parsedPacket.flowKey) ?: return false

            // Only forward ALLOW_READY flows
            synchronized(flowEntry) {
                if (flowEntry.enforcementState != EnforcementState.ALLOW_READY) {
                    return false
                }
            }

            // Get or create UDP forwarder
            val forwarder = forwarderRegistry.getOrCreateUdpForwarder(flowEntry) ?: return false

            // Extract UDP payload
            val udpHeader = parsedPacket.transportHeader as? TransportHeader.Udp ?: return false
            val payload = extractUdpPayload(buffer, length, parsedPacket.ipHeader.headerLength)

            if (payload.isEmpty()) {
                // Empty UDP packet, skip
                return true
            }

            // Phase 9: Send uplink UDP packet
            return forwarder.sendUplink(payload)

        } catch (e: Exception) {
            Log.w(TAG, "Error attempting UDP forwarding: ${e.message}")
            return false
        }
    }

    /**
     * Extract TCP payload from raw packet.
     *
     * @param buffer Raw packet buffer
     * @param length Total packet length
     * @param ipHeaderLength IP header length in bytes
     * @param tcpHeaderLength TCP header length in bytes
     * @return TCP payload bytes
     */
    private fun extractTcpPayload(
        buffer: ByteArray,
        length: Int,
        ipHeaderLength: Int,
        tcpHeaderLength: Int
    ): ByteArray {
        try {
            val headerSize = ipHeaderLength + tcpHeaderLength
            if (headerSize >= length) {
                return ByteArray(0)
            }

            val payloadSize = length - headerSize
            return buffer.copyOfRange(headerSize, headerSize + payloadSize)

        } catch (e: Exception) {
            Log.w(TAG, "Error extracting TCP payload: ${e.message}")
            return ByteArray(0)
        }
    }

    /**
     * Extract UDP payload from raw packet.
     * Phase 9: UDP payload extraction.
     *
     * @param buffer Raw packet buffer
     * @param length Total packet length
     * @param ipHeaderLength IP header length in bytes
     * @return UDP payload bytes
     */
    private fun extractUdpPayload(
        buffer: ByteArray,
        length: Int,
        ipHeaderLength: Int
    ): ByteArray {
        try {
            val udpHeaderSize = 8  // UDP header is always 8 bytes
            val headerSize = ipHeaderLength + udpHeaderSize

            if (headerSize >= length) {
                return ByteArray(0)
            }

            val payloadSize = length - headerSize
            return buffer.copyOfRange(headerSize, headerSize + payloadSize)

        } catch (e: Exception) {
            Log.w(TAG, "Error extracting UDP payload: ${e.message}")
            return ByteArray(0)
        }
    }

    /**
     * Log parsed packet information.
     * Phase 3+: Observation only.
     */
    private fun logParsedPacket(packet: com.example.aegis.vpn.packet.ParsedPacket) {
        val transportInfo = when (val header = packet.transportHeader) {
            is com.example.aegis.vpn.packet.TransportHeader.Tcp -> {
                val flags = buildString {
                    if (header.flags.syn) append("SYN ")
                    if (header.flags.ack) append("ACK ")
                    if (header.flags.fin) append("FIN ")
                    if (header.flags.rst) append("RST ")
                    if (header.flags.psh) append("PSH ")
                }.trim()
                "TCP ${header.sourcePort}→${header.destinationPort} [$flags] seq=${header.sequenceNumber}"
            }
            is com.example.aegis.vpn.packet.TransportHeader.Udp -> {
                "UDP ${header.sourcePort}→${header.destinationPort} len=${header.length}"
            }
            is com.example.aegis.vpn.packet.TransportHeader.Icmp -> {
                "ICMP type=${header.type} code=${header.code}"
            }
            is com.example.aegis.vpn.packet.TransportHeader.Unknown -> {
                "Unknown protocol=${packet.ipHeader.protocol}"
            }
        }

        Log.d(TAG, "Parsed: ${packet.ipHeader.sourceAddress} → ${packet.ipHeader.destinationAddress} | " +
                "$transportInfo | total=${totalPacketsParsed.get()}")
    }

    /**
     * Triggers error callback on service.
     * Called when unrecoverable read error occurs.
     */
    private fun triggerError() {
        if (isRunning.getAndSet(false)) {
            Log.e(TAG, "Triggering VPN teardown due to read error")
            onError()
        }
    }

    /**
     * Gets current statistics.
     *
     * @return Pair of (packets, bytes) read so far
     */
    fun getStats(): Pair<Long, Long> {
        return Pair(totalPacketsRead.get(), totalBytesRead.get())
    }
}

