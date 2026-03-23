package com.agoii.mobile.contracts

// ─── Agent Alignment Layer — Contract ↔ Agent Matching ───────────────────────

/**
 * AgentMatcher — evaluates whether a specific agent can execute a specific contract.
 *
 * Matching protocol (evaluated by contract classification):
 *
 *   LOW contract
 *     ACCEPT  → agent.constraintObedience ≥ 1 (any minimally capable agent)
 *     ADAPT   → otherwise
 *
 *   MEDIUM contract
 *     ACCEPT  → structuralAccuracy ≥ 2 AND driftTendency ≤ 1
 *     ADAPT   → capabilityScore ≥ 8 (partially capable; contract needs adjustment)
 *     REJECT  → capabilityScore < 8 (agent cannot safely handle this contract)
 *
 *   HIGH contract
 *     ACCEPT  → structuralAccuracy ≥ 3 AND driftTendency == 0 AND complexityHandling ≥ 2
 *     ADAPT   → capabilityScore ≥ 10 (high-capability agent but contract needs hardening)
 *     REJECT  → capabilityScore < 10 (agent fundamentally mismatched)
 *
 * Rules:
 *  - Pure function: no state, no side effects.
 *  - All reasoning is captured in [AgentMatchResult.reasons].
 */
class AgentMatcher {

    /**
     * Evaluate whether [agent] can execute [scoredContract].
     *
     * @param scoredContract The fully scored and classified contract.
     * @param agent          The agent capability profile.
     * @return [AgentMatchResult] with the decision and full trace.
     */
    fun match(scoredContract: ScoredContract, agent: AgentProfile): AgentMatchResult {
        val reasons   = mutableListOf<String>()
        val decision  = when (scoredContract.classification) {
            ContractClassification.LOW    -> matchLow(agent, reasons)
            ContractClassification.MEDIUM -> matchMedium(agent, reasons)
            ContractClassification.HIGH   -> matchHigh(agent, reasons)
        }

        return AgentMatchResult(
            decision       = decision,
            agentProfile   = agent,
            scoredContract = scoredContract,
            reasons        = reasons
        )
    }

    // ── matching rules ────────────────────────────────────────────────────────

    private fun matchLow(agent: AgentProfile, reasons: MutableList<String>): AgentMatchDecision {
        reasons.add("classification=LOW, EL is safe for standard execution")
        return if (agent.constraintObedience >= 1) {
            reasons.add("ACCEPT: constraintObedience=${agent.constraintObedience} ≥ 1")
            AgentMatchDecision.ACCEPT
        } else {
            reasons.add("ADAPT: constraintObedience=${agent.constraintObedience} < 1; " +
                        "contract needs explicit constraint annotation")
            AgentMatchDecision.ADAPT
        }
    }

    private fun matchMedium(agent: AgentProfile, reasons: MutableList<String>): AgentMatchDecision {
        reasons.add("classification=MEDIUM, requires controlled execution policy")
        return when {
            agent.structuralAccuracy >= 2 && agent.driftTendency <= 1 -> {
                reasons.add("ACCEPT: structuralAccuracy=${agent.structuralAccuracy} ≥ 2, " +
                            "driftTendency=${agent.driftTendency} ≤ 1")
                AgentMatchDecision.ACCEPT
            }
            agent.capabilityScore >= 8 -> {
                reasons.add("ADAPT: structuralAccuracy=${agent.structuralAccuracy} or " +
                            "driftTendency=${agent.driftTendency} insufficient, " +
                            "but capabilityScore=${agent.capabilityScore} ≥ 8")
                AgentMatchDecision.ADAPT
            }
            else -> {
                reasons.add("REJECT: structuralAccuracy=${agent.structuralAccuracy} < 2 or " +
                            "driftTendency=${agent.driftTendency} > 1, " +
                            "and capabilityScore=${agent.capabilityScore} < 8")
                AgentMatchDecision.REJECT
            }
        }
    }

    private fun matchHigh(agent: AgentProfile, reasons: MutableList<String>): AgentMatchDecision {
        reasons.add("classification=HIGH, requires high-fidelity agent")
        return when {
            agent.structuralAccuracy >= 3 && agent.driftTendency == 0 &&
                    agent.complexityHandling >= 2 -> {
                reasons.add("ACCEPT: structuralAccuracy=${agent.structuralAccuracy} ≥ 3, " +
                            "driftTendency=0, complexityHandling=${agent.complexityHandling} ≥ 2")
                AgentMatchDecision.ACCEPT
            }
            agent.capabilityScore >= 10 -> {
                reasons.add("ADAPT: high contract but capabilityScore=${agent.capabilityScore} ≥ 10; " +
                            "contract needs structural hardening for this agent")
                AgentMatchDecision.ADAPT
            }
            else -> {
                reasons.add("REJECT: capabilityScore=${agent.capabilityScore} < 10; " +
                            "agent fundamentally mismatched to HIGH contract")
                AgentMatchDecision.REJECT
            }
        }
    }
}
