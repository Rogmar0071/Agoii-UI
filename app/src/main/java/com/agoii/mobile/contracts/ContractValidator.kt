package com.agoii.mobile.contracts

// ─── Contract Completeness Validator ─────────────────────────────────────────

/**
 * ContractValidator — verifies structural completeness of a [ContractDefinition].
 *
 * Scope of responsibility (strictly bounded):
 *  - DOES: enforce that all mandatory fields are present and non-empty.
 *  - DOES NOT: validate execution semantics, business rules, or ledger state.
 *
 * Completeness rules (applied in order; all failures are collected):
 *  1. [ContractDefinition.objective]          must be non-blank.
 *  2. [ContractDefinition.scope]              must be non-empty; each entry must be non-blank.
 *  3. [ContractDefinition.expectedOutput]     must be non-blank.
 *  4. [ContractDefinition.completionCriteria] must be non-empty; each entry must be non-blank.
 *  5. [ContractDefinition.constraints]        entries, when present, must be non-blank.
 *
 * Rules:
 *  - Pure function: stateless, no side effects.
 *  - All violations are collected and returned together; validation never short-circuits.
 *  - Equal inputs always produce equal outputs.
 */
class ContractValidator {

    /**
     * Validate the completeness of [definition].
     *
     * @param definition The [ContractDefinition] to inspect.
     * @return [ContractDefinitionValidationResult] with [ContractDefinitionValidationResult.complete]
     *         = true only when all rules pass.
     */
    fun validate(definition: ContractDefinition): ContractDefinitionValidationResult {
        val reasons = mutableListOf<String>()

        // Rule 1: objective must be non-blank
        if (definition.objective.isBlank()) {
            reasons += "objective must not be blank"
        }

        // Rule 2: scope must be non-empty; each entry non-blank
        if (definition.scope.isEmpty()) {
            reasons += "scope must contain at least one entry"
        } else {
            definition.scope.forEachIndexed { i, entry ->
                if (entry.isBlank()) reasons += "scope[$i] must not be blank"
            }
        }

        // Rule 3: expectedOutput must be non-blank
        if (definition.expectedOutput.isBlank()) {
            reasons += "expectedOutput must not be blank"
        }

        // Rule 4: completionCriteria must be non-empty; each entry non-blank
        if (definition.completionCriteria.isEmpty()) {
            reasons += "completionCriteria must contain at least one criterion"
        } else {
            definition.completionCriteria.forEachIndexed { i, criterion ->
                if (criterion.isBlank()) reasons += "completionCriteria[$i] must not be blank"
            }
        }

        // Rule 5: constraint entries, when present, must be non-blank
        definition.constraints.forEachIndexed { i, constraint ->
            if (constraint.isBlank()) reasons += "constraints[$i] must not be blank"
        }

        return ContractDefinitionValidationResult(
            complete = reasons.isEmpty(),
            reasons  = reasons
        )
    }
}
