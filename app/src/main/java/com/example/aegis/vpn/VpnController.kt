package com.example.aegis.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log

/**
 * VpnController - Helper for VPN service intent routing.
 * Phase 1 — VPN Skeleton & Lifecycle
 *
 * Provides clean separation between intent handling and VPN lifecycle.
 */
object VpnController {

    private const val TAG = "VpnController"

    /**
     * Prepares VPN for activation. Must be called before startVpn().
     *
     * @return Intent to request VPN permission, or null if already granted
     */
    fun prepare(context: Context): Intent? {
        return VpnService.prepare(context)
    }

    /**
     * Starts the VPN service.
     * Safe to call multiple times.
     *
     * @param context Application or activity context
     */
    fun startVpn(context: Context) {
        Log.d(TAG, "Requesting VPN start")
        val intent = Intent(context, AegisVpnService::class.java).apply {
            action = VpnConstants.ACTION_START
        }
        context.startForegroundService(intent)
    }

    /**
     * Stops the VPN service.
     * Safe to call multiple times.
     *
     * @param context Application or activity context
     */
    fun stopVpn(context: Context) {
        Log.d(TAG, "Requesting VPN stop")
        val intent = Intent(context, AegisVpnService::class.java).apply {
            action = VpnConstants.ACTION_STOP
        }
        context.startService(intent)
    }
}

