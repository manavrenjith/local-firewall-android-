package com.example.aegis.vpn.forwarding

import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.aegis.vpn.flow.FlowEntry
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * UdpForwarder - Phase 9: UDP Socket Forwarding
 *
 * Manages bidirectional UDP forwarding for a single flow.
 * One forwarder instance per UDP flow.
 *
 * Responsibilities:
 * - Send client → server payloads via protected DatagramSocket
 * - Receive server → client responses
 * - Reinject responses into TUN interface
 * - Track basic telemetry
 *
 * Non-responsibilities:
 * - No connection tracking (UDP is stateless)
 * - No retransmission
 * - No ordering guarantees
 * - No NAT table
 * - UI control
 */
class UdpForwarder(
    private val flow: FlowEntry,
    private val tunInterface: ParcelFileDescriptor,
    private val protectSocket: (DatagramSocket) -> Boolean
) {

    companion object {
        private const val TAG = "UdpForwarder"
        private const val BUFFER_SIZE = 8192
        private const val SO_TIMEOUT_MS = 5000  // 5 seconds
    }

    private var socket: DatagramSocket? = null
    private val isActive = AtomicBoolean(false)
    private val isClosed = AtomicBoolean(false)
    private val lastActivityTime = AtomicLong(System.currentTimeMillis())

    // Thread handle
    private var downlinkThread: Thread? = null

    // TUN output for reinjection
    private val tunOutput = FileOutputStream(tunInterface.fileDescriptor)

    /**
     * Initialize the forwarder.
     * Must be called before forwarding.
     *
     * @return true if successful, false otherwise
     */
    fun initialize(): Boolean {
        if (isActive.get()) {
            return true
        }

        try {
            // Create unbound datagram socket
            val sock = DatagramSocket()

            // Phase 9: Protect socket to prevent routing loop
            if (!protectSocket(sock)) {
                Log.e(TAG, "Failed to protect UDP socket for flow ${flow.flowKey}")
                sock.close()
                return false
            }

            sock.soTimeout = SO_TIMEOUT_MS

            socket = sock
            isActive.set(true)
            lastActivityTime.set(System.currentTimeMillis())

            Log.d(TAG, "UDP forwarder initialized for ${flow.flowKey}")

            // Phase 9: Start downlink listener
            startDownlinkListener()

            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize UDP forwarder", e)
            return false
        }
    }

    /**
     * Send uplink data (TUN → socket).
     * Phase 9: Stateless send, no acknowledgment expected.
     *
     * @param data Payload data
     * @return true if sent, false if failed
     */
    fun sendUplink(data: ByteArray): Boolean {
        val sock = socket
        if (sock == null || !isActive.get() || isClosed.get()) {
            return false
        }

        try {
            val destAddress = InetAddress.getByName(flow.flowKey.destinationAddress)
            val packet = DatagramPacket(
                data,
                data.size,
                destAddress,
                flow.flowKey.destinationPort
            )

            sock.send(packet)
            lastActivityTime.set(System.currentTimeMillis())

            // Phase 9: Record telemetry (best-effort)
            synchronized(flow) {
                flow.telemetry.recordUplinkForward(data.size)
            }

            return true

        } catch (e: IOException) {
            Log.w(TAG, "UDP uplink send failed: ${e.message}")

            // Phase 9: Record error (best-effort)
            synchronized(flow) {
                flow.telemetry.recordError()
            }

            return false
        }
    }

    /**
     * Start downlink listener thread (socket → TUN).
     * Phase 9: Receives responses and reinjects to TUN.
     */
    private fun startDownlinkListener() {
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
                    val packet = DatagramPacket(buffer, buffer.size)

                    try {
                        sock.receive(packet)

                        if (packet.length > 0) {
                            lastActivityTime.set(System.currentTimeMillis())

                            // Phase 9: Reconstruct and reinject UDP packet
                            val payload = packet.data.copyOf(packet.length)
                            sendUdpPacket(payload)
                        }

                    } catch (e: java.net.SocketTimeoutException) {
                        // Timeout is normal, continue listening
                        continue
                    }
                }
            } catch (e: IOException) {
                if (isActive.get()) {
                    Log.w(TAG, "UDP downlink listener error: ${e.message}")

                    // Phase 9: Record error (best-effort)
                    synchronized(flow) {
                        flow.telemetry.recordError()
                    }
                }
            } finally {
                // Don't close here, let cleanup handle it
            }
        }, "UdpDownlink-${flow.flowKey.hashCode()}").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Reconstruct and send UDP packet to TUN interface.
     * Phase 9: Downlink reinjection with address swapping.
     *
     * @param payload UDP payload data
     */
    private fun sendUdpPacket(payload: ByteArray) {
        try {
            // Phase 9: Build IPv4 + UDP packet (swapped addresses for downlink)
            val packet = buildUdpPacket(
                srcIp = flow.flowKey.destinationAddress,  // Server IP
                dstIp = flow.flowKey.sourceAddress,       // Client IP
                srcPort = flow.flowKey.destinationPort,   // Server port
                dstPort = flow.flowKey.sourcePort,        // Client port
                payload = payload
            )

            // Phase 9: Write to TUN (atomic operation)
            synchronized(tunOutput) {
                tunOutput.write(packet)
                tunOutput.flush()
            }

            // Phase 9: Record telemetry (best-effort)
            synchronized(flow) {
                flow.telemetry.recordDownlinkReinjection(payload.size)
            }

        } catch (e: Exception) {
            Log.w(TAG, "Error sending UDP packet: ${e.message}")

            // Phase 9: Record error (best-effort)
            synchronized(flow) {
                flow.telemetry.recordError()
            }
        }
    }

    /**
     * Build complete IPv4 + UDP packet.
     * Phase 9: Packet reconstruction for TUN reinjection.
     *
     * @return Complete packet bytes ready for TUN
     */
    private fun buildUdpPacket(
        srcIp: String,
        dstIp: String,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val ipHeaderSize = 20
        val udpHeaderSize = 8
        val totalSize = ipHeaderSize + udpHeaderSize + payload.size

        val buffer = ByteBuffer.allocate(totalSize)

        // IPv4 Header
        buffer.put(0x45.toByte())  // Version 4, IHL 5
        buffer.put(0x00.toByte())  // DSCP/ECN
        buffer.putShort(totalSize.toShort())  // Total length
        buffer.putShort(0x0000.toShort())  // Identification
        buffer.putShort(0x4000.toShort())  // Flags: Don't fragment
        buffer.put(0x40.toByte())  // TTL: 64
        buffer.put(0x11.toByte())  // Protocol: UDP (17)
        buffer.putShort(0x0000.toShort())  // Checksum (will calculate)

        // Source IP
        srcIp.split(".").forEach { buffer.put(it.toInt().toByte()) }
        // Destination IP
        dstIp.split(".").forEach { buffer.put(it.toInt().toByte()) }

        // Calculate and set IP checksum
        val ipChecksumPos = 10
        val ipChecksum = calculateChecksum(buffer.array(), 0, ipHeaderSize)
        buffer.putShort(ipChecksumPos, ipChecksum.toShort())

        // UDP Header
        buffer.putShort(srcPort.toShort())  // Source port
        buffer.putShort(dstPort.toShort())  // Destination port
        buffer.putShort((udpHeaderSize + payload.size).toShort())  // Length
        buffer.putShort(0x0000.toShort())  // Checksum (optional for IPv4)

        // Payload
        buffer.put(payload)

        // Calculate and set UDP checksum (optional but recommended)
        val udpChecksumPos = ipHeaderSize + 6
        val udpChecksum = calculateUdpChecksum(
            buffer.array(),
            ipHeaderSize,
            srcIp,
            dstIp,
            udpHeaderSize + payload.size
        )
        buffer.putShort(udpChecksumPos, udpChecksum.toShort())

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
     * Calculate UDP checksum with pseudo-header.
     */
    private fun calculateUdpChecksum(
        data: ByteArray,
        udpOffset: Int,
        srcIp: String,
        dstIp: String,
        udpLength: Int
    ): Int {
        var sum = 0L

        // Pseudo-header
        srcIp.split(".").forEach { sum += it.toInt() and 0xFF }
        dstIp.split(".").forEach { sum += it.toInt() and 0xFF }
        sum += 17  // Protocol: UDP
        sum += udpLength

        // UDP header + data
        var i = udpOffset
        while (i < udpOffset + udpLength - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }

        if (i < udpOffset + udpLength) {
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
     */
    fun close() {
        if (isClosed.getAndSet(true)) {
            return
        }

        isActive.set(false)

        try {
            socket?.close()
            socket = null
        } catch (e: Exception) {
            Log.w(TAG, "Error closing UDP socket: ${e.message}")
        }

        // Thread will exit naturally
        downlinkThread = null

        Log.d(TAG, "UDP forwarder closed for ${flow.flowKey}")
    }

    /**
     * Check if forwarder is active.
     */
    fun isActive(): Boolean = isActive.get() && !isClosed.get()

    /**
     * Get last activity time.
     * Phase 9: Used for idle timeout cleanup.
     *
     * @return Timestamp of last activity
     */
    fun getLastActivityTime(): Long = lastActivityTime.get()

    /**
     * Check if forwarder is idle.
     * Phase 9: For cleanup purposes.
     *
     * @param timeoutMs Idle timeout in milliseconds
     * @return true if idle
     */
    fun isIdle(timeoutMs: Long): Boolean {
        return System.currentTimeMillis() - lastActivityTime.get() > timeoutMs
    }
}

