package com.example.aegis.vpn.decision

import com.example.aegis.vpn.flow.FlowDecision
import com.example.aegis.vpn.flow.FlowEntry

/**
 * DecisionEngine - Phase 6: Decision Engine (Decision-Only, No Enforcement)
 *
 * Pure decision engine that evaluates flows and produces ALLOW/BLOCK/UNDECIDED decisions.
 * Decisions are metadata only - no enforcement, no traffic modification.
 *
 * Responsibilities:
 * - Evaluate flows against rules
 * - Produce decision (ALLOW, BLOCK, UNDECIDED)
 * - Store decision in FlowEntry
 * - Monotonic decisions (never downgrade)
 *
 * Non-responsibilities (Phase 6):
 * - No packet blocking
 * - No packet forwarding
 * - No socket operations
 * - No flow termination
 * - No enforcement
 */
class DecisionEngine {

    companion object {
        private const val TAG = "DecisionEngine"
    }

    // Collection of decision rules
    private val rules = listOf<DecisionRule>(
        DefaultAllowRule()
    )

    /**
     * Evaluate a flow and produce a decision.
     * Pure function - no side effects.
     * Monotonic - once decided, never changes.
     *
     * @param flow FlowEntry to evaluate
     * @return Decision (ALLOW, BLOCK, or UNDECIDED)
     */
    fun evaluateFlow(flow: FlowEntry): FlowDecision {
        // Check if already decided (monotonic)
        synchronized(flow) {
            if (flow.decision != FlowDecision.UNDECIDED) {
                // Already decided - never change
                return flow.decision
            }
        }

        // Flow is UNDECIDED - evaluate rules
        try {
            for (rule in rules) {
                val decision = rule.evaluate(flow)

                // If rule produced a decision, use it
                if (decision != FlowDecision.UNDECIDED) {
                    return decision
                }
            }

            // No rule produced a decision
            return FlowDecision.UNDECIDED

        } catch (e: Exception) {
            // Fail-open: errors result in UNDECIDED
            android.util.Log.w(TAG, "Error evaluating flow: ${e.message}")
            return FlowDecision.UNDECIDED
        }
    }

    /**
     * Apply decision to flow (metadata only).
     * Monotonic - only transitions from UNDECIDED.
     *
     * @param flow FlowEntry to update
     * @param decision Decision to apply
     * @return true if decision was applied, false if already decided
     */
    fun applyDecision(flow: FlowEntry, decision: FlowDecision): Boolean {
        synchronized(flow) {
            // Only apply if currently UNDECIDED (monotonic)
            if (flow.decision == FlowDecision.UNDECIDED && decision != FlowDecision.UNDECIDED) {
                flow.decision = decision

                // Optional debug logging (rate-limited elsewhere)
                android.util.Log.d(TAG, "Decision applied: ${flow.flowKey} → $decision")

                return true
            }
            return false
        }
    }
}

