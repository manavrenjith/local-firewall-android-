package com.example.aegis.vpn.enforcement

/**
 * EnforcementState - Phase 7: Enforcement Controller
 *
 * Represents the enforcement readiness state for a flow.
 * Metadata only - does not control actual enforcement yet.
 *
 * State transitions (monotonic):
 * NONE → ALLOW_READY (if decision = ALLOW)
 * NONE → BLOCK_READY (if decision = BLOCK + confidence checks pass)
 *
 * Never downgrades once set.
 */
enum class EnforcementState {
    /**
     * No enforcement state applied.
     * Default state for all new flows.
     */
    NONE,

    /**
     * Flow is ready to be allowed.
     * Phase 8+: Will enable forwarding.
     * Phase 7: Metadata only.
     */
    ALLOW_READY,

    /**
     * Flow is ready to be blocked.
     * Phase 8+: Will prevent forwarding.
     * Phase 7: Metadata only.
     */
    BLOCK_READY
}

