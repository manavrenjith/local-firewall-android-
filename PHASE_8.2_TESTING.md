# Phase 8.2 — Forwarding Telemetry & Flow Metrics
# Testing Guide

**Phase**: 8.2 — Forwarding Telemetry & Flow Metrics  
**Date**: December 25, 2025  
**Status**: Ready for Testing

---

## Testing Objective

Verify that the telemetry infrastructure:
1. Does not affect forwarding behavior
2. Records metrics correctly
3. Handles errors gracefully
4. Provides accurate snapshots
5. Does not cause performance degradation

---

## Pre-Testing Checklist

- [ ] Phase 8.2 code compiled successfully
- [ ] APK built without errors
- [ ] Device/emulator has internet connectivity
- [ ] Previous phases (8.0, 8.1) working correctly

---

## Test 1: Basic Functionality (Forwarding Unchanged)

**Objective**: Verify that telemetry does not affect TCP forwarding.

### Steps:
1. Install APK on device/emulator
2. Launch Aegis VPN app
3. Tap "Start VPN" and grant permission
4. Open Chrome browser
5. Navigate to multiple HTTPS websites:
   - https://www.google.com
   - https://www.github.com
   - https://www.wikipedia.org
6. Verify all sites load correctly
7. Tap "Stop VPN"

### Expected Results:
✅ All HTTPS sites load successfully  
✅ No crashes or ANRs  
✅ No visible performance degradation  
✅ Behavior identical to Phase 8.1  

### Logcat Verification:
```bash
adb logcat | grep -E "(TcpForwarder|FlowTable|TunReader)"
```

Look for:
- ✅ No telemetry errors
- ✅ No exceptions in telemetry code
- ✅ Normal forwarding logs

---

## Test 2: Telemetry Recording (Manual Verification)

**Objective**: Verify telemetry counters are updated correctly.

### Steps:
1. Start VPN
2. Open browser and load a website (e.g., https://example.com)
3. Stop VPN
4. Check logcat for flow table cleanup logs

### Logcat Commands:
```bash
# Monitor flow table statistics
adb logcat | grep "FlowTable"

# Monitor forwarder activity
adb logcat | grep "TcpForwarder"
```

### Expected Logs:
```
FlowTable: Flow cleanup: removed X idle flows
TcpForwarder: TCP forwarder initialized for FlowKey(...)
TcpForwarder: TCP forwarder closed for FlowKey(...)
```

### Manual Code Verification:
To manually inspect telemetry, temporarily add debug logs in `TunReader.handlePacket()`:

```kotlin
// After attemptForwarding call, add:
val flow = flowTable.getFlow(parsedPacket.flowKey)
if (flow != null && totalPacketsRead.get() % 100 == 0L) {
    synchronized(flow) {
        Log.d(TAG, "Telemetry: uplink=${flow.telemetry.uplinkPackets} " +
                   "downlink=${flow.telemetry.downlinkPackets} " +
                   "errors=${flow.telemetry.forwardingErrors}")
    }
}
```

Expected output every 100 packets:
```
TunReader: Telemetry: uplink=45 downlink=128 errors=0
```

---

## Test 3: Snapshot API (Code Inspection)

**Objective**: Verify snapshot API works correctly.

### Test Code:
Add temporary test in `AegisVpnService.startTunReader()` after creating flow table:

```kotlin
// Test snapshot API (remove after testing)
Thread({
    Thread.sleep(30000)  // Wait 30 seconds for some flows
    val snapshots = flowTable?.snapshotFlows() ?: emptyList()
    Log.d(TAG, "=== Flow Snapshots (${snapshots.size}) ===")
    snapshots.forEach { snapshot ->
        Log.d(TAG, "Flow: ${snapshot.flowKey}")
        Log.d(TAG, "  Uplink: ${snapshot.uplinkPackets} pkts, ${snapshot.uplinkBytes} bytes")
        Log.d(TAG, "  Downlink: ${snapshot.downlinkPackets} pkts, ${snapshot.downlinkBytes} bytes")
        Log.d(TAG, "  Errors: ${snapshot.forwardingErrors}")
        Log.d(TAG, "  Direction: ${snapshot.lastActivityDirection}")
    }
}, "SnapshotTest").start()
```

### Expected Logcat Output:
```
AegisVpnService: === Flow Snapshots (3) ===
AegisVpnService: Flow: FlowKey(192.168.0.5:54321 -> 93.184.216.34:443, TCP)
AegisVpnService:   Uplink: 12 pkts, 1024 bytes
AegisVpnService:   Downlink: 35 pkts, 8192 bytes
AegisVpnService:   Errors: 0
AegisVpnService:   Direction: DOWNLINK
```

### Verification:
✅ Snapshots are returned  
✅ Counters are non-zero for active flows  
✅ No crashes or exceptions  
✅ Snapshots are immutable  

---

## Test 4: Error Resilience

**Objective**: Verify telemetry errors don't crash forwarding.

This is implicitly tested since all telemetry operations are wrapped in try-catch with silent suppression. No explicit test needed unless you want to inject faults.

### Verification:
✅ No telemetry-related crashes in all previous tests  
✅ Forwarding continues even if telemetry fails  

---

## Test 5: Debug Logging (Optional)

**Objective**: Verify optional aggregate logging works.

### Steps:
1. Enable debug logging in `TelemetryLogger.kt`:
   ```kotlin
   private const val DEBUG_ENABLED = true
   ```
2. Rebuild APK
3. Install and start VPN
4. Browse multiple websites for 60+ seconds
5. Check logcat for aggregate logs

### Expected Logcat Output:
```
TelemetryLogger: === Telemetry Snapshot ===
TelemetryLogger: Flows: 5 (TCP: 5)
TelemetryLogger: Forwarders: 3 active, 10 created, 7 closed
TelemetryLogger: Forwarding: 3 active flows
TelemetryLogger: Traffic: 1.2 MB ↑ / 3.5 MB ↓
TelemetryLogger: Packets: 2847 forwarded
TelemetryLogger: Errors: 0
TelemetryLogger: =========================
```

### Verification:
✅ Logs appear every ~30 seconds  
✅ Counters increase over time  
✅ No performance degradation  

**Important**: Disable debug logging after testing by setting `DEBUG_ENABLED = false`.

---

## Test 6: Stress Test

**Objective**: Verify telemetry under heavy load.

### Steps:
1. Start VPN
2. Open multiple browser tabs simultaneously:
   - Tab 1: https://www.google.com
   - Tab 2: https://www.youtube.com (load video)
   - Tab 3: https://www.reddit.com
   - Tab 4: https://www.twitter.com
3. Navigate and scroll in all tabs for 2-3 minutes
4. Stop VPN

### Expected Results:
✅ All websites work correctly  
✅ No ANRs or crashes  
✅ No telemetry errors in logcat  
✅ VPN stops cleanly  

### Logcat Verification:
```bash
adb logcat | grep -E "(ERROR|FATAL|ANR)"
```

Should show no telemetry-related errors.

---

## Test 7: VPN Lifecycle

**Objective**: Verify telemetry cleanup on VPN stop.

### Steps:
1. Start VPN
2. Browse a website
3. Stop VPN
4. Repeat 3 times

### Expected Results:
✅ Each start/stop cycle works correctly  
✅ No memory leaks (telemetry cleared)  
✅ No lingering telemetry from previous sessions  

### Logcat Verification:
```bash
adb logcat | grep "Flow table cleared"
```

Should appear on each VPN stop.

---

## Performance Testing (Optional)

### Baseline Comparison:
1. Measure Phase 8.1 performance (without telemetry)
2. Measure Phase 8.2 performance (with telemetry)
3. Compare:
   - Page load times
   - Memory usage
   - CPU usage

### Expected:
- **Page load times**: < 5% difference
- **Memory usage**: < 1 MB increase
- **CPU usage**: < 2% increase

If degradation is higher, telemetry may need optimization.

---

## Known Acceptable Warnings

The following warnings are expected and acceptable:

1. **"Class FlowTelemetry is never used"** - Used via FlowEntry
2. **"Function recordUplinkForward is never used"** - Called by TcpForwarder
3. **"Object TelemetryLogger is never used"** - Called by TunReader
4. **"Condition '!DEBUG_ENABLED' is always true"** - Intentional (debug flag)

These are IDE warnings due to indirect usage. They do not indicate actual problems.

---

## Failure Investigation

### If HTTPS stops working:
1. Check if forwarding behavior was accidentally changed
2. Review TcpForwarder modifications
3. Verify telemetry updates don't block forwarding
4. Check for exceptions in logcat

### If telemetry counters are always zero:
1. Verify `flow.telemetry.recordXXX()` calls are being executed
2. Add temporary debug logs in recording methods
3. Check if flows are ALLOW_READY before forwarding

### If crashes occur:
1. Check stack trace in logcat
2. Identify which telemetry operation failed
3. Verify all telemetry operations have try-catch
4. Report issue with full stack trace

---

## Test Completion Checklist

After completing all tests, verify:

- [ ] Test 1: Basic functionality passed
- [ ] Test 2: Telemetry recording verified
- [ ] Test 3: Snapshot API works
- [ ] Test 4: Error resilience confirmed
- [ ] Test 5: Debug logging works (if enabled)
- [ ] Test 6: Stress test passed
- [ ] Test 7: VPN lifecycle clean
- [ ] No telemetry-related crashes
- [ ] No performance degradation
- [ ] Forwarding behavior unchanged

---

## Reporting Results

### Success Criteria:
✅ All tests passed  
✅ Forwarding behavior unchanged from Phase 8.1  
✅ Telemetry counters increase correctly  
✅ No crashes or ANRs  
✅ Snapshot API returns valid data  

### If All Tests Pass:
**Phase 8.2 is validated and ready for production.**

### If Any Test Fails:
Document:
1. Which test failed
2. Steps to reproduce
3. Logcat output
4. Expected vs actual behavior

---

## Post-Testing Cleanup

After testing:

1. **Disable debug logging** if enabled:
   ```kotlin
   private const val DEBUG_ENABLED = false  // in TelemetryLogger.kt
   ```

2. **Remove any temporary test code** added for verification

3. **Rebuild APK** for clean release

---

## Conclusion

Phase 8.2 adds pure observational telemetry infrastructure. It should be **invisible** to the user and have **zero impact** on forwarding behavior. If all tests pass, the telemetry layer is ready for future consumption by UI or debugging tools.

**Next Phase**: Phase 9+ will likely consume this telemetry for user-facing features.

