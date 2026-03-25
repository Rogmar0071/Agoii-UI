package com.agoii.mobile.core.enforcement

import com.agoii.mobile.core.contract.ContractGraph

// ─── Enforcement Pipeline ─────────────────────────────────────────────────────

/**
 * EnforcementPipeline — mandatory execution gate for all contracts.
 *
 * No contract may proceed to execution without passing all eight steps:
 *
 *   Step 1: Structural Surface Scan    — validate the full [SurfaceMap]: files, data classes,
 *                                        field usage, and dependencies; detect invalid references
 *                                        and unrecognised structural entries.
 *   Step 2: Field Validity Check       — all field paths in [SurfaceMap.fieldUsage] must
 *                                        belong to ReplayStructuralState.
 *   Step 3: Data Class Alignment       — no data class without field paths; no default or
 *                                        placeholder derivation expressions.
 *   Step 4: Derivation Validation      — only the two permitted derivations are accepted.
 *   Step 5: Substitution Detection     — scan for substitution patterns in expressions.
 *   Step 6: Flow Integrity Check       — no legacy ReplayState references in expressions,
 *                                        field paths, or surface files.
 *   Step 7: Mutation Scope Lock        — files, data classes, and derived field keys must be
 *                                        confined to their respective declared structural sets.
 *   Step 8: Execution Authorization    — approved = violations.isEmpty(); blocks on any violation.
 *
 * Forbidden:
 *  - Skipping any step
 *  - Partial validation
 *  - Silent failure handling
 *  - Fallback execution
 *  - Synthetic approval states
 *  - Exception-based control flow
 *
 * The pipeline is stateless; every [run] call is independent.
 */
class EnforcementPipeline(
    private val validator: EnforcementValidator = EnforcementValidator()
) {

    /**
     * Run all eight enforcement steps against [graph].
     *
     * All steps are executed unconditionally so the full violation trace is always produced.
     *
     * @param graph The [ContractGraph] to validate.
     * @return [EnforcementResult] with [EnforcementResult.approved] = true only when all
     *         steps produce zero violations.
     */
    fun run(graph: ContractGraph): EnforcementResult {
        val allViolations = mutableListOf<Violation>()

        // Step 1: Structural Surface Scan
        val (surfaceMap, step1Violations) = validator.scanSurface(graph)
        allViolations += step1Violations

        // Step 2: Field Validity Check
        allViolations += validator.validateFields(graph)

        // Step 3: Data Class Alignment Check
        allViolations += validator.checkDataClassAlignment(graph)

        // Step 4: Derivation Validation
        allViolations += validator.validateDerivations(graph)

        // Step 5: Substitution Detection
        allViolations += validator.detectSubstitutions(graph)

        // Step 6: Flow Integrity Check
        allViolations += validator.checkFlowIntegrity(graph)

        // Step 7: Mutation Scope Lock
        allViolations += validator.checkMutationScope(graph)

        // Step 8: Execution Authorization — approved only when no violations
        val approved = allViolations.isEmpty()

        return EnforcementResult(
            approved         = approved,
            violations       = allViolations,
            validatedSurface = surfaceMap
        )
    }
}
