package com.agoii.mobile.contracts

// ─── L2 — Deterministic Contract Engine ──────────────────────────────────────

/**
 * ContractEngine — the deterministic contract evaluation engine.
 *
 * Chains the five contract evaluation stages in strict order:
 *   Step 1: [SurfaceMapper]        — intent → mutation footprint
 *   Step 2: [FailureMapper]        — surface → failure landscape
 *   Step 3: [ExecutionDecomposer]  — surface → execution plan
 *   Step 4: [ConstraintEnforcer]   — plan + intent → constraint result
 *   Step 5: [DeterministicDeriver] — all artifacts → final derivation
 *
 * Rules:
 *  - Each step is executed exactly once per [evaluate] call.
 *  - No stage is skipped regardless of intermediate results; the full trace is
 *    always produced for auditability.
 *  - Output is deterministic: equal inputs always produce equal outputs.
 *  - The engine is stateless: every [evaluate] call is independent.
 */
class ContractEngine(
    private val surfaceMapper:        SurfaceMapper        = SurfaceMapper(),
    private val failureMapper:        FailureMapper        = FailureMapper(),
    private val executionDecomposer:  ExecutionDecomposer  = ExecutionDecomposer(),
    private val constraintEnforcer:   ConstraintEnforcer   = ConstraintEnforcer(),
    private val deterministicDeriver: DeterministicDeriver = DeterministicDeriver()
) {

    /**
     * Evaluate a [ContractIntent] through the full deterministic engine.
     *
     * @param intent The raw intent to evaluate.
     * @return [ContractDerivation] with the terminal outcome, full trace, and all
     *         intermediate artifacts (surface, failureMap, executionPlan, constraints).
     */
    fun evaluate(intent: ContractIntent): ContractDerivation {
        val surface       = surfaceMapper.map(intent)
        val failureMap    = failureMapper.map(intent, surface)
        val executionPlan = executionDecomposer.decompose(surface)
        val constraints   = constraintEnforcer.enforce(intent, executionPlan)
        return deterministicDeriver.derive(surface, failureMap, executionPlan, constraints)
    }
}
