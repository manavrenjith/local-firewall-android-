package com.example.aegis.vpn.decision

import com.example.aegis.vpn.flow.FlowDecision
import com.example.aegis.vpn.flow.FlowEntry

/**
 * DecisionRule - Phase 6: Decision Engine
 *
 * Interface for flow decision rules.
 * Rules are pure functions that evaluate flow metadata.
 */
interface DecisionRule {
    /**
     * Evaluate a flow and return a decision.
     * Pure function - no side effects.
     *
     * @param flow FlowEntry to evaluate
     * @return Decision (ALLOW, BLOCK, or UNDECIDED)
     */
    fun evaluate(flow: FlowEntry): FlowDecision
}

/**
 * DefaultAllowRule - Phase 6 default implementation.
 *
 * Simple rule that allows all flows with known UIDs.
 * Leaves unknown UIDs as UNDECIDED.
 *
 * This is a placeholder for Phase 6.
 * Phase 7+ will introduce more sophisticated rules.
 */
class DefaultAllowRule : DecisionRule {

    companion object {
        private const val UID_UNKNOWN = -1
    }

    override fun evaluate(flow: FlowEntry): FlowDecision {
        return synchronized(flow) {
            // If UID is known, allow
            if (flow.uid != UID_UNKNOWN) {
                FlowDecision.ALLOW
            } else {
                // UID unknown - leave undecided
                FlowDecision.UNDECIDED
            }
        }
    }
}

/**
 * Example: BlockUnknownUidRule - Not active by default.
 *
 * Demonstrates a blocking rule.
 * Would block flows with unknown UIDs.
 *
 * NOT ENABLED in Phase 6 - just an example.
 */
class BlockUnknownUidRule : DecisionRule {

    companion object {
        private const val UID_UNKNOWN = -1
    }

    override fun evaluate(flow: FlowEntry): FlowDecision {
        return synchronized(flow) {
            if (flow.uid == UID_UNKNOWN) {
                FlowDecision.BLOCK
            } else {
                FlowDecision.UNDECIDED
            }
        }
    }
}

/**
 * Example: PortBasedRule - Not active by default.
 *
 * Demonstrates port-based decision making.
 * Could allow/block based on destination port.
 *
 * NOT ENABLED in Phase 6 - just an example.
 */
class PortBasedRule(
    private val allowedPorts: Set<Int>,
    private val blockedPorts: Set<Int>
) : DecisionRule {

    override fun evaluate(flow: FlowEntry): FlowDecision {
        val destPort = flow.flowKey.destinationPort

        return when {
            destPort in blockedPorts -> FlowDecision.BLOCK
            destPort in allowedPorts -> FlowDecision.ALLOW
            else -> FlowDecision.UNDECIDED
        }
    }
}

