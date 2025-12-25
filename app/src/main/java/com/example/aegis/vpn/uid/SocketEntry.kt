package com.example.aegis.vpn.uid

/**
 * SocketEntry - Phase 5: UID Attribution
 *
 * Represents a parsed entry from /proc/net/tcp or /proc/net/udp.
 * Immutable data structure for kernel socket information.
 */
data class SocketEntry(
    val localAddress: String,
    val localPort: Int,
    val remoteAddress: String,
    val remotePort: Int,
    val uid: Int,
    val state: Int
) {
    companion object {
        // TCP states (from include/net/tcp_states.h)
        const val TCP_ESTABLISHED = 1
        const val TCP_SYN_SENT = 2
        const val TCP_SYN_RECV = 3
        const val TCP_CLOSE_WAIT = 8

        // Any state for UDP (UDP is stateless)
        const val UDP_ANY = 7
    }
}

