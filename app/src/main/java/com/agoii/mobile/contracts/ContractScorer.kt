package com.agoii.mobile.contracts

// ─── Mathematical Scoring Layer ───────────────────────────────────────────────

/**
 * ContractScorer — quantifies every contract with three orthogonal metrics.
 *
 * ── Execution Load (EL) ──────────────────────────────────────────────────────
 *   EL = surfaceWeight + executionCount + (2 × conditionCount)
 *   Where:
 *     surfaceWeight  = [ContractSurface.totalWeight]
 *     executionCount = [ExecutionPlan.steps].size
 *     conditionCount = [ConstraintResult.violations].size
 *       (each violation is a conditional branch that was evaluated)
 *
 * ── Risk Score (RS) ──────────────────────────────────────────────────────────
 *   RS = Σ (severity × likelihood) over all failures in [FailureMap].
 *   Severity/likelihood table (1–3 each):
 *     LOAD_EXCEEDED      → severity=3, likelihood=3
 *     DEPENDENCY_MISSING → severity=3, likelihood=1
 *     CONSTRAINT_VIOLATED→ severity=2, likelihood=2
 *     MISSING_RESOURCE   → severity=1, likelihood=2
 *
 * ── Confidence Index (CCF) ───────────────────────────────────────────────────
 *   CCF = Σ of 5 dimension scores (0–3 each), max = 15.
 *   Dimensions:
 *     D1 Mutation control   — fewer modules = better containment.
 *     D2 Scope containment  — longer/richer objective = more specific scope.
 *     D3 Hidden side effects— fewer failures = fewer unexpected consequences.
 *     D4 Lifecycle integrity— fewer constraint violations = cleaner flow.
 *     D5 Output determinism — APPROVED outcome = deterministic output guaranteed.
 *
 * Rules:
 *  - Pure function: no state, no side effects.
 */
class ContractScorer {

    /**
     * Compute the [ContractScore] for a finalized [ContractDerivation].
     *
     * @param derivation The derivation produced by [ContractEngine.evaluate].
     * @return [ContractScore] with EL, RS, and CCF pre-computed.
     */
    fun score(derivation: ContractDerivation): ContractScore {
        val el  = computeExecutionLoad(derivation)
        val rs  = computeRiskScore(derivation.failureMap)
        val ccf = computeConfidenceIndex(derivation)
        return ContractScore(
            executionLoad   = el,
            riskScore       = rs,
            confidenceIndex = ccf
        )
    }

    // ── EL ───────────────────────────────────────────────────────────────────

    private fun computeExecutionLoad(d: ContractDerivation): Int {
        val surfaceWeight  = d.surface.totalWeight
        val executionCount = d.executionPlan.steps.size
        val conditionCount = d.constraints.violations.size
        return surfaceWeight + executionCount + (2 * conditionCount)
    }

    // ── Risk Score ────────────────────────────────────────────────────────────

    private fun computeRiskScore(failureMap: FailureMap): Int =
        failureMap.failures.sumOf { failure ->
            val (severity, likelihood) = riskTable(failure.type)
            severity * likelihood
        }

    private fun riskTable(type: ContractFailureType): Pair<Int, Int> = when (type) {
        ContractFailureType.LOAD_EXCEEDED       -> Pair(3, 3) // high severity × high likelihood
        ContractFailureType.DEPENDENCY_MISSING  -> Pair(3, 1) // high severity × low likelihood
        ContractFailureType.CONSTRAINT_VIOLATED -> Pair(2, 2) // medium × medium
        ContractFailureType.MISSING_RESOURCE    -> Pair(1, 2) // low × medium
    }

    // ── CCF (Confidence Index) ────────────────────────────────────────────────

    private fun computeConfidenceIndex(d: ContractDerivation): Int {
        val d1 = scoreMutationControl(d.surface.modules.size)
        val d2 = scoreScopeContainment(d)
        val d3 = scoreHiddenSideEffects(d.failureMap.failures.size, d.failureMap.hasCritical)
        val d4 = scoreLifecycleIntegrity(d.constraints.violations.size)
        val d5 = scoreOutputDeterminism(d.outcome)
        return (d1 + d2 + d3 + d4 + d5).coerceIn(0, 15)
    }

    /** D1: Mutation control — fewer surface modules = tighter containment. */
    private fun scoreMutationControl(moduleCount: Int): Int = when {
        moduleCount == 1 -> 3
        moduleCount == 2 -> 2
        moduleCount == 3 -> 1
        else             -> 0
    }

    /** D2: Scope containment — richer objective = more precisely scoped contract. */
    private fun scoreScopeContainment(d: ContractDerivation): Int {
        // Approximate scope specificity from total module count and surface weight.
        // A heavy, multi-module surface has better-defined scope than a single-module one.
        val moduleCount = d.surface.modules.size
        return when {
            moduleCount >= 4 -> 3
            moduleCount == 3 -> 2
            moduleCount == 2 -> 1
            else             -> 0 // single-module surfaces have implicit scope only
        }
    }

    /** D3: Hidden side effects — fewer and less-critical failures = fewer surprises. */
    private fun scoreHiddenSideEffects(failureCount: Int, hasCritical: Boolean): Int = when {
        failureCount == 0              -> 3
        !hasCritical                   -> 2
        failureCount == 1 && hasCritical -> 1
        else                           -> 0
    }

    /** D4: Lifecycle integrity — fewer constraint violations = cleaner execution flow. */
    private fun scoreLifecycleIntegrity(violationCount: Int): Int = when {
        violationCount == 0 -> 3
        violationCount == 1 -> 2
        violationCount == 2 -> 1
        else                -> 0
    }

    /** D5: Output determinism — APPROVED outcome guarantees deterministic output. */
    private fun scoreOutputDeterminism(outcome: ContractOutcome): Int = when (outcome) {
        ContractOutcome.APPROVED -> 3
        ContractOutcome.REJECTED -> 0
    }
}
