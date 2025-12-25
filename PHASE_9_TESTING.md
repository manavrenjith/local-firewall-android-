# Phase 9 — UDP Socket Forwarding
# Testing Guide

**Phase**: 9 — UDP Socket Forwarding (Stateless, Direction-Safe)  
**Date**: December 25, 2025  
**Status**: Ready for Testing

---

## Testing Objective

Verify that UDP forwarding:
1. Enables DNS resolution
2. Supports QUIC/HTTP3 traffic
3. Works with messaging apps
4. Handles streaming protocols
5. Does not affect TCP forwarding
6. Prevents routing loops
7. Cleans up properly

---

## Pre-Testing Checklist

- [ ] Phase 9 code compiled successfully
- [ ] APK built without errors
- [ ] Device/emulator has internet connectivity
- [ ] Previous phases (8.0, 8.1, 8.3) working correctly
- [ ] TCP forwarding (HTTPS) verified working

---

## Test 1: DNS Resolution (Critical)

**Objective**: Verify UDP/53 DNS queries work correctly.

### Steps:
1. Install APK on device/emulator
2. Launch Aegis VPN app
3. Tap "Start VPN" and grant permission
4. Open Chrome browser
5. Navigate to a **new domain** you haven't visited:
   - https://www.wikipedia.org
   - https://www.reddit.com
   - https://news.ycombinator.com
6. Verify pages load successfully
7. Check DNS resolution time (should be fast)

### Expected Results:
✅ All new domains resolve correctly  
✅ Pages load without DNS errors  
✅ Resolution time < 1 second  
✅ No "DNS_PROBE_FINISHED_NXDOMAIN" errors  

### Logcat Verification:
```bash
adb logcat | grep -E "(UdpForwarder|DNS)"
```

Look for:
```
UdpForwarder: UDP forwarder initialized for FlowKey(...:53)
UdpForwarder: UDP forwarder created for ... (total: X)
```

### If DNS Fails:
- Check if UDP forwarder is being created for port 53
- Verify DatagramSocket protection is working
- Check for routing loop errors

---

## Test 2: QUIC/HTTP3 Protocol

**Objective**: Verify UDP/443 QUIC traffic works.

### Steps:
1. Ensure VPN is running
2. Open Chrome browser
3. Navigate to: https://www.google.com
4. Open Chrome DevTools (if on desktop browser via debugging)
5. Check Network tab → Protocol column
6. Look for "h3" or "http/3"

### Alternative Test:
Visit QUIC-specific sites:
- https://quic.tech:4433/
- https://www.chromium.org/quic/

### Expected Results:
✅ Google loads successfully  
✅ QUIC protocol negotiation works  
✅ HTTP/3 connections established (if supported)  
✅ Fast page loading  

### Logcat Verification:
```bash
adb logcat | grep "UdpForwarder.*:443"
```

Expected:
```
UdpForwarder: UDP forwarder created for FlowKey(...:443)
```

---

## Test 3: Messaging Apps (Real-World)

**Objective**: Verify UDP-based messaging works.

### Test with WhatsApp:
1. VPN running
2. Open WhatsApp
3. Send a message to a contact
4. Make a voice call (if possible)
5. Verify connection status

### Test with Telegram:
1. Open Telegram
2. Send message
3. Join voice chat
4. Verify connectivity

### Test with Signal:
1. Open Signal
2. Send message
3. Verify delivery

### Expected Results:
✅ Messages send successfully  
✅ Voice calls connect  
✅ No connection errors  
✅ App shows "Connected" status  

### Logcat Verification:
```bash
adb logcat | grep "UdpForwarder" | grep -v ":53\|:443"
```

Should show various UDP ports being forwarded.

---

## Test 4: Video Streaming

**Objective**: Verify UDP streaming protocols work.

### YouTube Test:
1. VPN running
2. Open YouTube app
3. Play a video
4. Watch for 30+ seconds
5. Check for buffering

### Expected Results:
✅ Video loads quickly  
✅ No buffering or stuttering  
✅ Quality adjusts smoothly  
✅ No connection errors  

### Alternative Streaming:
- Netflix (if available)
- Twitch
- Any live stream

---

## Test 5: TCP Forwarding Unchanged

**Objective**: Verify TCP forwarding still works perfectly.

### Steps:
1. VPN running
2. Open Chrome
3. Navigate to HTTPS sites:
   - https://www.github.com
   - https://www.example.com
   - https://httpbin.org/get
4. Verify all load correctly
5. Compare loading speed with Phase 8.1

### Expected Results:
✅ All HTTPS sites work  
✅ Loading speed unchanged  
✅ No regression in TCP performance  
✅ Certificates validate correctly  

### Verification:
TCP forwarding should be **identical** to Phase 8.1 behavior.

---

## Test 6: Routing Loop Prevention

**Objective**: Verify no routing loops occur.

### Steps:
1. Start VPN
2. Generate mixed TCP/UDP traffic:
   - Browse websites (TCP)
   - Do DNS lookups (UDP)
   - Watch video (UDP)
3. Monitor for 5 minutes
4. Check logcat for routing errors

### Expected Results:
✅ No routing loop errors  
✅ No "socket protection failed" messages  
✅ VPN remains stable  
✅ No packet storms  

### Logcat Check:
```bash
adb logcat | grep -E "(routing|loop|protect)"
```

Should NOT see:
```
Failed to protect socket
Routing loop detected
```

---

## Test 7: Idle Timeout Cleanup

**Objective**: Verify UDP forwarders are cleaned up after idle.

### Steps:
1. Start VPN
2. Open browser
3. Visit a website (creates UDP forwarder for DNS)
4. Wait 2 minutes without any activity
5. Check logcat for cleanup messages

### Expected Results:
✅ Idle UDP forwarders cleaned up after 60 seconds  
✅ Cleanup logs appear  
✅ No memory leaks  

### Logcat Verification:
```bash
adb logcat | grep "Cleaned up.*UDP forwarders"
```

Expected:
```
ForwarderRegistry: Cleaned up X idle UDP forwarders (total: Y)
```

---

## Test 8: VPN Lifecycle

**Objective**: Verify clean startup and shutdown with UDP.

### Steps:
1. Start VPN
2. Generate UDP traffic (browse websites)
3. Check forwarder count in logs
4. Stop VPN
5. Verify cleanup
6. Repeat 3 times

### Expected Results:
✅ VPN starts successfully  
✅ UDP forwarders created as needed  
✅ VPN stops cleanly  
✅ All forwarders closed  
✅ No lingering threads  
✅ No memory leaks  

### Logcat Verification:
```bash
adb logcat | grep "All forwarders closed"
```

Expected:
```
ForwarderRegistry: All forwarders closed (TCP: X, UDP: Y)
```

---

## Test 9: Telemetry Verification (Optional)

**Objective**: Verify UDP telemetry is recorded correctly.

### Setup:
1. Enable debug logging in `TelemetryLogger.kt`:
   ```kotlin
   private const val DEBUG_ENABLED = true
   ```
2. Rebuild APK
3. Install

### Steps:
1. Start VPN
2. Generate mixed traffic for 60+ seconds
3. Check logcat for telemetry snapshots

### Expected Logcat Output:
```
TelemetryLogger: === Telemetry Snapshot ===
TelemetryLogger: Flows: 10 (TCP: 6)
TelemetryLogger: Forwarders: TCP 5, UDP 4
TelemetryLogger: Created: TCP 8, UDP 12
TelemetryLogger: Closed: TCP 3, UDP 8
TelemetryLogger: Forwarding: 9 active flows
TelemetryLogger: Traffic: 2.5 MB ↑ / 5.2 MB ↓
TelemetryLogger: Packets: 8432 forwarded
TelemetryLogger: Errors: 0
TelemetryLogger: =========================
```

### Verification:
✅ UDP forwarder counts shown  
✅ Counters increase over time  
✅ No excessive errors  

**Important**: Disable debug logging after testing.

---

## Test 10: Stress Test

**Objective**: Verify UDP forwarding under load.

### Steps:
1. Start VPN
2. Simultaneously:
   - Open 5 browser tabs with different sites
   - Start a video stream
   - Send messages in WhatsApp
   - Make a voice call if possible
3. Monitor for 3-5 minutes
4. Check for errors or slowdowns

### Expected Results:
✅ All activities work simultaneously  
✅ No ANRs or crashes  
✅ No significant slowdown  
✅ UDP forwarders scale appropriately  
✅ Memory usage remains stable  

### Logcat Check:
```bash
adb logcat | grep -E "(ERROR|FATAL|ANR)"
```

Should NOT see UDP-related errors.

---

## Performance Benchmarks

### DNS Resolution Time

**Test**: Resolve 10 new domains
- **Expected**: < 1 second per domain
- **Acceptable**: < 2 seconds per domain
- **Issue**: > 3 seconds per domain

### QUIC Connection Setup

**Test**: Load Google homepage
- **Expected**: < 1 second
- **Acceptable**: < 2 seconds
- **Issue**: > 3 seconds

### Streaming Quality

**Test**: Watch YouTube for 2 minutes
- **Expected**: No buffering, HD quality
- **Acceptable**: Rare buffering, adapts quality
- **Issue**: Constant buffering

### Memory Usage

**Test**: Monitor memory during 10-minute session
- **Expected**: < 50 MB increase
- **Acceptable**: < 100 MB increase
- **Issue**: > 150 MB increase or memory leak

---

## Failure Investigation

### If DNS Doesn't Work:
1. Check UDP forwarder creation for port 53
2. Verify DatagramSocket protection
3. Check for "Unresolved reference" errors
4. Test with `nslookup` or `dig` on device

### If QUIC Doesn't Work:
1. Verify UDP forwarder for port 443
2. Check if HTTP/3 is being used (may fall back to TCP)
3. Some sites don't use QUIC — this is normal

### If Messaging Apps Fail:
1. Check which UDP ports they use
2. Verify forwarders are created for those ports
3. Check app-specific connection requirements

### If Routing Loops Occur:
1. Verify `protect()` is called on DatagramSocket
2. Check address swapping in downlink reinjection
3. Review packet reconstruction logic

### If Crashes Occur:
1. Get full stack trace from logcat
2. Identify which component failed
3. Check for null pointer exceptions
4. Verify all error handling is in place

---

## Test Completion Checklist

After completing all tests, verify:

- [ ] Test 1: DNS resolution passed
- [ ] Test 2: QUIC/HTTP3 passed
- [ ] Test 3: Messaging apps passed
- [ ] Test 4: Streaming passed
- [ ] Test 5: TCP unchanged passed
- [ ] Test 6: No routing loops passed
- [ ] Test 7: Idle cleanup passed
- [ ] Test 8: Lifecycle clean passed
- [ ] Test 9: Telemetry correct (optional)
- [ ] Test 10: Stress test passed
- [ ] No UDP-related crashes
- [ ] No performance degradation
- [ ] TCP forwarding unchanged

---

## Reporting Results

### Success Criteria:
✅ All critical tests passed (1-8)  
✅ UDP forwarding functional  
✅ TCP forwarding unchanged  
✅ No crashes or ANRs  
✅ Performance acceptable  

### If All Tests Pass:
**Phase 9 is validated and ready for production.**

### If Any Test Fails:
Document:
1. Which test failed
2. Steps to reproduce
3. Logcat output
4. Expected vs actual behavior
5. Device/emulator details

---

## Known Limitations (Acceptable)

1. **UDP Packet Loss**: Normal for UDP protocol
2. **Idle Timeout**: Forwarders close after 60 seconds
3. **No Retransmission**: Application layer handles this
4. **Best-Effort Telemetry**: May miss counts under extreme load
5. **QUIC Fallback**: Some sites fall back to TCP/HTTPS

These are by design and expected.

---

## Post-Testing Cleanup

After testing:

1. **Disable debug logging** if enabled:
   ```kotlin
   private const val DEBUG_ENABLED = false  // in TelemetryLogger.kt
   ```

2. **Rebuild APK** for clean release

3. **Clear test data** if needed

---

## Advanced Testing (Optional)

### Packet Capture Analysis:
```bash
adb shell "tcpdump -i any -w /sdcard/capture.pcap"
```

Then analyze to verify:
- DNS queries are UDP/53
- QUIC uses UDP/443
- Packets have correct checksums

### Performance Profiling:
Use Android Studio Profiler to monitor:
- CPU usage (should be < 5% increase)
- Memory allocation (should be stable)
- Network activity (should match usage)

---

## Conclusion

Phase 9 adds full UDP forwarding to Aegis VPN. When all tests pass:

✅ **DNS resolution works** — Web browsing fully functional  
✅ **QUIC traffic flows** — Modern protocols supported  
✅ **Messaging apps work** — Real-time communication enabled  
✅ **Streaming works** — Media playback smooth  
✅ **TCP unchanged** — Existing functionality preserved  
✅ **No routing loops** — Protected sockets working  
✅ **Clean lifecycle** — Startup/shutdown robust  

**Phase 9 achieves complete network parity with full bidirectional TCP + UDP forwarding!**

**Next Phase**: Future phases can focus on advanced features, performance optimization, and UI enhancements.

