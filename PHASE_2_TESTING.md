# Phase 2 — TUN Interface & Routing - Testing Guide

## Project: Aegis VPN
**Phase**: 2 — TUN Interface & Routing  
**Date**: December 25, 2025

---

## Testing Objective

Verify that the VPN service can safely read packets from the TUN interface without crashes, resource leaks, or lifecycle issues. Internet connectivity is expected to be **unavailable** during Phase 2.

---

## Prerequisites

### Hardware/Emulator:
- Android device or emulator (API 21+ / Android 5.0+)
- ADB installed and device connected
- USB debugging enabled

### Build:
```powershell
cd C:\Users\user\AndroidStudioProjects\aegis
.\gradlew assembleDebug
```

### Install:
```powershell
.\gradlew installDebug
```

---

## Test Suite

### Test 1: Basic VPN Start/Stop

**Objective**: Verify VPN starts and stops without crashes.

**Steps**:
1. Launch Aegis app
2. Tap "Start VPN"
3. Grant VPN permission when prompted
4. Verify notification appears ("Aegis VPN - VPN is active")
5. Check logcat for successful start
6. Wait 10 seconds
7. Tap "Stop VPN"
8. Verify notification disappears
9. Check logcat for successful stop

**Expected Logs**:
```
AegisVpnService: Starting VPN service
AegisVpnService: VPN interface established
AegisVpnService: TunReader started
TunReader: Read loop started on thread AegisTunReader
AegisVpnService: VPN started successfully

[... packets being read ...]

AegisVpnService: Stopping VPN service
TunReader: Stopping TUN read loop
TunReader: Read loop exiting
TunReader: TUN read loop stopped. Stats: packets=X, bytes=Y
AegisVpnService: TunReader stopped
AegisVpnService: VPN interface closed
```

**Pass Criteria**:
- ✅ No crashes
- ✅ TunReader thread starts and stops cleanly
- ✅ VPN notification appears/disappears correctly

---

### Test 2: Packet Reading Under Traffic

**Objective**: Verify packets are read when device generates network traffic.

**Steps**:
1. Start VPN
2. Open browser or another app that attempts network access
3. Try to load a webpage (will fail — expected)
4. Check logcat for packet read logs
5. Verify statistics are incrementing
6. Stop VPN

**Expected Logs**:
```
TunReader: Packet read: length=60 bytes (total packets: 1)
TunReader: Packet read: length=1420 bytes (total packets: 1001)
TunReader: Packet read: length=532 bytes (total packets: 2001)
```

**Pass Criteria**:
- ✅ Packets are logged (every 1000th packet)
- ✅ Packet lengths are reasonable (20-1500 bytes)
- ✅ Total packet count increases
- ✅ **Internet does NOT work** (expected — no forwarding)

---

### Test 3: Idempotent Start/Stop

**Objective**: Verify multiple start/stop requests are handled safely.

**Steps**:
1. Tap "Start VPN" → Wait 2 seconds
2. Tap "Start VPN" again (should be ignored)
3. Check logs for "VPN already running" message
4. Tap "Stop VPN" → Wait 2 seconds
5. Tap "Stop VPN" again (should be handled safely)
6. Tap "Start VPN" again (should succeed)
7. Stop VPN

**Expected Behavior**:
- Multiple starts are ignored if VPN is already running
- Multiple stops complete without errors
- VPN can be restarted after stopping

**Pass Criteria**:
- ✅ No crashes
- ✅ "Already running" log appears
- ✅ VPN restarts successfully after stop

---

### Test 4: Screen Rotation

**Objective**: Verify VPN survives activity recreation.

**Steps**:
1. Start VPN
2. Rotate device/emulator screen
3. Wait 5 seconds
4. Verify VPN notification still present
5. Check logcat — TunReader should still be running
6. Rotate back
7. Stop VPN

**Pass Criteria**:
- ✅ VPN remains active through rotation
- ✅ TunReader thread continues running
- ✅ UI reconnects to service properly
- ✅ Can stop VPN after rotation

---

### Test 5: App Backgrounding

**Objective**: Verify VPN survives app going to background.

**Steps**:
1. Start VPN
2. Press Home button
3. Wait 30 seconds
4. Open another app (e.g., Settings)
5. Wait 30 seconds
6. Return to Aegis app
7. Verify VPN notification still present
8. Stop VPN

**Pass Criteria**:
- ✅ VPN remains active in background
- ✅ TunReader continues reading packets
- ✅ Foreground service notification persists
- ✅ Can stop VPN after returning

---

### Test 6: Screen Off

**Objective**: Verify VPN survives screen lock.

**Steps**:
1. Start VPN
2. Press power button to lock screen
3. Wait 60 seconds
4. Unlock screen
5. Open Aegis app
6. Verify VPN notification still present
7. Check logcat for continuous packet reads
8. Stop VPN

**Pass Criteria**:
- ✅ VPN remains active with screen off
- ✅ TunReader continues reading
- ✅ Device may generate network traffic (background apps)

---

### Test 7: VPN Permission Revocation

**Objective**: Verify clean shutdown when permission is revoked.

**Steps**:
1. Start VPN
2. Go to Settings → Apps → Aegis → Permissions
3. Revoke VPN permission (if possible on your Android version)
4. OR: Settings → Network & Internet → VPN → Aegis → Disconnect
5. Check logcat for `onRevoke()` call
6. Verify service stops cleanly

**Expected Logs**:
```
AegisVpnService: VPN permission revoked by system
AegisVpnService: Stopping VPN service
[... clean shutdown logs ...]
```

**Pass Criteria**:
- ✅ `onRevoke()` triggers `handleStop()`
- ✅ TunReader stops cleanly
- ✅ No crashes

---

### Test 8: Process Death and Recreation

**Objective**: Verify service survives system-initiated process kill.

**Steps**:
1. Start VPN
2. Press Home to background the app
3. Kill the app process via ADB:
   ```powershell
   adb shell am kill com.example.aegis
   ```
4. Wait 10 seconds
5. Check if VPN is still active (notification present)
6. Reopen Aegis app
7. Stop VPN

**Expected Behavior**:
- Service may or may not survive (depends on `START_NOT_STICKY`)
- If service is killed, VPN should disconnect cleanly
- If service survives, it should continue working

**Pass Criteria**:
- ✅ No crashes on process recreation
- ✅ If service killed, VPN disconnects properly
- ✅ If service survives, VPN continues working

---

### Test 9: Continuous Traffic Load

**Objective**: Verify stability under sustained packet load.

**Steps**:
1. Start VPN
2. Use ADB to generate continuous traffic:
   ```powershell
   adb shell
   ping 8.8.8.8
   ```
   (Will fail to reach destination, but generates packets)
3. Let it run for 5 minutes
4. Monitor logcat for errors or crashes
5. Check packet statistics
6. Stop VPN

**Expected Logs**:
```
TunReader: Packet read: length=84 bytes (total packets: 1)
TunReader: Packet read: length=84 bytes (total packets: 1001)
TunReader: Packet read: length=84 bytes (total packets: 2001)
[... continues ...]
```

**Pass Criteria**:
- ✅ No crashes
- ✅ Thousands of packets read successfully
- ✅ No memory leaks (check Android Profiler if available)
- ✅ TunReader stops cleanly after load test

---

### Test 10: Rapid Start/Stop Cycles

**Objective**: Verify no resource leaks on repeated start/stop.

**Steps**:
1. Start VPN → Wait 5 seconds → Stop VPN
2. Repeat 10 times
3. Check logcat for any errors
4. Verify no dangling threads or file descriptors

**Pass Criteria**:
- ✅ All 10 cycles complete without errors
- ✅ Thread always stops within timeout
- ✅ No "too many open files" errors
- ✅ Final stop is clean

---

## Logcat Filtering

### View All Aegis Logs:
```powershell
adb logcat -s AegisVpnService:* TunReader:* VpnController:*
```

### View Only Errors:
```powershell
adb logcat *:E AegisVpnService:D TunReader:D
```

### Clear and Follow:
```powershell
adb logcat -c; adb logcat -s AegisVpnService:* TunReader:*
```

---

## Common Issues and Debugging

### Issue: No packets being read
**Symptoms**: TunReader starts but no "Packet read" logs appear

**Debugging**:
1. Check if VPN interface established: Look for "VPN interface established" log
2. Generate traffic: Open browser, try to load webpage
3. Check if read thread is blocked: Look for "Read loop started" log
4. Verify file descriptor is valid: Check for null vpnInterface

**Possible Causes**:
- No apps generating network traffic
- VPN interface not properly established
- File descriptor closed prematurely

---

### Issue: "TUN interface EOF reached"
**Symptoms**: Read loop exits early with EOF message

**Debugging**:
1. Check if VPN was revoked: Look for `onRevoke()` log
2. Check if service is being stopped: Look for "Stopping VPN service"
3. Verify proper shutdown sequence

**Possible Causes**:
- System revoked VPN permission
- VPN interface closed while read loop active
- Improper shutdown sequence

---

### Issue: "Read thread did not terminate within timeout"
**Symptoms**: TunReader stop times out after 5 seconds

**Debugging**:
1. Check if thread is actually stopped: `adb shell ps -T | grep aegis`
2. Increase timeout temporarily for testing
3. Check for blocking operations in read loop

**Possible Causes**:
- Thread stuck in blocking read
- FileInputStream not responding to interrupt
- Deadlock (unlikely with current implementation)

---

### Issue: VPN starts but crashes after rotation
**Symptoms**: App crashes when screen is rotated

**Debugging**:
1. Check for lifecycle issues in MainActivity
2. Verify service is not coupled to Activity
3. Look for NullPointerException in logcat

**Possible Causes**:
- Activity trying to access service directly
- UI state not preserved
- Service reference not properly managed

---

## Performance Monitoring

### Check Thread Count:
```powershell
adb shell ps -T | findstr aegis
```

Should show:
- Main thread
- AegisTunReader thread (when VPN is running)

### Check CPU Usage:
```powershell
adb shell top | findstr aegis
```

Should be minimal (<5% CPU) when idle.

### Check Memory:
```powershell
adb shell dumpsys meminfo com.example.aegis
```

Should remain stable across multiple start/stop cycles.

---

## Phase 2 Acceptance Criteria

### Must Pass:
- ✅ Tests 1-3 (Basic functionality)
- ✅ Test 4-6 (Lifecycle robustness)
- ✅ Test 9 (Stability under load)

### Should Pass:
- ✅ Test 7 (Permission revocation)
- ✅ Test 10 (Resource management)

### May Fail (Acceptable):
- Test 8 (Process death) — Behavior depends on Android version and memory pressure

---

## Expected Limitations (Phase 2)

These are **correct** and **intentional**:

1. ❌ **Internet does not work** — No forwarding implemented yet
2. ❌ **Packets are dropped** — Observation only, no sockets
3. ❌ **No UID attribution** — Cannot identify which app owns packet
4. ❌ **No packet parsing** — Headers not decoded yet
5. ❌ **No statistics UI** — Stats tracked but not displayed

---

## Next Steps After Testing

If all tests pass:
1. Document any issues found
2. Create `PHASE_2_TESTING.md` with results
3. Proceed to Phase 3 (Packet Parsing)

If tests fail:
1. Document failure details
2. Check logcat for error messages
3. Review code for lifecycle issues
4. Fix and retest

---

## Quick Test Script

For rapid testing, run these commands in sequence:

```powershell
# Build and install
.\gradlew installDebug

# Clear logs and start monitoring
adb logcat -c; adb logcat -s AegisVpnService:* TunReader:* &

# Launch app (requires manual VPN start/stop via UI)
adb shell am start -n com.example.aegis/.MainActivity

# Generate test traffic
adb shell ping 8.8.8.8

# Kill app (for process death test)
adb shell am kill com.example.aegis
```

---

## Success Criteria Summary

**Phase 2 is complete when**:
- ✅ VPN starts and stops reliably
- ✅ TunReader reads packets without crashes
- ✅ Thread lifecycle is deterministic
- ✅ Service survives backgrounding and rotation
- ✅ Errors trigger clean teardown
- ✅ No resource leaks
- ✅ **Internet is unavailable** (expected)

**Current Status**: Implementation complete, ready for testing

