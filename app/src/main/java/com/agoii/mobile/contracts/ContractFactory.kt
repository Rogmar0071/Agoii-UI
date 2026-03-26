package com.agoii.mobile.contracts

import java.util.UUID

// ─── Contract Factory ─────────────────────────────────────────────────────────

/**
 * ContractFactory — the sole authority for materialising [Contract] instances.
 *
 * No module outside this factory may construct a [Contract] directly.  All
 * creation flows through [build], which:
 *   1. Runs [ContractValidator] to verify the [ContractDefinition] is complete.
 *   2. Assigns a unique, collision-resistant [Contract.id].
 *   3. Returns [ContractBuildResult] conveying either the ready [Contract] or the
 *      validation failures that prevented construction.
 *
 * Rules:
 *  - Stateless: every [build] call is independent.
 *  - Deterministic given the same [ContractDefinition] content (ids are random UUIDs).
 *  - A [Contract] is never returned when validation has failed.
 */
class ContractFactory(
    private val validator: ContractValidator = ContractValidator()
) {

    /**
     * Build a [Contract] from the given [definition].
     *
     * @param definition The [ContractDefinition] produced by a typed builder.
     * @return [ContractBuildResult] — either [ContractBuildResult.Success] containing
     *         the ready [Contract], or [ContractBuildResult.Failure] listing every
     *         completeness violation.
     */
    fun build(definition: ContractDefinition): ContractBuildResult {
        val validation = validator.validate(definition)
        if (!validation.complete) {
            return ContractBuildResult.Failure(reasons = validation.reasons)
        }
        val contract = Contract(
            id                 = UUID.randomUUID().toString(),
            type               = definition.type,
            objective          = definition.objective,
            scope              = definition.scope,
            constraints        = definition.constraints,
            expectedOutput     = definition.expectedOutput,
            completionCriteria = definition.completionCriteria,
            metadata           = definition.metadata
        )
        return ContractBuildResult.Success(contract = contract)
    }
}

// ─── Build Result ─────────────────────────────────────────────────────────────

/**
 * Terminal result of a [ContractFactory.build] invocation.
 *
 * Callers must handle both branches; accessing [Success.contract] is safe only
 * after verifying [Success] via an `is` check or exhaustive `when`.
 */
sealed class ContractBuildResult {

    /**
     * The [ContractDefinition] was complete and a [Contract] was produced.
     *
     * @property contract The fully initialised, immutable contract.
     */
    data class Success(val contract: Contract) : ContractBuildResult()

    /**
     * The [ContractDefinition] failed completeness validation; no contract was built.
     *
     * @property reasons Ordered list of completeness violations.
     */
    data class Failure(val reasons: List<String>) : ContractBuildResult()
}
