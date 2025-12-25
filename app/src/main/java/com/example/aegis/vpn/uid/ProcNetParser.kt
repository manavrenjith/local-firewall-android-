package com.example.aegis.vpn.uid

import android.util.Log
import java.io.File

/**
 * ProcNetParser - Phase 5: UID Attribution
 *
 * Parses /proc/net/tcp, tcp6, udp, udp6 files to extract socket information.
 * Best-effort parsing - errors are swallowed gracefully.
 *
 * Responsibilities:
 * - Read kernel socket tables
 * - Parse hex-encoded addresses and ports
 * - Extract UID from entries
 *
 * Non-responsibilities (Phase 5):
 * - No caching (handled by UidResolver)
 * - No flow matching (handled by UidResolver)
 * - No enforcement or blocking
 */
object ProcNetParser {

    private const val TAG = "ProcNetParser"

    // Proc net paths
    private const val PROC_NET_TCP = "/proc/net/tcp"
    private const val PROC_NET_TCP6 = "/proc/net/tcp6"
    private const val PROC_NET_UDP = "/proc/net/udp"
    private const val PROC_NET_UDP6 = "/proc/net/udp6"

    /**
     * Parse all TCP socket entries.
     * Best-effort - returns empty list on error.
     */
    fun parseTcp(): List<SocketEntry> {
        val entries = mutableListOf<SocketEntry>()
        entries.addAll(parseFile(PROC_NET_TCP, isIpv6 = false))
        entries.addAll(parseFile(PROC_NET_TCP6, isIpv6 = true))
        return entries
    }

    /**
     * Parse all UDP socket entries.
     * Best-effort - returns empty list on error.
     */
    fun parseUdp(): List<SocketEntry> {
        val entries = mutableListOf<SocketEntry>()
        entries.addAll(parseFile(PROC_NET_UDP, isIpv6 = false))
        entries.addAll(parseFile(PROC_NET_UDP6, isIpv6 = true))
        return entries
    }

    /**
     * Parse a specific proc net file.
     */
    private fun parseFile(path: String, isIpv6: Boolean): List<SocketEntry> {
        val entries = mutableListOf<SocketEntry>()

        try {
            val file = File(path)
            if (!file.exists() || !file.canRead()) {
                return entries
            }

            val lines = file.readLines()

            // Skip header line
            for (i in 1 until lines.size) {
                try {
                    val entry = parseLine(lines[i], isIpv6)
                    if (entry != null) {
                        entries.add(entry)
                    }
                } catch (e: Exception) {
                    // Skip malformed entries
                    continue
                }
            }

        } catch (e: Exception) {
            // File read error - log once and return empty
            Log.w(TAG, "Error reading $path: ${e.message}")
        }

        return entries
    }

    /**
     * Parse a single line from proc net file.
     * Format: sl local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid ...
     */
    private fun parseLine(line: String, isIpv6: Boolean): SocketEntry? {
        try {
            val parts = line.trim().split("\\s+".toRegex())

            if (parts.size < 8) {
                return null
            }

            // Parse local address (format: XXXXXXXX:XXXX or IPv6)
            val localParts = parts[1].split(":")
            if (localParts.size != 2) {
                return null
            }
            val localAddress = parseAddress(localParts[0], isIpv6)
            val localPort = parsePort(localParts[1])

            // Parse remote address
            val remoteParts = parts[2].split(":")
            if (remoteParts.size != 2) {
                return null
            }
            val remoteAddress = parseAddress(remoteParts[0], isIpv6)
            val remotePort = parsePort(remoteParts[1])

            // Parse state
            val state = parts[3].toInt(16)

            // Parse UID (column 7)
            val uid = parts[7].toInt()

            return SocketEntry(
                localAddress = localAddress,
                localPort = localPort,
                remoteAddress = remoteAddress,
                remotePort = remotePort,
                uid = uid,
                state = state
            )

        } catch (e: Exception) {
            // Parsing error - skip this entry
            return null
        }
    }

    /**
     * Parse hex-encoded address to IP string.
     */
    private fun parseAddress(hex: String, isIpv6: Boolean): String {
        try {
            if (isIpv6) {
                // IPv6: 32 hex chars representing 16 bytes
                if (hex.length == 32) {
                    return parseIpv6Address(hex)
                }
            } else {
                // IPv4: 8 hex chars in little-endian format
                if (hex.length == 8) {
                    return parseIpv4Address(hex)
                }
            }
        } catch (e: Exception) {
            // Parsing error
        }

        return "0.0.0.0"
    }

    /**
     * Parse IPv4 address from little-endian hex.
     * Example: 0100007F -> 127.0.0.1
     */
    private fun parseIpv4Address(hex: String): String {
        val bytes = ByteArray(4)
        for (i in 0..3) {
            bytes[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }

        // Little-endian: reverse bytes
        return "${bytes[3].toInt() and 0xFF}." +
                "${bytes[2].toInt() and 0xFF}." +
                "${bytes[1].toInt() and 0xFF}." +
                "${bytes[0].toInt() and 0xFF}"
    }

    /**
     * Parse IPv6 address from hex.
     * Simplified - returns hex representation.
     */
    private fun parseIpv6Address(hex: String): String {
        // IPv6 parsing is complex - for now, return hex form
        // Future enhancement: proper IPv6 formatting
        return hex
    }

    /**
     * Parse hex-encoded port to integer.
     */
    private fun parsePort(hex: String): Int {
        return try {
            hex.toInt(16)
        } catch (e: Exception) {
            0
        }
    }
}

