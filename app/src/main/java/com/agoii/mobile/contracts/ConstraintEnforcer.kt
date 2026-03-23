package com.agoii.mobile.contracts

// ─── Step 4 — Constraint Enforcement ─────────────────────────────────────────

/**
 * ConstraintEnforcer — Step 4 of the Contract Engine.
 *
 * Validates each [ExecutionStep] in an [ExecutionPlan] against the constraints
 * declared in the [ContractIntent].
 *
 * Enforcement rules (evaluated in order for every step):
 *  1. Per-step load limit — step.load must not exceed [MAX_STEP_LOAD].
 *  2. "no ui" constraint  — when intent.constraints contains "no ui", UI steps are rejected.
 *  3. "read-only" constraint — when intent.constraints contains "read-only",
 *                              CORE steps are rejected (CORE writes the event ledger).
 *
 * Rules:
 *  - Pure function: no state, no side effects.
 *  - [ConstraintResult.passed] is true only when [ConstraintResult.violations] is empty.
 */
class ConstraintEnforcer {

    /**
     * Enforce all active constraints against each step in [plan].
     *
     * @param intent The raw contract intent whose [ContractIntent.constraints] field is inspected.
     * @param plan   The execution plan produced by [ExecutionDecomposer].
     * @return [ConstraintResult] with all detected violations and the [ConstraintResult.passed] flag.
     */
    fun enforce(intent: ContractIntent, plan: ExecutionPlan): ConstraintResult {
        val violations  = mutableListOf<ConstraintViolation>()
        val constraints = intent.constraints.lowercase()

        for (step in plan.steps) {

            // Rule 1 — per-step load limit
            if (step.load > MAX_STEP_LOAD) {
                violations.add(
                    ConstraintViolation(
                        step       = step,
                        constraint = "MAX_STEP_LOAD=$MAX_STEP_LOAD",
                        reason     = "step ${step.position} (${step.module}) has load ${step.load} " +
                                     "exceeding the per-step limit"
                    )
                )
            }

            // Rule 2 — explicit UI exclusion
            if (step.module == ContractModule.UI && constraints.contains("no ui")) {
                violations.add(
                    ConstraintViolation(
                        step       = step,
                        constraint = "no ui",
                        reason     = "intent constraints prohibit UI layer mutations"
                    )
                )
            }

            // Rule 3 — read-only constraint forbids CORE steps
            if (step.module == ContractModule.CORE && constraints.contains("read-only")) {
                violations.add(
                    ConstraintViolation(
                        step       = step,
                        constraint = "read-only",
                        reason     = "intent is declared read-only but CORE step would write to the event ledger"
                    )
                )
            }
        }

        return ConstraintResult(
            passed     = violations.isEmpty(),
            violations = violations
        )
    }

    companion object {
        /** Maximum allowed load per individual execution step. */
        const val MAX_STEP_LOAD = 3
    }
}
