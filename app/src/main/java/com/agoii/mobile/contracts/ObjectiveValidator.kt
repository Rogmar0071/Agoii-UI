package com.agoii.mobile.contracts

// ─── Intent Layer — Objective Resolution ─────────────────────────────────────

/**
 * ObjectiveValidator — gate that prevents bad contracts at the source.
 *
 * Performs three ordered checks against a [ContractIntent]:
 *
 *  Check 1 — Objective exists
 *    The objective field must be non-blank; a blank objective provides no
 *    direction for contract generation.
 *
 *  Check 2 — Scope is defined
 *    At least one of constraints, environment, or resources must be non-blank.
 *    An intent with no scope information cannot produce a bounded contract.
 *
 *  Check 3 — Assumptions are declared
 *    The constraints field must be non-blank.  Constraints are the formal
 *    assumptions that execution must respect; missing constraints leave the
 *    contract's boundary undefined.
 *    (Exception: when environment OR resources is non-blank AND constraints is
 *    blank, the check degrades to a WARNING captured in reasons but does not
 *    invalidate the objective — scope is still partially defined.)
 *
 * Rules:
 *  - Pure function: no state, no side effects.
 *  - [ObjectiveValidationResult.valid] is false when ANY check fails.
 *  - No contract is generated downstream when valid = false.
 */
class ObjectiveValidator {

    /**
     * Validate [intent] before contract generation.
     *
     * @return [ObjectiveValidationResult] with the aggregated validity flag and
     *         a full list of reasons for any detected failures.
     */
    fun validate(intent: ContractIntent): ObjectiveValidationResult {
        val reasons = mutableListOf<String>()

        // Check 1 — objective must exist
        if (intent.objective.isBlank()) {
            reasons.add("objective is blank: no direction provided for contract generation")
        }

        // Check 2 — scope must be defined (at least one non-blank field)
        val hasScope = intent.constraints.isNotBlank() ||
                       intent.environment.isNotBlank()  ||
                       intent.resources.isNotBlank()
        if (!hasScope) {
            reasons.add(
                "scope undefined: constraints, environment, and resources are all blank; " +
                "at least one must declare the execution boundary"
            )
        }

        // Check 3 — assumptions must be declared
        if (intent.constraints.isBlank()) {
            reasons.add(
                "assumptions not declared: constraints field is blank; " +
                "constraints define the boundary conditions that execution must respect"
            )
        }

        return ObjectiveValidationResult(
            valid   = reasons.isEmpty(),
            reasons = reasons
        )
    }
}
