package com.agoii.mobile.contracts

// AGOII CONTRACT — UNIVERSAL CONTRACT VALIDATOR (UCS-1)
// SURFACE 2: CONTRACT VALIDATION (STRUCTURAL + SEMANTIC)
// CLASSIFICATION:
// - Class: Structural
// - Reversibility: Forward-only
// - Execution Scope: Both
// - Mutation Authority: Tier C
//
// PURPOSE:
// Performs STRUCTURAL and SEMANTIC validation of [UniversalContract] instances.
// ALL validation is contained within this class — no external or implicit checks permitted.
//
// STRUCTURAL VALIDATION:
//   All required fields non-blank.
//   position/total constraints satisfied (position ≥ 1, total ≥ 1, position ≤ total).
//   outputDefinition.expectedType non-blank.
//   outputDefinition.expectedSchema has at least one entry with non-blank keys.
//
// SEMANTIC VALIDATION:
//   executionType/targetDomain compatibility matrix enforced.
//   requiredCapabilities list does not contain duplicate entries.
//   outputDefinition schema values are non-null.

/**
 * Result of [UniversalContractValidator.validate].
 *
 * [Valid]   — contract passed all structural and semantic checks.
 * [Invalid] — one or more violations detected; [violations] listed in detection order.
 */
sealed class ContractValidationResult {
    /** Contract passed all structural and semantic checks. */
    object Valid : ContractValidationResult()

    /**
     * One or more validation violations were found.
     *
     * @property violations All detected violations in detection order.
     */
    data class Invalid(val violations: List<String>) : ContractValidationResult()
}

/**
 * UniversalContractValidator — structural and semantic gate for [UniversalContract].
 *
 * Entry point: [validate].
 *
 * Validation pipeline (executed in full — no short-circuit):
 *  Phase 1 — STRUCTURAL: field presence, non-blank constraints, position/total relation,
 *             outputDefinition coherence.
 *  Phase 2 — SEMANTIC:   executionType/targetDomain compatibility, duplicate requirements,
 *             outputDefinition schema value coherence.
 *
 * The validator is stateless and side-effect-free; the same input always produces
 * the same result.
 */
class UniversalContractValidator {

    /**
     * Validate [contract] through the full structural + semantic pipeline.
     *
     * Both phases are always executed in full; all violations are collected before
     * returning. This ensures callers receive a complete picture of all problems
     * rather than having to fix-and-retry for each one.
     *
     * @param contract The [UniversalContract] to validate.
     * @return [ContractValidationResult.Valid] when all checks pass;
     *         [ContractValidationResult.Invalid] listing all violations otherwise.
     */
    fun validate(contract: UniversalContract): ContractValidationResult {
        val violations = mutableListOf<String>()
        checkStructural(contract, violations)
        checkSemantic(contract, violations)
        return if (violations.isEmpty()) ContractValidationResult.Valid
               else ContractValidationResult.Invalid(violations)
    }

    // ── Phase 1: Structural ────────────────────────────────────────────────────

    private fun checkStructural(contract: UniversalContract, violations: MutableList<String>) {
        if (contract.contractId.isBlank())
            violations += "STRUCTURAL: contractId must not be blank"
        if (contract.intentId.isBlank())
            violations += "STRUCTURAL: intentId must not be blank"
        if (contract.reportReference.isBlank())
            violations += "STRUCTURAL: reportReference must not be blank (RRIL-1)"
        if (contract.position < 1)
            violations += "STRUCTURAL: position must be >= 1, got ${contract.position}"
        if (contract.total < 1)
            violations += "STRUCTURAL: total must be >= 1, got ${contract.total}"
        if (contract.position > contract.total)
            violations += "STRUCTURAL: position (${contract.position}) must be <= total (${contract.total})"
        if (contract.outputDefinition.expectedType.isBlank())
            violations += "STRUCTURAL: outputDefinition.expectedType must not be blank"
        if (contract.outputDefinition.expectedSchema.isEmpty())
            violations += "STRUCTURAL: outputDefinition.expectedSchema must have at least one entry"
        contract.outputDefinition.expectedSchema.keys.forEachIndexed { i, key ->
            if (key.isBlank())
                violations += "STRUCTURAL: outputDefinition.expectedSchema key[$i] must not be blank"
        }
    }

    // ── Phase 2: Semantic ──────────────────────────────────────────────────────

    private fun checkSemantic(contract: UniversalContract, violations: MutableList<String>) {
        // ExecutionType / TargetDomain compatibility matrix
        val allowed = COMPATIBILITY_MATRIX[contract.executionType]
        if (allowed != null && contract.targetDomain !in allowed) {
            violations += "SEMANTIC: executionType=${contract.executionType} is incompatible " +
                          "with targetDomain=${contract.targetDomain}; allowed: $allowed"
        }

        // Duplicate capability detection (by enum identity)
        val capabilityNames = contract.requiredCapabilities.map { it.name }
        val duplicates = capabilityNames.groupBy { it }.filter { it.value.size > 1 }.keys
        if (duplicates.isNotEmpty()) {
            violations += "SEMANTIC: requiredCapabilities contain duplicate entries: $duplicates"
        }

        // Output schema value coherence: null values are prohibited
        contract.outputDefinition.expectedSchema.forEach { (key, value) ->
            @Suppress("SENSELESS_COMPARISON")
            if (value == null)
                violations += "SEMANTIC: outputDefinition.expectedSchema['$key'] has a null value"
        }
    }

    companion object {
        /**
         * ExecutionType → allowed TargetDomain set.
         *
         * Encodes the execution authority compatibility matrix (UCS-1).
         * A contract whose [UniversalContract.targetDomain] is not in the set for its
         * [UniversalContract.executionType] is semantically invalid.
         */
        val COMPATIBILITY_MATRIX: Map<ExecutionType, Set<TargetDomain>> = mapOf(
            ExecutionType.INTERNAL_EXECUTION to setOf(TargetDomain.INTERNAL_ENGINE, TargetDomain.CONTRACTOR),
            ExecutionType.EXTERNAL_EXECUTION to setOf(TargetDomain.EXTERNAL_SYSTEM),
            ExecutionType.COMMUNICATION      to setOf(TargetDomain.USER),
            ExecutionType.AI_PROCESSING      to setOf(TargetDomain.MULTI_AGENT, TargetDomain.EXTERNAL_SYSTEM),
            ExecutionType.SWARM_COORDINATION to setOf(TargetDomain.MULTI_AGENT)
        )
    }
}
