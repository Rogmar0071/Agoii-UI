package com.agoii.mobile.contracts

// AGOII CONTRACT — CONTRACT ENFORCEMENT ENGINE (UCS-1)
// SURFACE 6: CONTRACT ENFORCEMENT ENGINE
// CLASSIFICATION:
// - Class: Structural
// - Reversibility: Forward-only
// - Execution Scope: Both
// - Mutation Authority: Tier C
//
// PURPOSE:
// Enforces ALL constraints and requirements declared by a [UniversalContract].
// Applied after validation (Surface 2) and normalization (Surface 3); acts as a
// BLOCKING pre-execution gate.
//
// ENFORCEMENT RULES:
//   E1 — Requirements: every map-typed requirement must have a non-blank 'capability'
//        and a 'requiredLevel' in 0..5.  Blank string requirements are rejected.
//   E2 — Constraints: no constraint string may be blank.
//   E3 — Output schema coherence: every schema entry must have a non-blank type description.
//   E4 — Execution authority compatibility: executionType/targetDomain pair must be in the
//        authority matrix (mirrors Surface 2 semantic check; enforcement is a blocking gate,
//        validation is a pre-normalization gate).

/**
 * A single enforcement violation within a [UniversalContract].
 *
 * @property surface     The contract field or sub-field where the violation was detected.
 * @property rule        The enforcement rule identifier (e.g., "E1", "E2").
 * @property description Human-readable explanation of the violation.
 */
data class ContractViolation(
    val surface:     String,
    val rule:        String,
    val description: String
)

/**
 * Result of [ContractEnforcementEngine.enforce].
 *
 * [Compliant] — all enforcement rules passed.
 * [Violated]  — one or more [ContractViolation]s detected.
 */
sealed class ContractEnforcementResult {
    /** Contract is fully compliant with all enforcement rules. */
    object Compliant : ContractEnforcementResult()

    /**
     * One or more enforcement violations were found.
     *
     * @property violations All detected violations (one per failing rule instance).
     */
    data class Violated(val violations: List<ContractViolation>) : ContractEnforcementResult()
}

/**
 * ContractEnforcementEngine — blocking pre-execution enforcement gate for [UniversalContract].
 *
 * Entry point: [enforce].
 *
 * The engine runs all four enforcement rules in full (no short-circuit), collecting every
 * violation before returning.  It is stateless and side-effect-free; the same input always
 * produces the same result.
 */
class ContractEnforcementEngine {

    /**
     * Enforce all constraint and requirement rules declared in [contract].
     *
     * @param contract The [UniversalContract] to enforce.
     * @return [ContractEnforcementResult.Compliant] when all rules pass;
     *         [ContractEnforcementResult.Violated] listing every violation otherwise.
     */
    fun enforce(contract: UniversalContract): ContractEnforcementResult {
        val violations = mutableListOf<ContractViolation>()
        enforceRequirements(contract, violations)
        enforceConstraints(contract, violations)
        enforceOutputSchema(contract, violations)
        enforceExecutionAuthority(contract, violations)
        return if (violations.isEmpty()) ContractEnforcementResult.Compliant
               else ContractEnforcementResult.Violated(violations)
    }

    // ── E1: Requirements enforcement ──────────────────────────────────────────

    private fun enforceRequirements(contract: UniversalContract, violations: MutableList<ContractViolation>) {
        contract.requirements.forEachIndexed { index, req ->
            when (req) {
                is Map<*, *> -> {
                    val capability = req["capability"]?.toString()
                    if (capability.isNullOrBlank()) {
                        violations += ContractViolation(
                            surface     = "requirements[$index]",
                            rule        = "E1",
                            description = "requirement[$index] has missing or blank 'capability'"
                        )
                    }
                    val rawLevel = req["requiredLevel"]
                    val level = when (rawLevel) {
                        is Number -> rawLevel.toInt()
                        is String -> rawLevel.toIntOrNull()
                        else      -> null
                    }
                    if (level == null || level !in 0..5) {
                        violations += ContractViolation(
                            surface     = "requirements[$index]",
                            rule        = "E1",
                            description = "requirement[$index] 'requiredLevel' must be an integer in 0..5, got $rawLevel"
                        )
                    }
                }
                is String -> if (req.isBlank()) {
                    violations += ContractViolation(
                        surface     = "requirements[$index]",
                        rule        = "E1",
                        description = "requirement[$index] must not be blank"
                    )
                }
            }
        }
    }

    // ── E2: Constraints enforcement ────────────────────────────────────────────

    private fun enforceConstraints(contract: UniversalContract, violations: MutableList<ContractViolation>) {
        contract.constraints.forEachIndexed { index, constraint ->
            if (constraint is String && constraint.isBlank()) {
                violations += ContractViolation(
                    surface     = "constraints[$index]",
                    rule        = "E2",
                    description = "constraint[$index] must not be blank"
                )
            }
        }
    }

    // ── E3: Output schema coherence ────────────────────────────────────────────

    private fun enforceOutputSchema(contract: UniversalContract, violations: MutableList<ContractViolation>) {
        contract.outputDefinition.expectedSchema.forEach { (key, value) ->
            if (value.toString().isBlank()) {
                violations += ContractViolation(
                    surface     = "outputDefinition.expectedSchema['$key']",
                    rule        = "E3",
                    description = "schema entry '$key' has a blank or empty type description"
                )
            }
        }
    }

    // ── E4: Execution authority compatibility ──────────────────────────────────

    private fun enforceExecutionAuthority(contract: UniversalContract, violations: MutableList<ContractViolation>) {
        val allowed = AUTHORITY_MATRIX[contract.executionType]
        if (allowed != null && contract.targetDomain !in allowed) {
            violations += ContractViolation(
                surface     = "executionType/targetDomain",
                rule        = "E4",
                description = "executionType=${contract.executionType} cannot route to " +
                              "targetDomain=${contract.targetDomain}; allowed: $allowed"
            )
        }
    }

    companion object {
        /**
         * Execution authority compatibility matrix.
         *
         * Mirrors [UniversalContractValidator.COMPATIBILITY_MATRIX]; enforcement is a
         * BLOCKING gate applied at pre-execution time, while validation is a
         * pre-normalization gate.
         */
        private val AUTHORITY_MATRIX: Map<ExecutionType, Set<TargetDomain>> = mapOf(
            ExecutionType.INTERNAL_EXECUTION to setOf(TargetDomain.INTERNAL_ENGINE, TargetDomain.CONTRACTOR),
            ExecutionType.EXTERNAL_EXECUTION to setOf(TargetDomain.EXTERNAL_SYSTEM),
            ExecutionType.COMMUNICATION      to setOf(TargetDomain.USER),
            ExecutionType.AI_PROCESSING      to setOf(TargetDomain.MULTI_AGENT, TargetDomain.EXTERNAL_SYSTEM),
            ExecutionType.SWARM_COORDINATION to setOf(TargetDomain.MULTI_AGENT)
        )
    }
}
