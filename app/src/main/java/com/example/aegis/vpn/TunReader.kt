package com.example.aegis.vpn

import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * TunReader - Phase 2: TUN Interface & Routing
 *
 * Responsibilities:
 * - Read raw IP packets from VPN TUN interface
 * - Observation-only (no forwarding, no modification)
 * - Thread lifecycle management
 * - Graceful error handling
 *
 * Non-responsibilities (Phase 2):
 * - No packet parsing
 * - No packet modification
 * - No packet forwarding
 * - No socket operations
 * - No UID attribution
 * - No enforcement logic
 */
class TunReader(
    private val vpnInterface: ParcelFileDescriptor,
    private val onError: () -> Unit
) {

    companion object {
        private const val TAG = "TunReader"
        private const val PACKET_BUFFER_SIZE = 32 * 1024 // 32KB buffer
    }

    private var readThread: Thread? = null
    private val isRunning = AtomicBoolean(false)

    // Statistics (observation only)
    private val totalPacketsRead = AtomicLong(0)
    private val totalBytesRead = AtomicLong(0)

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

        Log.i(TAG, "TUN read loop stopped. Stats: packets=$totalPacketsRead, bytes=$totalBytesRead")
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
     *
     * @param buffer Byte array containing packet data
     * @param length Number of valid bytes in buffer
     */
    private fun handlePacket(buffer: ByteArray, length: Int) {
        // Update statistics
        totalPacketsRead.incrementAndGet()
        totalBytesRead.addAndGet(length.toLong())

        // Phase 2: Observation only
        // Log packet receipt (with rate limiting in production)
        if (totalPacketsRead.get() % 1000 == 1L) {
            Log.d(TAG, "Packet read: length=$length bytes (total packets: ${totalPacketsRead.get()})")
        }

        // Future phases will:
        // - Parse IP headers
        // - Classify protocol (TCP/UDP/ICMP)
        // - Attribute to UID
        // - Apply rules
        // - Forward via protected sockets

        // Phase 2: Packet is silently dropped (by design)
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

