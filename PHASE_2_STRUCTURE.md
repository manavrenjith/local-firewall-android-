# Phase 2 — TUN Interface & Routing - Code Structure

## Project: Aegis VPN
**Phase**: 2 — TUN Interface & Routing  
**Date**: December 25, 2025

---

## File Structure

```
aegis/
├── app/
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml
│           └── java/com/example/aegis/
│               ├── MainActivity.kt (unchanged from Phase 1)
│               └── vpn/
│                   ├── AegisVpnService.kt ✏️ MODIFIED
│                   ├── TunReader.kt ✅ VERIFIED (already existed)
│                   ├── VpnConstants.kt (unchanged)
│                   └── VpnController.kt (unchanged)
├── PHASE_0_GROUND_RULES.md
├── PHASE_1_SUMMARY.md
├── PHASE_1_TESTING.md
├── PHASE_2_SUMMARY.md ✅ NEW
├── PHASE_2_TESTING.md ✅ NEW
└── PHASE_2_STRUCTURE.md ✅ NEW (this file)
```

---

## Component Relationships

```
MainActivity
    ↓ (user input)
VpnController
    ↓ (start/stop intents)
AegisVpnService ← ← ← ← ← ← ← ← ← ┐
    ↓                               │
    ├─→ VpnInterface (ParcelFileDescriptor)
    │       ↓                       │
    └─→ TunReader                   │
            ↓                       │
            FileInputStream.read()  │
            ↓                       │
            handlePacket()          │
            ↓                       │
            (silently dropped)      │
            ↓ (on error)            │
            errorCallback() ─ ─ ─ ─ ┘
```

---

## AegisVpnService.kt Changes

### New Fields:
```kotlin
private var tunReader: TunReader? = null
```

### New Methods:
```kotlin
private fun startTunReader() {
    // Creates TunReader with error callback
    // Starts read loop after VPN establishment
}

private fun stopTunReader() {
    // Stops read thread before VPN teardown
    // Safe to call multiple times
}
```

### Modified Methods:
```kotlin
private fun handleStart() {
    // ...existing setup...
    startTunReader() // ← NEW
    // ...existing completion...
}

private fun handleStop() {
    // ...existing checks...
    stopTunReader() // ← NEW
    teardownVpn()
    // ...existing cleanup...
}

override fun onDestroy() {
    stopTunReader() // ← NEW
    teardownVpn()
    // ...existing cleanup...
}
```

### Error Callback Flow:
```kotlin
TunReader(vpnInterface) {
    // This lambda is called on unrecoverable read errors
    Log.e(TAG, "TunReader reported error, initiating VPN teardown")
    handleStop()
}
```

---

## TunReader.kt (Already Existed, Verified Compliant)

### Class Signature:
```kotlin
class TunReader(
    private val vpnInterface: ParcelFileDescriptor,
    private val onError: () -> Unit
)
```

### Public API:
```kotlin
fun start()                           // Starts read loop
fun stop()                            // Stops read loop (blocking)
fun getStats(): Pair<Long, Long>      // Returns (packets, bytes)
```

### Private Methods:
```kotlin
private fun runReadLoop()             // Main read loop (thread)
private fun handlePacket(buffer, length) // Process read packet
private fun triggerError()            // Invoke error callback
```

### Thread Management:
```kotlin
private var readThread: Thread? = null
private val isRunning = AtomicBoolean(false)
```

### Statistics:
```kotlin
private val totalPacketsRead = AtomicLong(0)
private val totalBytesRead = AtomicLong(0)
```

---

## Lifecycle Sequence Diagram

### Start VPN:
```
User Taps "Start VPN"
    ↓
MainActivity.requestVpnStart()
    ↓
VpnController.startVpn(context)
    ↓
AegisVpnService.onStartCommand(ACTION_START)
    ↓
AegisVpnService.handleStart()
    ↓
    ├─→ startForeground() (notification)
    ├─→ establishVpn() (VPN interface)
    └─→ startTunReader() ← NEW
            ↓
            TunReader created with error callback
            ↓
            TunReader.start()
            ↓
            readThread spawned ("AegisTunReader")
            ↓
            runReadLoop() begins
            ↓
            FileInputStream.read() (blocking)
            ↓
            handlePacket() called for each packet
            ↓
            [Packet silently dropped] ✓
```

### Stop VPN:
```
User Taps "Stop VPN"
    ↓
MainActivity.requestVpnStop()
    ↓
VpnController.stopVpn(context)
    ↓
AegisVpnService.onStartCommand(ACTION_STOP)
    ↓
AegisVpnService.handleStop()
    ↓
    ├─→ stopTunReader() ← NEW
    │       ↓
    │       isRunning.set(false)
    │       ↓
    │       readThread.interrupt()
    │       ↓
    │       readThread.join(5000ms)
    │       ↓
    │       [Read loop exits]
    │
    ├─→ teardownVpn()
    │       ↓
    │       vpnInterface.close()
    │
    └─→ stopForeground() & stopSelf()
```

### Error Recovery:
```
[Unrecoverable IO error in read loop]
    ↓
TunReader.triggerError()
    ↓
onError callback invoked
    ↓
AegisVpnService.handleStop() ← same as normal stop
    ↓
[Clean teardown sequence]
```

---

## Thread Model

### Phase 1 (Before):
- **Main Thread**: UI and intent handling
- **Binder Threads**: Service communication (Android system)

### Phase 2 (After):
- **Main Thread**: UI and intent handling
- **Binder Threads**: Service communication (Android system)
- **AegisTunReader Thread**: Packet reading (blocking I/O) ← NEW

### Thread Ownership:
- AegisVpnService **owns** the TunReader instance
- TunReader **owns** the read thread
- Service **controls** thread lifecycle via `start()`/`stop()`

### Thread Safety:
- `isRunning`: AtomicBoolean (lock-free state)
- `totalPacketsRead/Bytes`: AtomicLong (lock-free counters)
- `readThread`: Guarded by `isRunning` check
- No shared mutable state between threads

---

## Data Flow

### Packet Path:
```
Android Network Stack
    ↓
TUN Virtual Interface
    ↓
File Descriptor (non-blocking)
    ↓
FileInputStream.read(buffer) [blocking call]
    ↓
buffer: ByteArray[32KB]
    ↓
handlePacket(buffer, length)
    ↓
    ├─→ totalPacketsRead.increment()
    ├─→ totalBytesRead.add(length)
    └─→ Log (rate-limited)
    ↓
[PACKET DROPPED — End of Phase 2]
```

### Future Path (Phase 3+):
```
handlePacket(buffer, length)
    ↓
[Phase 3: Parse IP header]
    ↓
[Phase 4: UID attribution]
    ↓
[Phase 5: Apply rules]
    ↓
[Phase 6: Forward via protected socket]
    ↓
Internet
```

---

## Error Handling

### TunReader Errors:
| Error Type | Trigger | Action |
|------------|---------|--------|
| IOException (running) | Network error | Log ERROR → triggerError() → handleStop() |
| IOException (stopping) | Expected during shutdown | Log DEBUG → Continue |
| InterruptedException | Thread interrupted | Log WARN → Exit loop |
| Any other Exception | Unexpected error | Log ERROR → triggerError() → handleStop() |
| EOF (length < 0) | VPN interface closed | Log WARN → Exit loop |

### Service Errors:
| Error Type | Trigger | Action |
|------------|---------|--------|
| establish() returns null | VPN setup failed | Log ERROR → stopSelf() |
| TunReader constructor throws | Invalid FD | Log ERROR → handleStop() |
| stopTunReader() timeout | Thread won't stop | Log WARN → Continue teardown |

---

## Constants and Configuration

### TunReader Constants:
```kotlin
private const val PACKET_BUFFER_SIZE = 32 * 1024  // 32KB
```

### VpnConstants (unchanged):
```kotlin
const val VPN_ADDRESS = "10.1.10.1"           // VPN interface address
const val VPN_ROUTE = "0.0.0.0"               // Route all traffic
const val VPN_PREFIX_LENGTH = 0                // Full routing
const val VPN_MTU = 1500                       // Standard Ethernet MTU
```

---

## Logging Strategy

### Service Logs:
```kotlin
Log.i(TAG, "Starting VPN service")              // Lifecycle events
Log.d(TAG, "VPN interface established")         // Setup details
Log.e(TAG, "Failed to establish VPN", e)        // Errors with exceptions
```

### TunReader Logs:
```kotlin
Log.i(TAG, "Starting TUN read loop")            // Thread lifecycle
Log.d(TAG, "Packet read: length=X ...")         // Every 1000th packet
Log.w(TAG, "TUN interface EOF reached")         // Warnings
Log.e(TAG, "IO error reading from TUN", e)      // Errors
```

### Rate Limiting:
```kotlin
if (totalPacketsRead.get() % 1000 == 1L) {
    Log.d(TAG, "Packet read: ...")
}
```

---

## Memory Management

### Heap Allocations:
- **TunReader instance**: Created once per VPN session
- **Thread instance**: Created once per VPN session
- **Packet buffer**: 32KB allocated once, reused for all packets
- **FileInputStream**: Wraps existing FD, minimal overhead

### Resource Cleanup:
```kotlin
// Stop TunReader
tunReader?.stop()        // Thread interrupted and joined
tunReader = null         // Instance eligible for GC

// Close VPN interface
vpnInterface?.close()    // FD closed
vpnInterface = null      // Instance eligible for GC
```

### No Leaks:
- Thread is deterministically stopped (interrupt + join)
- File descriptor is always closed in `teardownVpn()`
- No static references to lifecycle-scoped objects

---

## Testing Hooks

### Statistics Access:
```kotlin
val (packets, bytes) = tunReader?.getStats() ?: Pair(0, 0)
```

### Thread State Check:
```kotlin
val isActive = tunReader != null && readThread?.isAlive == true
```

### Logs for Verification:
- Every 1000th packet logged with count
- Final stats logged on stop: `"Stats: packets=X, bytes=Y"`

---

## Phase Compliance

### Phase 0 Ground Rules: ✅
- VpnService is sole endpoint
- No protected sockets yet (correct for Phase 2)
- TUN not used as bridge (packets read, not written back)
- Layer separation (read ≠ parse ≠ forward)
- Lifecycle safety (deterministic shutdown)

### Phase 1 Constraints: ✅
- VPN lifecycle unchanged (start/stop still idempotent)
- Foreground service maintained
- Clean teardown (TunReader stopped first)
- No changes to notification or permission handling

### Phase 2 Requirements: ✅
- TUN reading implemented
- Observation only (no forwarding)
- Single read thread
- Blocking reads from TUN
- Fixed-size buffer (32KB)
- Lifecycle-safe thread management
- Graceful error handling
- No packet parsing (correct)
- No sockets (correct)
- Packets silently dropped (correct by design)

---

## Known Limitations (Intentional)

1. **No internet connectivity**: Packets dropped, not forwarded
2. **No packet inspection**: Headers not parsed
3. **No UID attribution**: Cannot identify app source
4. **Aggressive logging**: Rate-limited but still visible
5. **Single-threaded read**: One packet at a time

**All limitations are by design for Phase 2.**

---

## Next Phase Preview

**Phase 3** will add:
```kotlin
// Inside TunReader.handlePacket()
private fun handlePacket(buffer: ByteArray, length: Int) {
    // Phase 2: Update stats, log, drop
    updateStats(length)
    
    // Phase 3: Parse IP header (NEW)
    val ipPacket = parseIpHeader(buffer, length)
    if (ipPacket == null) {
        // Invalid packet, drop
        return
    }
    
    // Phase 3: Log protocol info (NEW)
    Log.d(TAG, "Protocol: ${ipPacket.protocol}, " +
               "Src: ${ipPacket.srcIp}, Dst: ${ipPacket.dstIp}")
    
    // Still no forwarding in Phase 3
}
```

---

## Build Verification

### Clean Build:
```powershell
cd C:\Users\user\AndroidStudioProjects\aegis
.\gradlew clean
.\gradlew assembleDebug
```

### Expected Output:
```
BUILD SUCCESSFUL in Xs
36 actionable tasks: X executed, X up-to-date
```

### APK Location:
```
app/build/outputs/apk/debug/app-debug.apk
```

---

## Summary

Phase 2 adds **read-only TUN packet observation** with proper thread lifecycle management. The implementation is:

- ✅ **Correct**: Follows all Phase 0/1 constraints
- ✅ **Safe**: Deterministic teardown, no leaks
- ✅ **Complete**: All Phase 2 requirements met
- ✅ **Testable**: Clear logs and statistics
- ✅ **Maintainable**: Clean separation of concerns

**Internet unavailability is expected and correct.**

Ready for Phase 3: Packet Parsing.

