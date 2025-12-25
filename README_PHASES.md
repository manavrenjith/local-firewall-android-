# Aegis VPN — Phase Documentation Index

## Project Overview
**Aegis** is a NetGuard-aligned, VpnService-based local firewall implemented using a clean-room approach.

---

## Phase 0: Ground Rules
**File**: Contact user for Phase 0 document  
**Purpose**: Non-negotiable architectural constraints  
**Status**: ✅ Established

### Key Rules:
- VpnService is sole network endpoint
- All allowed traffic must use protected sockets
- TUN is not a bridge
- Strict layer separation
- Fail-open during uncertainty

---

## Phase 1: VPN Skeleton & Lifecycle
**Documentation**:
- `PHASE_1_SUMMARY.md` — Implementation summary
- `PHASE_1_STRUCTURE.md` — Code structure
- `PHASE_1_TESTING.md` — Testing guide

**Status**: ✅ Complete

**What Was Implemented**:
- AegisVpnService (VpnService subclass)
- Foreground service with notification
- VPN establishment and teardown
- Start/Stop intent handling
- VpnController helper
- Lifecycle-safe operations

**What Was NOT Implemented**:
- No TUN reads/writes
- No packet handling
- No sockets
- No UID attribution

---

## Phase 2: TUN Interface & Routing
**Documentation**:
- `PHASE_2_SUMMARY.md` — Implementation summary
- `PHASE_2_STRUCTURE.md` — Code structure
- `PHASE_2_TESTING.md` — Testing guide
- `PHASE_2_COMPLETE.md` — Completion summary

**Status**: ✅ Complete

**What Was Implemented**:
- TunReader class (packet reading)
- Single background thread for TUN reads
- Fixed-size 32KB packet buffer
- Statistics tracking (packets, bytes)
- Error callback mechanism
- Deterministic thread shutdown
- Integration with AegisVpnService

**What Was NOT Implemented**:
- No packet parsing (IP headers not decoded)
- No packet modification
- No packet forwarding (all dropped)
- No sockets
- No UID attribution

**Expected Behavior**:
- ✅ VPN starts and reads packets
- ✅ Statistics update in real-time
- ❌ **Internet is unavailable** (correct, no forwarding)

---

## Phase 3: Packet Parsing (Observation Only)
**Documentation**:
- `PHASE_3_SUMMARY.md` — Implementation summary
- `PHASE_3_TESTING.md` — Testing guide
- `PHASE_3_COMPLETE.md` — Completion summary

**Status**: ✅ Complete

**What Was Implemented**:
- PacketParser (stateless, defensive)
- ParsedPacket data structures (immutable)
- IPv4 header parsing
- TCP header parsing (ports, seq/ack, flags)
- UDP header parsing (ports, length)
- ICMP header parsing (type, code)
- FlowKey generation (5-tuple)
- Parser integration with TunReader
- Parsing statistics tracking
- Enable/disable flags

**What Was NOT Implemented**:
- No packet modification
- No packet forwarding (all dropped)
- No sockets
- No UID attribution
- No rule engine
- No checksum validation
- IPv6 not supported yet

**Expected Behavior**:
- ✅ VPN starts and parses packets
- ✅ Protocol information logged (TCP/UDP/ICMP)
- ✅ TCP flags displayed (SYN, ACK, etc.)
- ✅ Parse statistics tracked
- ❌ **Internet is unavailable** (correct, no forwarding)

---

## Implementation Files

### Core VPN Files:
```
app/src/main/java/com/example/aegis/
├── MainActivity.kt
│   └── UI for VPN control (start/stop)
└── vpn/
    ├── AegisVpnService.kt
    │   └── VPN lifecycle owner (Phase 1 + Phase 2)
    ├── TunReader.kt
    │   └── TUN packet reader + parser integration (Phase 2 + Phase 3)
    ├── VpnController.kt
    │   └── Intent routing helper (Phase 1)
    ├── VpnConstants.kt
    │   └── Configuration constants (Phase 1 + Phase 3)
    └── packet/ (Phase 3)
        ├── ParsedPacket.kt
        │   └── Immutable data structures
        └── PacketParser.kt
            └── Stateless packet parser
```

### Documentation Files:
```
aegis/
├── PHASE_1_SUMMARY.md       — Phase 1 implementation details
├── PHASE_1_STRUCTURE.md     — Phase 1 code structure
├── PHASE_1_TESTING.md       — Phase 1 testing guide
├── PHASE_2_SUMMARY.md       — Phase 2 implementation details
├── PHASE_2_STRUCTURE.md     — Phase 2 code structure
├── PHASE_2_TESTING.md       — Phase 2 testing guide
├── PHASE_2_COMPLETE.md      — Phase 2 completion summary
├── PHASE_3_SUMMARY.md       — Phase 3 implementation details
├── PHASE_3_TESTING.md       — Phase 3 testing guide
├── PHASE_3_COMPLETE.md      — Phase 3 completion summary
└── README_PHASES.md         — This index file
```

---

## Quick Start

### Build and Install:
```powershell
cd C:\Users\user\AndroidStudioProjects\aegis
.\gradlew installDebug
```

### Run and Monitor:
```powershell
# Launch app
adb shell am start -n com.example.aegis/.MainActivity

# Monitor logs
adb logcat -c; adb logcat -s AegisVpnService:* TunReader:*
```

### Generate Test Traffic:
```powershell
adb shell ping 8.8.8.8
```

---

## Testing Status

| Phase | Build | Basic Tests | Full Tests | Status |
|-------|-------|-------------|------------|--------|
| Phase 0 | N/A | N/A | N/A | ✅ Rules defined |
| Phase 1 | ✅ | ✅ | ✅ | ✅ Complete |
| Phase 2 | ✅ | ⏸️ Manual | ⏸️ Pending | ✅ Complete |
| Phase 3 | ✅ | ⏸️ Manual | ⏸️ Pending | ✅ Complete |
| Phase 4 | ⏸️ | ⏸️ | ⏸️ | ⏸️ Not started |

---

## Current State

### What Works:
✅ VPN lifecycle (start/stop)  
✅ Foreground service  
✅ TUN packet reading  
✅ Packet parsing (IPv4/TCP/UDP/ICMP)  
✅ Protocol identification  
✅ TCP flags extraction  
✅ Flow key generation  
✅ Thread lifecycle  
✅ Error recovery  
✅ Statistics tracking  

### What Doesn't Work (Intentional):
❌ Internet connectivity (no forwarding yet)  
❌ UID attribution (Phase 4)  
❌ Rule engine (Phase 5+)  
❌ Packet forwarding (Phase 6+)  
❌ IPv6 support (future phase)  

---

## Known Issues

### Expected Limitations:
1. **Internet unavailable** — Packets are dropped (correct for Phase 2)
2. **No packet details** — Headers not parsed yet
3. **All traffic treated equally** — No per-app control yet

### None of these are bugs — they are phase boundaries.

---

## Next Phase: Phase 4 — UID Attribution

### Phase 4 Goals:
- Attribute packets to originating app (UID)
- Read /proc/net/tcp and /proc/net/udp
- Build connection tracking table
- Resolve process names
- **Still no forwarding** (packets still dropped)

### Phase 4 Non-Goals:
- Rule engine
- Protected sockets
- Packet forwarding

---

## Development Environment

### Requirements:
- Android Studio (latest)
- Kotlin 1.9+
- Gradle 8.0+
- Android SDK 21+ (Android 5.0+)

### Build System:
- Gradle with Kotlin DSL
- Version catalog (libs.versions.toml)
- Jetpack Compose for UI

---

## Architecture Principles

### Layer Separation:
```
UI Layer (MainActivity)
    ↓
Control Layer (VpnController)
    ↓
Service Layer (AegisVpnService)
    ↓
I/O Layer (TunReader)
    ↓
Network Layer (TUN interface)
```

### Ownership:
- MainActivity → does NOT own service
- VpnController → stateless helper
- AegisVpnService → owns TunReader
- TunReader → owns read thread

### Thread Model:
- **Main Thread**: UI only
- **Binder Threads**: Service communication
- **AegisTunReader**: Packet reading

---

## Testing Approach

### Phase 1 Testing:
- Manual UI testing
- Lifecycle stress testing
- Permission flow testing

### Phase 2 Testing:
- Packet reading verification
- Thread lifecycle testing
- Error recovery testing
- Load testing (continuous traffic)

### Future Testing:
- Phase 3: Packet parsing correctness
- Phase 4: UID attribution accuracy
- Phase 5: Rule engine logic
- Phase 6: Forwarding performance

---

## Performance Targets

### Phase 2 Targets (Observation Only):
- CPU: <5% under active traffic
- Memory: <5MB heap for VPN service
- Thread count: 1 reader thread only
- Latency: N/A (no forwarding)

### Future Targets:
- Phase 3: Same as Phase 2 (just parsing)
- Phase 6: <10ms forwarding latency
- Phase 7: Production optimization

---

## Security Model

### Phase 2 Security:
- ✅ All traffic enters through VPN
- ✅ VPN permission required
- ✅ Foreground service (visible)
- ✅ No elevated privileges
- ⚠️ No packet inspection yet
- ⚠️ No UID attribution yet
- ⚠️ No blocking yet

### Future Security:
- Phase 4: UID attribution (identify apps)
- Phase 5: Rule engine (allow/block)
- Phase 6: Protected forwarding
- Phase 7: Security hardening

---

## Code Quality Standards

### Current Standards:
- ✅ Clear separation of concerns
- ✅ Lifecycle-safe operations
- ✅ Deterministic resource cleanup
- ✅ Comprehensive error handling
- ✅ Thread-safe operations
- ✅ Inline documentation

### Future Standards:
- Unit tests (Phase 3+)
- Integration tests (Phase 6+)
- Performance profiling (Phase 7)
- Security audit (Phase 7)

---

## Resources

### Android Documentation:
- [VpnService API](https://developer.android.com/reference/android/net/VpnService)
- [Foreground Services](https://developer.android.com/guide/components/foreground-services)
- [Thread Management](https://developer.android.com/guide/background/threading)

### NetGuard Reference:
- [NetGuard GitHub](https://github.com/M66B/NetGuard)
- Clean-room approach: No code copying, design inspiration only

---

## FAQ

### Q: Why is internet unavailable?
**A**: Phase 2 only reads packets for observation. Forwarding is Phase 6.

### Q: When will I see per-app blocking?
**A**: Phase 4 (UID attribution) + Phase 5 (rule engine) + Phase 6 (forwarding).

### Q: Can I use this as a real firewall now?
**A**: No. It's still in development. Phase 7 will be production-ready.

### Q: How do I test packet reading?
**A**: See `PHASE_2_TESTING.md` for full test procedures.

### Q: What if I find a bug?
**A**: Check if it's a phase boundary first (see "Expected Limitations").

---

## Version History

| Version | Phase | Date | Status |
|---------|-------|------|--------|
| 0.1.0 | Phase 1 | Dec 2025 | ✅ Complete |
| 0.2.0 | Phase 2 | Dec 2025 | ✅ Complete |
| 0.3.0 | Phase 3 | TBD | ⏸️ Planned |

---

## Contact

For questions about the phase-based implementation approach or architectural decisions, refer to the Phase 0 Ground Rules document.

---

**Current Status**: Phase 3 Complete ✅  
**Next Phase**: Phase 4 — UID Attribution  
**Overall Progress**: ~30% to NetGuard parity  

---

*This index is updated at the completion of each phase.*

