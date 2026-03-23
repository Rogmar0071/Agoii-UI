package com.agoii.mobile.contracts

// ─── Full System Orchestrator ─────────────────────────────────────────────────

/**
 * ContractSystemOrchestrator — the complete objective-to-execution pipeline.
 *
 * Execution flow:
 *
 *   Step 0: [ObjectiveValidator]    — validate objective (VALID / INVALID)
 *              → if INVALID → halt; return ContractSystemResult with readyForExecution=false
 *
 *   Step 1–5: [ContractEngine]      — build full contract structure
 *              → if REJECTED → return result with readyForExecution=false
 *
 *   Step 6:   [TraceabilityEnforcer]— verify every step maps back to intent
 *
 *   Step 7:   [ContractScorer]      — compute EL + RS + CCF
 *
 *   Step 8:   [ContractClassifier]  — classify as LOW / MEDIUM / HIGH
 *
 *   Step 9:   [AgentMatcher]        — evaluate ACCEPT / ADAPT / REJECT
 *              → if REJECT → return result with readyForExecution=false
 *
 *   Step 10:  [ContractAdapter]     — adapt contract structure (only when ADAPT)
 *
 *   Terminal: [ContractSystemResult] with readyForExecution=true
 *
 * Non-negotiable principles (enforced here):
 *  1. Objective is the first-class authority — no contract is built before validation passes.
 *  2. Contracts are derived, not written — engine constructs from intent only.
 *  3. Every contract is measured before execution — scoring always runs.
 *  4. Every contract is matched to an agent before execution — matching always runs.
 *  5. No mutation to core/governance/orchestration — only contracts package is used.
 *
 * Rules:
 *  - Each step is executed exactly once per [evaluate] call.
 *  - Equal inputs always produce equal outputs.
 *  - The orchestrator is stateless.
 */
class ContractSystemOrchestrator(
    private val objectiveValidator:   ObjectiveValidator   = ObjectiveValidator(),
    private val contractEngine:       ContractEngine       = ContractEngine(),
    private val traceabilityEnforcer: TraceabilityEnforcer = TraceabilityEnforcer(),
    private val contractScorer:       ContractScorer       = ContractScorer(),
    private val contractClassifier:   ContractClassifier   = ContractClassifier(),
    private val agentMatcher:         AgentMatcher         = AgentMatcher(),
    private val contractAdapter:      ContractAdapter      = ContractAdapter()
) {

    /**
     * Execute the full pipeline for [intent] against [agentProfile].
     *
     * @param intent       The raw contract intent to evaluate.
     * @param agentProfile The agent that will execute the contract.
     * @return [ContractSystemResult] capturing the outcome of every stage.
     */
    fun evaluate(intent: ContractIntent, agentProfile: AgentProfile): ContractSystemResult {

        // ── Step 0: Objective Validation ─────────────────────────────────────
        val objValidation = objectiveValidator.validate(intent)
        if (!objValidation.valid) {
            return ContractSystemResult(
                objectiveValidation = objValidation,
                scoredContract      = null,
                matchResult         = null,
                adaptedContract     = null,
                readyForExecution   = false
            )
        }

        // ── Steps 1–5: Contract Construction (existing engine) ────────────────
        val derivation = contractEngine.evaluate(intent)
        if (derivation.outcome == ContractOutcome.REJECTED) {
            return ContractSystemResult(
                objectiveValidation = objValidation,
                scoredContract      = null,
                matchResult         = null,
                adaptedContract     = null,
                readyForExecution   = false
            )
        }

        // ── Step 6: Traceability Enforcement ──────────────────────────────────
        val traceability = traceabilityEnforcer.enforce(intent, derivation.executionPlan)

        // ── Steps 7–8: Scoring + Classification ───────────────────────────────
        val score          = contractScorer.score(derivation)
        val classification = contractClassifier.classify(score)
        val scored         = ScoredContract(
            derivation     = derivation,
            score          = score,
            classification = classification,
            traceability   = traceability
        )

        // ── Step 9: Agent Matching ────────────────────────────────────────────
        val matchResult = agentMatcher.match(scored, agentProfile)

        // ── Step 10: Adaptation (only when ADAPT) ─────────────────────────────
        val adapted: AdaptedContract? = when (matchResult.decision) {
            AgentMatchDecision.ADAPT   -> contractAdapter.adapt(scored)
            AgentMatchDecision.ACCEPT,
            AgentMatchDecision.REJECT  -> null
        }

        val ready = matchResult.decision != AgentMatchDecision.REJECT

        return ContractSystemResult(
            objectiveValidation = objValidation,
            scoredContract      = scored,
            matchResult         = matchResult,
            adaptedContract     = adapted,
            readyForExecution   = ready
        )
    }
}
