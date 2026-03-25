package com.agoii.mobile.core.enforcement

import com.agoii.mobile.core.contract.ContractGraph

// ─── Enforcement Pipeline ─────────────────────────────────────────────────────

/**
 * EnforcementPipeline — mandatory execution gate for all contracts.
 *
 * No contract may proceed to execution without passing all four steps:
 *
 *   Step 1: Structural Surface Scan  — map fields, references, and dependencies;
 *                                      detect missing fields, invalid references, type mismatches.
 *   Step 2: Field Validity Check     — all fields must belong to ReplayStructuralState.
 *   Step 3: Data Class Alignment     — no non-structural or placeholder fields permitted.
 *   Step 4: Derivation Validation    — only the permitted derivation is accepted.
 *
 * Forbidden:
 *  - Skipping any step
 *  - Partial validation
 *  - Silent failure handling
 *  - Fallback execution
 *  - Synthetic approval states
 *
 * The pipeline is stateless; every [run] call is independent.
 */
class EnforcementPipeline(
    private val validator: EnforcementValidator = EnforcementValidator()
) {

    /**
     * Run all four enforcement steps against [graph].
     *
     * All steps are executed unconditionally so the full violation trace is always produced.
     *
     * @param graph The [ContractGraph] to validate.
     * @return [EnforcementResult] with APPROVED verdict only when all steps pass with zero violations.
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

        val verdict = if (allViolations.isEmpty()) {
            EnforcementVerdict.APPROVED
        } else {
            EnforcementVerdict.REJECTED
        }

        return EnforcementResult(
            verdict    = verdict,
            surfaceMap = surfaceMap,
            violations = allViolations
        )
    }
}
