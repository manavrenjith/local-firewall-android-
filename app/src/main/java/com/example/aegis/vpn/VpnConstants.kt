package com.example.aegis.vpn

/**
 * Constants for VPN service operations.
 * Phase 1 — VPN Skeleton & Lifecycle
 */
object VpnConstants {
    // Intent actions
    const val ACTION_START = "com.example.aegis.action.START_VPN"
    const val ACTION_STOP = "com.example.aegis.action.STOP_VPN"

    // Notification
    const val NOTIFICATION_CHANNEL_ID = "aegis_vpn_channel"
    const val NOTIFICATION_CHANNEL_NAME = "Aegis VPN Service"
    const val NOTIFICATION_ID = 1

    // VPN configuration
    const val VPN_ADDRESS = "10.1.10.1"
    const val VPN_ROUTE = "0.0.0.0"
    const val VPN_PREFIX_LENGTH = 0
    const val VPN_MTU = 1500
}

