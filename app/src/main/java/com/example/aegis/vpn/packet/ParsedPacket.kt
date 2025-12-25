package com.example.aegis.vpn.packet

/**
 * ParsedPacket - Phase 3: Packet Parsing
 *
 * Immutable representation of a parsed IP packet.
 * Contains IP header and transport layer information.
 */
data class ParsedPacket(
    val ipHeader: Ipv4Header,
    val transportHeader: TransportHeader,
    val flowKey: FlowKey
)

/**
 * IPv4 header information (immutable).
 */
data class Ipv4Header(
    val version: Int,
    val headerLength: Int,
    val totalLength: Int,
    val protocol: Int,
    val sourceAddress: String,
    val destinationAddress: String
)

/**
 * Transport layer header (sealed class for type safety).
 */
sealed class TransportHeader {
    data class Tcp(
        val sourcePort: Int,
        val destinationPort: Int,
        val sequenceNumber: Long,
        val acknowledgmentNumber: Long,
        val headerLength: Int,
        val flags: TcpFlags
    ) : TransportHeader()

    data class Udp(
        val sourcePort: Int,
        val destinationPort: Int,
        val length: Int
    ) : TransportHeader()

    data class Icmp(
        val type: Int,
        val code: Int
    ) : TransportHeader()

    object Unknown : TransportHeader()
}

/**
 * TCP flags (immutable).
 */
data class TcpFlags(
    val fin: Boolean,
    val syn: Boolean,
    val rst: Boolean,
    val psh: Boolean,
    val ack: Boolean,
    val urg: Boolean
)

/**
 * Flow key - 5-tuple identifier for network flows.
 * Used to identify unique connections.
 */
data class FlowKey(
    val sourceAddress: String,
    val sourcePort: Int,
    val destinationAddress: String,
    val destinationPort: Int,
    val protocol: Int
) {
    override fun toString(): String {
        return "$sourceAddress:$sourcePort -> $destinationAddress:$destinationPort ($protocol)"
    }
}

