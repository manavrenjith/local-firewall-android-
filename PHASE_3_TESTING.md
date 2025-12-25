# Phase 3 — Packet Parsing - Testing Guide

## Project: Aegis VPN
**Phase**: 3 — Packet Parsing (Observation Only)  
**Date**: December 25, 2025

---

## Testing Objective

Verify that the packet parser correctly decodes IPv4, TCP, UDP, and ICMP headers without crashes or memory leaks. Internet connectivity is expected to remain **unavailable** during Phase 3.

---

## Prerequisites

### Build and Install:
```powershell
cd C:\Users\user\AndroidStudioProjects\aegis
.\gradlew installDebug
```

---

## Test Suite

### Test 1: TCP Packet Parsing (HTTPS)

**Objective**: Verify TCP header parsing with SYN/ACK flags.

**Steps**:
1. Start VPN
2. Monitor logs:
   ```powershell
   adb logcat -c; adb logcat -s TunReader:*
   ```
3. Open browser and try to visit `https://google.com`
4. Observe parsed TCP packets in logs

**Expected Logs**:
```
TunReader: Parsed: 192.168.x.x → 142.250.x.x | TCP 54321→443 [SYN] seq=123456789 | total=1
TunReader: Parsed: 192.168.x.x → 142.250.x.x | TCP 54321→443 [SYN ACK] seq=... | total=1001
```

**Pass Criteria**:
- ✅ Source/destination IPs shown
- ✅ Source port > 1024 (ephemeral)
- ✅ Destination port = 443 (HTTPS)
- ✅ TCP flags displayed (SYN, ACK, etc.)
- ✅ Sequence numbers present

---

### Test 2: UDP Packet Parsing (DNS)

**Objective**: Verify UDP header parsing for DNS queries.

**Steps**:
1. Start VPN
2. Monitor logs
3. Generate DNS traffic:
   ```powershell
   adb shell nslookup google.com
   ```
4. Observe parsed UDP packets

**Expected Logs**:
```
TunReader: Parsed: 192.168.x.x → 8.8.8.8 | UDP 12345→53 len=64 | total=2
```

**Pass Criteria**:
- ✅ Source IP is device IP
- ✅ Destination port = 53 (DNS)
- ✅ UDP length field present
- ✅ No crashes on DNS packets

---

### Test 3: ICMP Packet Parsing (Ping)

**Objective**: Verify ICMP header parsing.

**Steps**:
1. Start VPN
2. Monitor logs
3. Generate ICMP traffic:
   ```powershell
   adb shell ping -c 5 8.8.8.8
   ```
4. Observe parsed ICMP packets

**Expected Logs**:
```
TunReader: Parsed: 192.168.x.x → 8.8.8.8 | ICMP type=8 code=0 | total=3
```

**Pass Criteria**:
- ✅ ICMP type = 8 (echo request)
- ✅ ICMP code = 0
- ✅ Source/destination IPs correct
- ✅ No crashes on ICMP packets

---

### Test 4: Parse Failure Handling

**Objective**: Verify graceful handling of malformed packets.

**Steps**:
1. Start VPN
2. Monitor logs
3. Generate various traffic types
4. Check for parse failure logs

**Expected Logs**:
```
TunReader: Packet parsing failed (total failures: 1)
TunReader: Packet parsing failed (total failures: 101)
```

**Pass Criteria**:
- ✅ Parse failures logged (rate-limited)
- ✅ No service crashes
- ✅ VPN remains active
- ✅ Subsequent packets still parse

---

### Test 5: Parser Enable/Disable

**Objective**: Verify parser can be disabled via flag.

**Steps**:
1. Edit `VpnConstants.kt`:
   ```kotlin
   const val ENABLE_PACKET_PARSING = false
   ```
2. Rebuild and install
3. Start VPN
4. Generate traffic
5. Verify no parsed packet logs
6. Re-enable parser and test again

**Expected Behavior**:
- When disabled: Only raw packet count logs
- When enabled: Parsed packet detail logs

**Pass Criteria**:
- ✅ Parser respects enable flag
- ✅ No parsing when disabled
- ✅ Normal parsing when enabled
- ✅ No crashes in either mode

---

### Test 6: High Packet Rate

**Objective**: Verify parser handles sustained load.

**Steps**:
1. Start VPN
2. Monitor logs
3. Generate continuous traffic:
   ```powershell
   adb shell
   while true; do ping -c 1 8.8.8.8; sleep 0.1; done
   ```
4. Let run for 5 minutes
5. Check statistics

**Expected Logs**:
```
TunReader: Parsed: ... | ICMP type=8 code=0 | total=1001
TunReader: Parsed: ... | ICMP type=8 code=0 | total=2001
TunReader: Parsed: ... | ICMP type=8 code=0 | total=3001
...
TunReader: TUN read loop stopped. Stats: packets=5000, bytes=420000, parsed=5000, parseFailures=0
```

**Pass Criteria**:
- ✅ No crashes under load
- ✅ Thousands of packets parsed
- ✅ Parse success rate high (>95%)
- ✅ No memory leaks

---

### Test 7: TCP Flag Variations

**Objective**: Verify all TCP flags are parsed correctly.

**Steps**:
1. Start VPN
2. Monitor logs
3. Open browser and try multiple connections
4. Observe different TCP flag combinations

**Expected Flags**:
- `SYN` — Connection initiation
- `SYN ACK` — Connection acceptance
- `ACK` — Data acknowledgment
- `PSH ACK` — Data push
- `FIN ACK` — Connection close
- `RST` — Connection reset

**Pass Criteria**:
- ✅ All flag types appear in logs
- ✅ Flag combinations correct
- ✅ Multiple flags shown together (e.g., "SYN ACK")

---

### Test 8: Multiple Protocols Simultaneously

**Objective**: Verify parser handles mixed traffic.

**Steps**:
1. Start VPN
2. Monitor logs
3. Generate mixed traffic:
   - Open browser (TCP)
   - Run DNS lookup (UDP): `adb shell nslookup google.com`
   - Run ping (ICMP): `adb shell ping 8.8.8.8`
4. Observe interleaved parsed packets

**Expected Logs**:
```
TunReader: Parsed: ... | TCP 54321→443 [SYN] ...
TunReader: Parsed: ... | UDP 12345→53 len=64 ...
TunReader: Parsed: ... | ICMP type=8 code=0 ...
TunReader: Parsed: ... | TCP 54321→443 [SYN ACK] ...
```

**Pass Criteria**:
- ✅ All protocol types logged
- ✅ No interference between protocols
- ✅ Parse counts accurate for each type

---

### Test 9: Statistics Accuracy

**Objective**: Verify parsing statistics are accurate.

**Steps**:
1. Start VPN
2. Monitor logs
3. Generate exactly 100 ping packets:
   ```powershell
   adb shell ping -c 100 8.8.8.8
   ```
4. Stop VPN
5. Check final statistics

**Expected Final Log**:
```
TunReader: TUN read loop stopped. Stats: packets=100, bytes=8400, parsed=100, parseFailures=0
```

**Pass Criteria**:
- ✅ `packets` count = 100
- ✅ `parsed` count = 100
- ✅ `parseFailures` count = 0
- ✅ `bytes` count reasonable (~84 bytes per ping packet)

---

### Test 10: Lifecycle Robustness

**Objective**: Verify parser survives VPN lifecycle events.

**Steps**:
1. Start VPN → Generate traffic → Check parsing
2. Background app → Return → Check parsing still works
3. Rotate screen → Check parsing still works
4. Stop VPN → Start VPN → Check parsing resets
5. Stop VPN

**Pass Criteria**:
- ✅ Parsing survives backgrounding
- ✅ Parsing survives rotation
- ✅ Statistics reset on VPN restart
- ✅ No parser state leaks between sessions

---

## Protocol-Specific Validation

### TCP Packet Structure:
```
IP Header:
  Version: 4
  Protocol: 6 (TCP)
  Source IP: 192.168.x.x
  Dest IP: Remote server

TCP Header:
  Source Port: Ephemeral (>1024)
  Dest Port: 443 (HTTPS), 80 (HTTP)
  Sequence: 32-bit number
  Acknowledgment: 32-bit number
  Flags: SYN, ACK, FIN, RST, PSH, URG
```

### UDP Packet Structure:
```
IP Header:
  Version: 4
  Protocol: 17 (UDP)
  Source IP: Device IP
  Dest IP: DNS server (e.g., 8.8.8.8)

UDP Header:
  Source Port: Ephemeral
  Dest Port: 53 (DNS)
  Length: Packet length
```

### ICMP Packet Structure:
```
IP Header:
  Version: 4
  Protocol: 1 (ICMP)
  Source IP: Device IP
  Dest IP: Target IP

ICMP Header:
  Type: 8 (echo request)
  Code: 0
```

---

## Logcat Filtering

### View All Parsed Packets:
```powershell
adb logcat -s TunReader:D
```

### View Only Parse Errors:
```powershell
adb logcat *:E TunReader:W
```

### Count Parsed Packets:
```powershell
adb logcat -s TunReader:D | findstr "Parsed:"
```

### Check Statistics:
```powershell
adb logcat -s TunReader:I | findstr "Stats:"
```

---

## Common Issues and Debugging

### Issue: No parsed packet logs
**Symptoms**: Only raw packet count logs appear

**Debugging**:
1. Check `VpnConstants.ENABLE_PACKET_PARSING = true`
2. Check `VpnConstants.LOG_PARSED_PACKETS = true`
3. Verify traffic is being generated
4. Look for parse failure logs

**Possible Causes**:
- Parser disabled via flag
- All packets failing to parse
- No network traffic being generated

---

### Issue: All packets fail to parse
**Symptoms**: High `parseFailures` count, no parsed packets

**Debugging**:
1. Check logcat for specific parse errors
2. Verify IPv4 traffic (not IPv6)
3. Check packet buffer size
4. Look for truncated packets

**Possible Causes**:
- IPv6 traffic (not yet supported)
- Malformed packets from kernel
- Buffer overflow issues

---

### Issue: TCP flags not displayed
**Symptoms**: TCP packets logged but no flags shown

**Debugging**:
1. Verify TCP traffic being generated
2. Check if flag byte is being read correctly
3. Look for "Unknown protocol" logs

**Possible Causes**:
- TCP header too short
- Flag byte parsing error
- Non-TCP traffic

---

### Issue: Parser crashes service
**Symptoms**: VPN stops after parsing attempt

**Debugging**:
1. Check logcat for exceptions
2. Look for ArrayIndexOutOfBoundsException
3. Verify bounds checking in parser

**This should NOT happen** — parser has defensive bounds checking.

---

## Performance Monitoring

### Check Parsing Overhead:
```powershell
# Monitor CPU usage
adb shell top | findstr aegis
```

Should remain <5% CPU with parsing enabled.

### Check Memory Usage:
```powershell
adb shell dumpsys meminfo com.example.aegis
```

Should remain stable across parsing.

### Parsing Rate:
```powershell
# Calculate packets/sec from logs
# If 1000 packets logged over 10 seconds = 100 pps
```

---

## Phase 3 Acceptance Criteria

### Must Pass:
- ✅ Tests 1-3 (TCP/UDP/ICMP parsing)
- ✅ Test 4 (Parse failure handling)
- ✅ Test 6 (High packet rate)
- ✅ Test 10 (Lifecycle robustness)

### Should Pass:
- ✅ Test 5 (Enable/disable)
- ✅ Test 7 (TCP flag variations)
- ✅ Test 8 (Mixed protocols)
- ✅ Test 9 (Statistics accuracy)

---

## Expected Limitations (Phase 3)

These are **correct** and **intentional**:

1. ❌ **Internet does not work** — Packets parsed but not forwarded
2. ❌ **No UID attribution** — Cannot identify which app owns packet
3. ❌ **IPv4 only** — IPv6 not yet supported
4. ❌ **No checksum validation** — Trusting kernel
5. ❌ **No flow tracking** — Packets parsed independently

---

## Success Criteria Summary

**Phase 3 is complete when**:
- ✅ TCP packets parse correctly (ports, flags, seq/ack)
- ✅ UDP packets parse correctly (ports, length)
- ✅ ICMP packets parse correctly (type, code)
- ✅ Malformed packets handled gracefully (no crashes)
- ✅ Parser can be enabled/disabled
- ✅ High packet rates handled without degradation
- ✅ Statistics accurate
- ✅ **Internet is unavailable** (expected)

**Current Status**: Implementation complete, ready for testing

---

## Quick Test Script

```powershell
# Build and install
.\gradlew installDebug

# Start monitoring
adb logcat -c; adb logcat -s TunReader:* &

# Launch app and start VPN manually via UI

# Test TCP (browser)
adb shell am start -a android.intent.action.VIEW -d https://google.com

# Test UDP (DNS)
adb shell nslookup google.com

# Test ICMP (ping)
adb shell ping -c 10 8.8.8.8

# Check statistics
# Stop VPN via UI and check final stats in log
```

---

## Comparison: Phase 2 vs Phase 3

| Aspect | Phase 2 | Phase 3 |
|--------|---------|---------|
| Packet reading | ✅ | ✅ |
| Raw byte logging | ✅ | ✅ |
| IP header parsing | ❌ | ✅ |
| Protocol identification | ❌ | ✅ |
| TCP/UDP/ICMP details | ❌ | ✅ |
| Parse statistics | ❌ | ✅ |
| Forwarding | ❌ | ❌ |
| Internet | ❌ | ❌ |

---

**Phase 3 adds protocol awareness while maintaining observation-only behavior.**

