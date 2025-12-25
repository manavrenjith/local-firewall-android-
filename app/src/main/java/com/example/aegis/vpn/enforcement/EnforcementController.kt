package com.example.aegis.vpn.enforcement

import android.util.Log
import com.example.aegis.vpn.flow.FlowDecision
import com.example.aegis.vpn.flow.FlowEntry
import com.example.aegis.vpn.flow.FlowTable

/**
 * EnforcementController - Phase 7: Enforcement Controller (Gatekeeper, No Forwarding)
 *
 * Determines enforcement readiness for flows based on decisions and confidence checks.
 * Does NOT enforce - only prepares enforcement state metadata.
 *
 * Responsibilities:
 * - Observe flow decisions
 * - Apply confidence gating for BLOCK decisions
 * - Transition enforcement state (metadata only)
 * - Fail-open on uncertainty
 *
 * Non-responsibilities (Phase 7):
 * - No packet dropping
 * - No packet forwarding
 * - No socket operations
 * - No traffic modification
 */
class EnforcementController(private val flowTable: FlowTable) {

    companion object {
        private const val TAG = "EnforcementController"

        // Evaluation timing
        private const val EVALUATION_INTERVAL_MS = 20000L  // 20 seconds

        // Confidence parameters
        private const val MIN_FLOW_AGE_FOR_BLOCK_MS = 5000L  // 5 seconds
        private const val UID_UNKNOWN = -1
    }

    // Last evaluation time
    private var lastEvaluationTime = 0L

    // Statistics
    private var totalEvaluations = 0L
    private var allowReadyCount = 0L
    private var blockReadyCount = 0L

    /**
     * Evaluate flows and update enforcement states.
     * Called periodically from existing timing mechanisms.
     * Non-blocking and fail-open.
     */
    fun evaluateEnforcement() {
        try {
            val now = System.currentTimeMillis()

            // Check if we should evaluate
            if (now - lastEvaluationTime < EVALUATION_INTERVAL_MS) {
                return
            }
            lastEvaluationTime = now

            // Evaluate flows
            var evaluated = 0
            var transitioned = 0

            flowTable.evaluateEnforcement { flow ->
                // Only evaluate flows with enforcement state = NONE
                if (flow.enforcementState == EnforcementState.NONE) {
                    evaluated++

                    // Determine enforcement state
                    val newState = determineEnforcementState(flow)

                    // Apply state if changed
                    if (newState != EnforcementState.NONE) {
                        val wasApplied = applyEnforcementState(flow, newState)
                        if (wasApplied) {
                            transitioned++

                            when (newState) {
                                EnforcementState.ALLOW_READY -> allowReadyCount++
                                EnforcementState.BLOCK_READY -> blockReadyCount++
                                else -> {}
                            }
                        }
                    }
                }
            }

            totalEvaluations += evaluated

            if (transitioned > 0) {
                Log.d(TAG, "Enforcement evaluation: evaluated=$evaluated, transitioned=$transitioned, " +
                        "allow_ready=$allowReadyCount, block_ready=$blockReadyCount")
            }

        } catch (e: Exception) {
            // Swallow errors - never break VPN
            Log.w(TAG, "Error in enforcement evaluation: ${e.message}")
        }
    }

    /**
     * Determine enforcement state for a flow.
     * Pure function based on flow metadata.
     * Fail-open: uncertainty → NONE.
     */
    private fun determineEnforcementState(flow: FlowEntry): EnforcementState {
        return synchronized(flow) {
            // Already has enforcement state? Don't change (monotonic)
            if (flow.enforcementState != EnforcementState.NONE) {
                return@synchronized flow.enforcementState
            }

            // Check decision
            when (flow.decision) {
                FlowDecision.ALLOW -> {
                    // ALLOW decisions can immediately be marked ready
                    EnforcementState.ALLOW_READY
                }

                FlowDecision.BLOCK -> {
                    // BLOCK decisions require confidence checks
                    if (passesConfidenceChecks(flow)) {
                        EnforcementState.BLOCK_READY
                    } else {
                        // Not confident enough yet - remain NONE
                        EnforcementState.NONE
                    }
                }

                FlowDecision.UNDECIDED -> {
                    // No decision yet - cannot enforce
                    EnforcementState.NONE
                }
            }
        }
    }

    /**
     * Confidence checks for BLOCK enforcement.
     * Ensures blocking is safe and intentional.
     * Fail-open: if unsure, return false.
     */
    private fun passesConfidenceChecks(flow: FlowEntry): Boolean {
        try {
            // Check 1: Flow must be old enough (avoid blocking initial packets)
            if (flow.getAge() < MIN_FLOW_AGE_FOR_BLOCK_MS) {
                return false
            }

            // Check 2: UID must be known OR rule explicitly allows blocking unknown UIDs
            // Phase 7: For now, require UID to be known for BLOCK
            if (flow.uid == UID_UNKNOWN) {
                // Fail-open: don't block if UID unknown
                return false
            }

            // Check 3: Decision must be stable (not just set)
            // Phase 7: Assume decision is stable if set

            // All checks passed
            return true

        } catch (e: Exception) {
            // Error in confidence check - fail-open
            Log.w(TAG, "Error in confidence check: ${e.message}")
            return false
        }
    }

    /**
     * Apply enforcement state to flow (metadata only).
     * Monotonic - only transitions from NONE.
     *
     * @param flow FlowEntry to update
     * @param state EnforcementState to apply
     * @return true if state was applied, false if already set
     */
    private fun applyEnforcementState(flow: FlowEntry, state: EnforcementState): Boolean {
        synchronized(flow) {
            // Only apply if currently NONE (monotonic)
            if (flow.enforcementState == EnforcementState.NONE && state != EnforcementState.NONE) {
                flow.enforcementState = state

                // Debug logging
                Log.d(TAG, "Enforcement state applied: ${flow.flowKey} → $state")

                return true
            }
            return false
        }
    }

    /**
     * Get evaluation statistics.
     */
    fun getStatistics(): Triple<Long, Long, Long> {
        return Triple(totalEvaluations, allowReadyCount, blockReadyCount)
    }
}

