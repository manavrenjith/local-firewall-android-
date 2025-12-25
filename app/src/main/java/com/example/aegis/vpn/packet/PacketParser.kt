package com.example.aegis.vpn.packet

import android.util.Log

/**
 * PacketParser - Phase 3: Packet Parsing (Observation Only)
 *
 * Responsibilities:
 * - Parse raw IP packets into structured metadata
 * - IPv4 header extraction
 * - TCP/UDP/ICMP header extraction
 * - Flow key construction
 * - Defensive bounds checking
 *
 * Non-responsibilities (Phase 3):
 * - No packet modification
 * - No packet forwarding
 * - No UID attribution
 * - No rule enforcement
 * - No checksum validation
 * - No flow tracking
 */
object PacketParser {

    private const val TAG = "PacketParser"

    // IP Protocol numbers
    private const val PROTO_TCP = 6
    private const val PROTO_UDP = 17
    private const val PROTO_ICMP = 1

    // Minimum header sizes
    private const val MIN_IPV4_HEADER = 20
    private const val MIN_TCP_HEADER = 20
    private const val MIN_UDP_HEADER = 8
    private const val MIN_ICMP_HEADER = 8

    // IP header field offsets
    private const val IP_VERSION_IHL = 0
    private const val IP_TOTAL_LENGTH = 2
    private const val IP_PROTOCOL = 9
    private const val IP_SRC_ADDR = 12
    private const val IP_DST_ADDR = 16

    // TCP header field offsets
    private const val TCP_SRC_PORT = 0
    private const val TCP_DST_PORT = 2
    private const val TCP_SEQ_NUM = 4
    private const val TCP_ACK_NUM = 8
    private const val TCP_DATA_OFFSET_FLAGS = 12
    private const val TCP_FLAGS = 13

    // UDP header field offsets
    private const val UDP_SRC_PORT = 0
    private const val UDP_DST_PORT = 2
    private const val UDP_LENGTH = 4

    // ICMP header field offsets
    private const val ICMP_TYPE = 0
    private const val ICMP_CODE = 1

    /**
     * Parse raw IP packet into structured metadata.
     * Returns null if packet is malformed or cannot be parsed.
     *
     * @param buffer Byte array containing packet data
     * @param length Number of valid bytes in buffer
     * @return ParsedPacket or null if parsing fails
     */
    fun parse(buffer: ByteArray, length: Int): ParsedPacket? {
        try {
            // Validate minimum packet size
            if (length < MIN_IPV4_HEADER) {
                return null
            }

            // Parse IPv4 header
            val ipHeader = parseIpv4Header(buffer, length) ?: return null

            // Calculate transport header offset
            val transportOffset = ipHeader.headerLength

            // Validate we have enough data for transport header
            if (length < transportOffset) {
                return null
            }

            // Parse transport layer based on protocol
            val transportHeader = when (ipHeader.protocol) {
                PROTO_TCP -> parseTcpHeader(buffer, transportOffset, length)
                PROTO_UDP -> parseUdpHeader(buffer, transportOffset, length)
                PROTO_ICMP -> parseIcmpHeader(buffer, transportOffset, length)
                else -> TransportHeader.Unknown
            }

            // Build flow key
            val flowKey = buildFlowKey(ipHeader, transportHeader)

            return ParsedPacket(
                ipHeader = ipHeader,
                transportHeader = transportHeader,
                flowKey = flowKey
            )
        } catch (e: Exception) {
            // Catch any unexpected parsing errors
            // Do not crash - silently drop malformed packets
            Log.w(TAG, "Packet parsing failed: ${e.message}")
            return null
        }
    }

    /**
     * Parse IPv4 header from packet buffer.
     *
     * @param buffer Packet data
     * @param length Total packet length
     * @return Ipv4Header or null if invalid
     */
    private fun parseIpv4Header(buffer: ByteArray, length: Int): Ipv4Header? {
        // Check minimum size
        if (length < MIN_IPV4_HEADER) {
            return null
        }

        // Parse version and header length
        val versionIhl = buffer[IP_VERSION_IHL].toInt() and 0xFF
        val version = (versionIhl shr 4) and 0x0F
        val ihl = versionIhl and 0x0F
        val headerLength = ihl * 4

        // Validate IPv4
        if (version != 4) {
            return null
        }

        // Validate header length
        if (headerLength < MIN_IPV4_HEADER || headerLength > length) {
            return null
        }

        // Parse total length
        val totalLength = readUInt16(buffer, IP_TOTAL_LENGTH)

        // Parse protocol
        val protocol = buffer[IP_PROTOCOL].toInt() and 0xFF

        // Parse source and destination addresses
        val sourceAddress = formatIpAddress(buffer, IP_SRC_ADDR)
        val destinationAddress = formatIpAddress(buffer, IP_DST_ADDR)

        return Ipv4Header(
            version = version,
            headerLength = headerLength,
            totalLength = totalLength,
            protocol = protocol,
            sourceAddress = sourceAddress,
            destinationAddress = destinationAddress
        )
    }

    /**
     * Parse TCP header from packet buffer.
     *
     * @param buffer Packet data
     * @param offset Offset to TCP header
     * @param length Total packet length
     * @return TransportHeader.Tcp or Unknown if invalid
     */
    private fun parseTcpHeader(buffer: ByteArray, offset: Int, length: Int): TransportHeader {
        // Check minimum size
        if (offset + MIN_TCP_HEADER > length) {
            return TransportHeader.Unknown
        }

        try {
            // Parse ports
            val sourcePort = readUInt16(buffer, offset + TCP_SRC_PORT)
            val destinationPort = readUInt16(buffer, offset + TCP_DST_PORT)

            // Parse sequence and acknowledgment numbers
            val sequenceNumber = readUInt32(buffer, offset + TCP_SEQ_NUM)
            val acknowledgmentNumber = readUInt32(buffer, offset + TCP_ACK_NUM)

            // Parse data offset (header length)
            val dataOffsetFlags = buffer[offset + TCP_DATA_OFFSET_FLAGS].toInt() and 0xFF
            val dataOffset = (dataOffsetFlags shr 4) and 0x0F
            val tcpHeaderLength = dataOffset * 4

            // Validate TCP header length
            if (tcpHeaderLength < MIN_TCP_HEADER || offset + tcpHeaderLength > length) {
                return TransportHeader.Unknown
            }

            // Parse flags
            val flagsByte = buffer[offset + TCP_FLAGS].toInt() and 0xFF
            val flags = TcpFlags(
                fin = (flagsByte and 0x01) != 0,
                syn = (flagsByte and 0x02) != 0,
                rst = (flagsByte and 0x04) != 0,
                psh = (flagsByte and 0x08) != 0,
                ack = (flagsByte and 0x10) != 0,
                urg = (flagsByte and 0x20) != 0
            )

            return TransportHeader.Tcp(
                sourcePort = sourcePort,
                destinationPort = destinationPort,
                sequenceNumber = sequenceNumber,
                acknowledgmentNumber = acknowledgmentNumber,
                headerLength = tcpHeaderLength,
                flags = flags
            )
        } catch (e: Exception) {
            return TransportHeader.Unknown
        }
    }

    /**
     * Parse UDP header from packet buffer.
     *
     * @param buffer Packet data
     * @param offset Offset to UDP header
     * @param length Total packet length
     * @return TransportHeader.Udp or Unknown if invalid
     */
    private fun parseUdpHeader(buffer: ByteArray, offset: Int, length: Int): TransportHeader {
        // Check minimum size
        if (offset + MIN_UDP_HEADER > length) {
            return TransportHeader.Unknown
        }

        try {
            // Parse ports
            val sourcePort = readUInt16(buffer, offset + UDP_SRC_PORT)
            val destinationPort = readUInt16(buffer, offset + UDP_DST_PORT)

            // Parse length
            val udpLength = readUInt16(buffer, offset + UDP_LENGTH)

            return TransportHeader.Udp(
                sourcePort = sourcePort,
                destinationPort = destinationPort,
                length = udpLength
            )
        } catch (e: Exception) {
            return TransportHeader.Unknown
        }
    }

    /**
     * Parse ICMP header from packet buffer.
     *
     * @param buffer Packet data
     * @param offset Offset to ICMP header
     * @param length Total packet length
     * @return TransportHeader.Icmp or Unknown if invalid
     */
    private fun parseIcmpHeader(buffer: ByteArray, offset: Int, length: Int): TransportHeader {
        // Check minimum size
        if (offset + MIN_ICMP_HEADER > length) {
            return TransportHeader.Unknown
        }

        try {
            // Parse type and code
            val type = buffer[offset + ICMP_TYPE].toInt() and 0xFF
            val code = buffer[offset + ICMP_CODE].toInt() and 0xFF

            return TransportHeader.Icmp(
                type = type,
                code = code
            )
        } catch (e: Exception) {
            return TransportHeader.Unknown
        }
    }

    /**
     * Build flow key from parsed headers.
     *
     * @param ipHeader IP header
     * @param transportHeader Transport header
     * @return FlowKey identifying this flow
     */
    private fun buildFlowKey(ipHeader: Ipv4Header, transportHeader: TransportHeader): FlowKey {
        val (sourcePort, destinationPort) = when (transportHeader) {
            is TransportHeader.Tcp -> transportHeader.sourcePort to transportHeader.destinationPort
            is TransportHeader.Udp -> transportHeader.sourcePort to transportHeader.destinationPort
            else -> 0 to 0
        }

        return FlowKey(
            sourceAddress = ipHeader.sourceAddress,
            sourcePort = sourcePort,
            destinationAddress = ipHeader.destinationAddress,
            destinationPort = destinationPort,
            protocol = ipHeader.protocol
        )
    }

    /**
     * Read unsigned 16-bit integer from buffer (network byte order).
     */
    private fun readUInt16(buffer: ByteArray, offset: Int): Int {
        return ((buffer[offset].toInt() and 0xFF) shl 8) or
                (buffer[offset + 1].toInt() and 0xFF)
    }

    /**
     * Read unsigned 32-bit integer from buffer (network byte order).
     */
    private fun readUInt32(buffer: ByteArray, offset: Int): Long {
        return (((buffer[offset].toInt() and 0xFF).toLong() shl 24) or
                ((buffer[offset + 1].toInt() and 0xFF).toLong() shl 16) or
                ((buffer[offset + 2].toInt() and 0xFF).toLong() shl 8) or
                (buffer[offset + 3].toInt() and 0xFF).toLong()) and 0xFFFFFFFFL
    }

    /**
     * Format IP address from 4-byte buffer to dotted decimal string.
     */
    private fun formatIpAddress(buffer: ByteArray, offset: Int): String {
        return "${buffer[offset].toInt() and 0xFF}." +
                "${buffer[offset + 1].toInt() and 0xFF}." +
                "${buffer[offset + 2].toInt() and 0xFF}." +
                "${buffer[offset + 3].toInt() and 0xFF}"
    }
}

