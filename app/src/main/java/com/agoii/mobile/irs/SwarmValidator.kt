package com.agoii.mobile.irs

/**
 * SwarmValidator — Step 4 of the IRS execution graph.
 *
 * Single responsibility: evaluate multi-agent consensus on intent validity.
 *
 * Rules:
 *  - [SwarmConfig.agentCount] must be ≥ 2 (enforced at call time).
 *  - Each agent applies a deterministic check; evaluation is reproducible.
 *  - Consensus is derived from [SwarmConfig.consensusRule]:
 *      UNANIMOUS → zero conflicts allowed.
 *      MAJORITY  → fewer than half of agent slots may produce conflicts.
 *      WEIGHTED  → each conflict carries weight 2; total weighted failures
 *                  must be less than agentCount to pass.
 *  - Does NOT call any other IRS module.
 *
 * Orchestration contract:
 *  - If [SwarmResult.consistent] is false the orchestrator must halt with UNSTABLE.
 */
class SwarmValidator {

    /**
     * Run the swarm over [intentData] with the supplied [config].
     *
     * Agents rotate over four deterministic checks (one per mandatory field).
     * Each check passes when the field value is non-blank AND has ≥ 1 EvidenceRef.
     */
    fun validate(intentData: IntentData, config: SwarmConfig): SwarmResult {
        require(config.agentCount >= 2) {
            "agentCount must be ≥ 2, got ${config.agentCount}"
        }

        val checks: List<() -> Pair<String, String?>> = listOf(
            { checkField("objective",   intentData.objective)   },
            { checkField("constraints", intentData.constraints) },
            { checkField("environment", intentData.environment) },
            { checkField("resources",   intentData.resources)   }
        )

        val outputs   = mutableListOf<String>()
        val conflicts = mutableListOf<String>()

        for (i in 0 until config.agentCount) {
            val (output, conflict) = checks[i % checks.size]()
            outputs.add("agent-${i + 1}: $output")
            if (conflict != null) conflicts.add(conflict)
        }

        val consistent = evaluateConsensus(config.agentCount, conflicts.size, config.consensusRule)
        return SwarmResult(consistent = consistent, conflicts = conflicts, agentOutputs = outputs)
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private fun checkField(name: String, field: IntentField): Pair<String, String?> {
        val valueOk    = field.value.isNotBlank()
        val evidenceOk = field.evidence.isNotEmpty()
        return if (valueOk && evidenceOk) {
            "PASS: $name is present and evidence-backed" to null
        } else {
            val reason = when {
                !valueOk && !evidenceOk -> "$name value is empty and has no evidence"
                !valueOk                -> "$name value is empty"
                else                    -> "$name has no evidence"
            }
            "FAIL: $reason" to reason
        }
    }

    private fun evaluateConsensus(
        agentCount:    Int,
        conflictCount: Int,
        rule:          ConsensusRule
    ): Boolean = when (rule) {
        ConsensusRule.UNANIMOUS -> conflictCount == 0
        ConsensusRule.MAJORITY  -> conflictCount * 2 < agentCount
        ConsensusRule.WEIGHTED  -> conflictCount * 2 < agentCount
    }
}
