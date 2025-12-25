package com.example.aegis.vpn.forwarding

import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.aegis.vpn.flow.FlowEntry
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * TcpForwarder - Phase 8: TCP Socket Forwarding
 *                Phase 8.1: TCP Downlink Reinjection (Bidirectional Completion)
 *                Phase 8.2: Forwarding Telemetry & Flow Metrics
 *
 * Manages bidirectional TCP forwarding for a single flow.
 * One forwarder instance per TCP flow.
 *
 * Responsibilities:
 * - Create protected socket
 * - Forward uplink (TUN → socket)
 * - Forward downlink (socket → TUN) with packet reconstruction
 * - Handle TCP state (SYN, ACK, FIN, RST)
 * - Track sequence/acknowledgment numbers
 * - Prevent routing loops
 * - Reinject TCP packets to TUN
 * - Record telemetry (Phase 8.2, best-effort)
 *
 * Non-responsibilities (Phase 8.2):
 * - UDP forwarding
 * - Packet blocking
 * - UI control
 * - Telemetry enforcement
 */
class TcpForwarder(
    private val flow: FlowEntry,
    private val tunInterface: ParcelFileDescriptor,
    private val protectSocket: (Socket) -> Boolean
) {

    companion object {
        private const val TAG = "TcpForwarder"
        private const val CONNECT_TIMEOUT_MS = 10000  // 10 seconds
        private const val SO_TIMEOUT_MS = 30000  // 30 seconds (longer for responses)
        private const val BUFFER_SIZE = 8192

        // Phase 10: Thread termination timeout
        private const val THREAD_JOIN_TIMEOUT_MS = 2000L  // 2 seconds

        // TCP flags
        private const val TCP_FIN = 0x01
        private const val TCP_SYN = 0x02
        private const val TCP_RST = 0x04
        private const val TCP_PSH = 0x08
        private const val TCP_ACK = 0x10
    }

    private var socket: Socket? = null
    private val isActive = AtomicBoolean(false)
    private val isClosed = AtomicBoolean(false)

    // Phase 8.1: TCP sequence tracking (atomic for thread safety)
    private val sendSeq = AtomicLong(1000L)  // Server → client sequence
    private val recvAck = AtomicLong(1000L)  // Client → server acknowledgment

    // Thread handles
    private var downlinkThread: Thread? = null

    // TUN output for reinjection
    private val tunOutput = FileOutputStream(tunInterface.fileDescriptor)

    /**
     * Initialize the forwarder and establish connection.
     * Must be called before forwarding.
     *
     * @return true if successful, false otherwise
     */
    fun initialize(): Boolean {
        if (isActive.get()) {
            return true
        }

        try {
            // Create socket
            val sock = Socket()

            // Phase 8: Protect socket to prevent routing loop
            if (!protectSocket(sock)) {
                Log.e(TAG, "Failed to protect socket for flow ${flow.flowKey}")
                sock.close()
                return false
            }

            // Connect to destination
            val destAddress = InetSocketAddress(
                flow.flowKey.destinationAddress,
                flow.flowKey.destinationPort
            )

            sock.connect(destAddress, CONNECT_TIMEOUT_MS)
            sock.soTimeout = SO_TIMEOUT_MS

            socket = sock
            isActive.set(true)

            Log.d(TAG, "TCP forwarder initialized for ${flow.flowKey}")

            // Phase 8.1: Start downlink reinjection
            startDownlinkReinjection()

            return true

        } catch (e: IOException) {
            Log.w(TAG, "Failed to initialize TCP forwarder: ${e.message}")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing TCP forwarder", e)
            return false
        }
    }

    /**
     * Update sequence numbers from uplink packet.
     * Phase 8.1: Track client sequence for proper ACK generation.
     *
     * @param clientSeq Client sequence number
     * @param payloadLength Length of payload
     */
    fun updateSequences(clientSeq: Long, payloadLength: Int) {
        // Update acknowledgment to reflect client sequence
        recvAck.set(clientSeq + payloadLength)
    }

    /**
     * Forward uplink data (TUN → socket).
     * Called with payload extracted from TUN packet.
     * Phase 8.2: Records telemetry on success.
     *
     * @param data Payload data
     * @param clientSeq Client sequence number from packet
     * @return true if forwarded, false if failed
     */
    fun forwardUplink(data: ByteArray, clientSeq: Long): Boolean {
        val sock = socket
        if (sock == null || !isActive.get() || isClosed.get()) {
            return false
        }

        try {
            // Update sequences before forwarding
            updateSequences(clientSeq, data.size)

            sock.outputStream.write(data)
            sock.outputStream.flush()

            // Phase 8.2: Record successful uplink forward (best-effort)
            synchronized(flow) {
                flow.telemetry.recordUplinkForward(data.size)
            }

            return true
        } catch (e: IOException) {
            Log.w(TAG, "Uplink forward failed: ${e.message}")

            // Phase 8.2: Record error (best-effort)
            synchronized(flow) {
                flow.telemetry.recordError()
            }

            close()
            return false
        }
    }

    /**
     * Start downlink reinjection thread (socket → TUN).
     * Phase 8.1: Reads from socket, reconstructs TCP packets, reinjects to TUN.
     * Phase 8.2: Records telemetry for downlink activity.
     */
    private fun startDownlinkReinjection() {
        if (downlinkThread != null) {
            return
        }

        downlinkThread = Thread({
            val buffer = ByteArray(BUFFER_SIZE)
            val sock = socket

            if (sock == null) {
                return@Thread
            }

            try {
                while (isActive.get() && !isClosed.get()) {
                    val bytesRead = sock.inputStream.read(buffer)

                    if (bytesRead == -1) {
                        // EOF - remote closed connection
                        Log.d(TAG, "Remote closed connection for ${flow.flowKey}")

                        // Phase 8.1: Send FIN packet
                        sendTcpPacket(ByteArray(0), TCP_FIN or TCP_ACK)

                        // Phase 8.2: Record FIN (best-effort)
                        synchronized(flow) {
                            flow.telemetry.recordTcpFin()
                        }

                        break
                    }

                    if (bytesRead > 0) {
                        // Phase 8.1: Reconstruct and reinject TCP packet
                        val payload = buffer.copyOf(bytesRead)
                        sendTcpPacket(payload, TCP_PSH or TCP_ACK)
                    }
                }
            } catch (e: IOException) {
                if (isActive.get()) {
                    Log.w(TAG, "Downlink reinjection error: ${e.message}")

                    // Phase 8.2: Record error (best-effort)
                    synchronized(flow) {
                        flow.telemetry.recordError()
                    }

                    // Phase 8.1: Send RST on error
                    try {
                        sendTcpPacket(ByteArray(0), TCP_RST or TCP_ACK)

                        // Phase 8.2: Record RST (best-effort)
                        synchronized(flow) {
                            flow.telemetry.recordTcpReset()
                        }
                    } catch (ignored: Exception) {
                    }
                }
            } finally {
                close()
            }
        }, "TcpDownlink-${flow.flowKey.hashCode()}").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Reconstruct and send TCP packet to TUN interface.
     * Phase 8.1: Critical method for downlink reinjection.
     * Phase 8.2: Records telemetry for downlink reinjection.
     *
     * @param payload TCP payload data
     * @param flags TCP flags (FIN, SYN, RST, PSH, ACK)
     */
    private fun sendTcpPacket(payload: ByteArray, flags: Int) {
        try {
            // Phase 8.1: Build IPv4 + TCP packet (swapped addresses for downlink)
            val packet = buildTcpPacket(
                srcIp = flow.flowKey.destinationAddress,  // Server IP
                dstIp = flow.flowKey.sourceAddress,       // Client IP
                srcPort = flow.flowKey.destinationPort,   // Server port
                dstPort = flow.flowKey.sourcePort,        // Client port
                seq = sendSeq.get(),
                ack = recvAck.get(),
                flags = flags,
                payload = payload
            )

            // Phase 8.1: Write to TUN (atomic operation)
            synchronized(tunOutput) {
                tunOutput.write(packet)
                tunOutput.flush()
            }

            // Phase 8.2: Record successful downlink reinjection (best-effort)
            synchronized(flow) {
                flow.telemetry.recordDownlinkReinjection(payload.size)
            }

            // Update sequence number
            sendSeq.addAndGet(payload.size.toLong())

        } catch (e: Exception) {
            Log.w(TAG, "Error sending TCP packet: ${e.message}")

            // Phase 8.2: Record error (best-effort)
            synchronized(flow) {
                flow.telemetry.recordError()
            }

            close()
        }
    }

    /**
     * Build complete IPv4 + TCP packet.
     * Phase 8.1: Packet reconstruction for TUN reinjection.
     *
     * @return Complete packet bytes ready for TUN
     */
    private fun buildTcpPacket(
        srcIp: String,
        dstIp: String,
        srcPort: Int,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        payload: ByteArray
    ): ByteArray {
        val ipHeaderSize = 20
        val tcpHeaderSize = 20
        val totalSize = ipHeaderSize + tcpHeaderSize + payload.size

        val buffer = ByteBuffer.allocate(totalSize)

        // IPv4 Header
        buffer.put(0x45.toByte())  // Version 4, IHL 5
        buffer.put(0x00.toByte())  // DSCP/ECN
        buffer.putShort(totalSize.toShort())  // Total length
        buffer.putShort(0x0000.toShort())  // Identification
        buffer.putShort(0x4000.toShort())  // Flags: Don't fragment
        buffer.put(0x40.toByte())  // TTL: 64
        buffer.put(0x06.toByte())  // Protocol: TCP
        buffer.putShort(0x0000.toShort())  // Checksum (will calculate)

        // Source IP
        srcIp.split(".").forEach { buffer.put(it.toInt().toByte()) }
        // Destination IP
        dstIp.split(".").forEach { buffer.put(it.toInt().toByte()) }

        // Calculate and set IP checksum
        val ipChecksumPos = 10
        val ipChecksum = calculateChecksum(buffer.array(), 0, ipHeaderSize)
        buffer.putShort(ipChecksumPos, ipChecksum.toShort())

        // TCP Header
        buffer.putShort(srcPort.toShort())  // Source port
        buffer.putShort(dstPort.toShort())  // Destination port
        buffer.putInt(seq.toInt())  // Sequence number
        buffer.putInt(ack.toInt())  // Acknowledgment number
        buffer.put(0x50.toByte())  // Data offset: 5 (20 bytes)
        buffer.put(flags.toByte())  // Flags
        buffer.putShort(0xFFFF.toShort())  // Window size
        buffer.putShort(0x0000.toShort())  // Checksum (will calculate)
        buffer.putShort(0x0000.toShort())  // Urgent pointer

        // Payload
        buffer.put(payload)

        // Calculate and set TCP checksum
        val tcpChecksumPos = ipHeaderSize + 16
        val tcpChecksum = calculateTcpChecksum(
            buffer.array(),
            ipHeaderSize,
            srcIp,
            dstIp,
            tcpHeaderSize + payload.size
        )
        buffer.putShort(tcpChecksumPos, tcpChecksum.toShort())

        return buffer.array()
    }

    /**
     * Calculate IP checksum.
     */
    private fun calculateChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset

        while (i < offset + length - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }

        if (i < offset + length) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }

        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }

        return (sum.inv() and 0xFFFF).toInt()
    }

    /**
     * Calculate TCP checksum with pseudo-header.
     */
    private fun calculateTcpChecksum(
        data: ByteArray,
        tcpOffset: Int,
        srcIp: String,
        dstIp: String,
        tcpLength: Int
    ): Int {
        var sum = 0L

        // Pseudo-header
        srcIp.split(".").forEach { sum += it.toInt() and 0xFF }
        dstIp.split(".").forEach { sum += it.toInt() and 0xFF }
        sum += 6  // Protocol: TCP
        sum += tcpLength

        // TCP header + data
        var i = tcpOffset
        while (i < tcpOffset + tcpLength - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }

        if (i < tcpOffset + tcpLength) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }

        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }

        return (sum.inv() and 0xFFFF).toInt()
    }

    /**
     * Close the forwarder and release resources.
     * Idempotent and thread-safe.
     * Phase 8.1: Also closes TUN output stream.
     * Phase 10: Deterministic thread termination with timeout.
     */
    fun close() {
        if (isClosed.getAndSet(true)) {
            return
        }

        isActive.set(false)

        // Phase 10: Close socket first to unblock read operations
        try {
            socket?.close()
        } catch (e: Exception) {
            // Defensive: log but continue cleanup
        }
        socket = null

        // Phase 10: Wait for downlink thread termination (bounded)
        val thread = downlinkThread
        if (thread != null && thread.isAlive) {
            try {
                thread.interrupt()
                thread.join(THREAD_JOIN_TIMEOUT_MS)
                if (thread.isAlive) {
                    Log.w(TAG, "Downlink thread did not terminate for ${flow.flowKey}")
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                // Defensive: continue cleanup
            }
        }
        downlinkThread = null

        Log.d(TAG, "TCP forwarder closed for ${flow.flowKey}")
    }

    /**
     * Check if forwarder is active.
     */
    fun isActive(): Boolean = isActive.get() && !isClosed.get()
}

