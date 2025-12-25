package com.example.aegis.vpn.decision

import android.util.Log
import com.example.aegis.vpn.flow.FlowDecision
import com.example.aegis.vpn.flow.FlowTable

/**
 * DecisionEvaluator - Phase 6: Decision Engine
 *
 * Coordinates decision evaluation for flows.
 * Wraps DecisionEngine and handles periodic evaluation.
 *
 * Responsibilities:
 * - Periodically evaluate undecided flows
 * - Apply decisions (metadata only)
 * - Never enforce decisions
 *
 * Non-responsibilities (Phase 6):
 * - No packet blocking
 * - No packet forwarding
 * - No socket operations
 * - No enforcement
 */
class DecisionEvaluator(private val flowTable: FlowTable) {

    companion object {
        private const val TAG = "DecisionEvaluator"

        // Evaluation timing
        private const val EVALUATION_INTERVAL_MS = 15000L  // 15 seconds
    }

    private val decisionEngine = DecisionEngine()

    // Last evaluation time
    private var lastEvaluationTime = 0L

    // Statistics
    private var totalEvaluations = 0L
    private var decisionsApplied = 0L

    /**
     * Evaluate flows and apply decisions.
     * Called periodically from existing timing mechanisms.
     * Non-blocking and best-effort.
     */
    fun evaluateFlows() {
        try {
            val now = System.currentTimeMillis()

            // Check if we should evaluate
            if (now - lastEvaluationTime < EVALUATION_INTERVAL_MS) {
                return
            }
            lastEvaluationTime = now

            // Evaluate flows
            var evaluated = 0
            var applied = 0

            flowTable.evaluateDecisions { flow ->
                // Only evaluate flows that are UNDECIDED
                if (flow.decision == FlowDecision.UNDECIDED) {
                    evaluated++

                    // Evaluate flow
                    val decision = decisionEngine.evaluateFlow(flow)

                    // Apply decision if not UNDECIDED
                    if (decision != FlowDecision.UNDECIDED) {
                        val wasApplied = decisionEngine.applyDecision(flow, decision)
                        if (wasApplied) {
                            applied++
                            decisionsApplied++
                        }
                    }
                }
            }

            totalEvaluations += evaluated

            if (applied > 0) {
                Log.d(TAG, "Decision evaluation: evaluated=$evaluated, applied=$applied, " +
                        "total_decisions=$decisionsApplied")
            }

        } catch (e: Exception) {
            // Swallow errors - never break VPN
            Log.w(TAG, "Error in decision evaluation: ${e.message}")
        }
    }

    /**
     * Get evaluation statistics.
     */
    fun getStatistics(): Pair<Long, Long> {
        return Pair(totalEvaluations, decisionsApplied)
    }
}

