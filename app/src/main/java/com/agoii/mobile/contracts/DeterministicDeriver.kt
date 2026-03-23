package com.agoii.mobile.contracts

// ─── Step 5 — Deterministic Derivation ───────────────────────────────────────

/**
 * DeterministicDeriver — Step 5 of the Contract Engine.
 *
 * Aggregates the outputs of Steps 1–4 into a single [ContractDerivation].
 *
 * Decision protocol (boolean rule table; no numeric thresholds):
 *  - REJECTED when: [FailureMap.hasCritical] OR NOT [ConstraintResult.passed]
 *  - APPROVED otherwise
 *
 * All reasoning is captured in [ContractDerivation.trace] for complete auditability.
 * The trace covers every step: surface, failures, execution plan, constraints, and outcome.
 *
 * Rules:
 *  - Pure function: no state, no side effects.
 *  - Equal inputs always produce equal outputs.
 */
class DeterministicDeriver {

    /**
     * Derive the [ContractDerivation] from the four stage outputs.
     *
     * @param surface       Mutation footprint from Step 1.
     * @param failureMap    Failure landscape from Step 2.
     * @param executionPlan Decomposed steps from Step 3.
     * @param constraints   Constraint enforcement result from Step 4.
     * @return [ContractDerivation] with outcome, full trace, and all intermediate artifacts.
     */
    fun derive(
        surface:       ContractSurface,
        failureMap:    FailureMap,
        executionPlan: ExecutionPlan,
        constraints:   ConstraintResult
    ): ContractDerivation {

        val outcome = if (failureMap.hasCritical || !constraints.passed) {
            ContractOutcome.REJECTED
        } else {
            ContractOutcome.APPROVED
        }

        val trace = buildList {
            add("surface: ${surface.modules.size} module(s), totalWeight=${surface.totalWeight}")
            surface.modules.forEach { m ->
                add("  module[${m.module}]: ${m.reason}")
            }
            add("failures: ${failureMap.failures.size} detected, hasCritical=${failureMap.hasCritical}")
            failureMap.failures.forEach { f ->
                add("  failure[${f.module}/${f.type}]: ${f.description} (critical=${f.critical})")
            }
            add("execution: ${executionPlan.steps.size} step(s), totalLoad=${executionPlan.totalLoad}")
            executionPlan.steps.forEach { s ->
                add("  step[${s.position}/${s.module}]: load=${s.load}")
            }
            add("constraints: passed=${constraints.passed}, violations=${constraints.violations.size}")
            constraints.violations.forEach { v ->
                add("  violation[${v.step.module}]: ${v.reason}")
            }
            add("outcome: $outcome")
        }

        return ContractDerivation(
            outcome       = outcome,
            surface       = surface,
            failureMap    = failureMap,
            executionPlan = executionPlan,
            constraints   = constraints,
            trace         = trace
        )
    }
}
