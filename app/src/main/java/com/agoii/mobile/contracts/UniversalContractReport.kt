package com.agoii.mobile.contracts

import com.agoii.mobile.contractors.ResolutionTrace
import com.agoii.mobile.execution.AnchorState
import com.agoii.mobile.execution.ContractorExecutionOutput

// AGOII CONTRACT — UNIVERSAL CONTRACT REPORT (UCS-1)
// SURFACE 9: CONTRACT REPORT INTEGRATION (AERP-1 BINDING)
// CLASSIFICATION:
// - Class: Structural
// - Reversibility: Forward-only
// - Execution Scope: Both
// - Mutation Authority: Tier C
//
// PURPOSE:
// Generates [ContractReport] (AERP-1) from a [UniversalContract] execution and
// extracts [AnchorState] for RCF-1 recovery binding (Surface 8).
//
// AERP-1 BINDING RULES:
//   A1 — Every [ContractReport] MUST carry the reportReference from the originating
//        [UniversalContract] (RRIL-1).
//   A2 — typeInventory is derived from [ContractorExecutionOutput.resultArtifact] keys.
//   A3 — executionSteps reflect the UCS-1 pipeline stages executed.
//   A4 — [AnchorState] is extracted once per execution and MUST NOT be modified.
//   A5 — Mandatory AERP-1 fields: reportReference, taskId, contractId, contractorId,
//        typeInventory, executionSteps, artifactStructure.

/**
 * UniversalContractReport — AERP-1 report generator for [UniversalContract] executions.
 *
 * Entry points:
 *  [generateReport]     — produces [ContractReport] from execution output (AERP-1, A5).
 *  [extractAnchorState] — extracts immutable [AnchorState] from a [ContractReport] (A4).
 *
 * Both methods are pure functions: no I/O, no state, no side effects.
 */
class UniversalContractReport {

    /**
     * Generate an AERP-1-compliant [ContractReport] from a [UniversalContract] execution.
     *
     * Mandatory AERP-1 fields (A5) are all populated from [contract] and the execution inputs.
     * [contract.reportReference] is propagated verbatim (A1, RRIL-1).
     * [typeInventory] is derived from [executionOutput.resultArtifact] keys (A2).
     * [executionSteps] records the UCS-1 pipeline stages (A3).
     *
     * @param contract        The [UniversalContract] that was executed.
     * @param taskId          Deterministic task identifier.
     * @param contractorId    The contractor that performed execution.
     * @param executionOutput The raw output produced by
     *                        [com.agoii.mobile.execution.ContractorExecutor].
     * @param trace           The [ResolutionTrace] from
     *                        [com.agoii.mobile.contractors.DeterministicMatchingEngine].
     * @return AERP-1-compliant [ContractReport].
     */
    fun generateReport(
        contract:        UniversalContract,
        taskId:          String,
        contractorId:    String,
        executionOutput: ContractorExecutionOutput,
        trace:           ResolutionTrace
    ): ContractReport {
        val artifact = executionOutput.resultArtifact
        return ContractReport(
            reportReference    = contract.reportReference,
            typeInventory      = artifact.keys.toList(),
            functionSignatures = listOf(contract.contractId),
            logicFlow          = UCS1_PIPELINE_STEPS,
            errorConditions    = listOfNotNull(executionOutput.error),
            traceStructure     = trace,
            rawOutput          = artifact["response"]?.toString() ?: executionOutput.error ?: "",
            normalizedOutput   = if (executionOutput.error == null) artifact["response"]?.toString() else null,
            exitCode           = if (executionOutput.error == null) 0 else 1,
            failureSurface     = listOfNotNull(executionOutput.error),
            policyViolations   = emptyList()
        )
    }

    /**
     * Extract an immutable [AnchorState] from [report] (AERP-1, A4).
     *
     * The returned [AnchorState] MUST NOT be modified after extraction.
     * It is embedded in every [com.agoii.mobile.execution.ExecutionRecoveryContract]
     * issued for this execution attempt.
     *
     * @param report The [ContractReport] produced by [generateReport].
     * @return Immutable [AnchorState] snapshot.
     */
    fun extractAnchorState(report: ContractReport): AnchorState = AnchorState(
        reportReference    = report.reportReference,
        validatedTypes     = report.typeInventory.toList(),
        validatedStructure = report.typeInventory.toSet(),
        validatedPaths     = report.logicFlow.toList()
    )

    companion object {
        /**
         * UCS-1 pipeline stages recorded in every AERP-1 report (A3).
         *
         * These steps reflect the full Surface pipeline executed before and during execution:
         *  1. UCS1_VALIDATED   — Surface 2: structural + semantic validation passed.
         *  2. UCS1_NORMALIZED  — Surface 3: canonical form produced.
         *  3. UCS1_ENFORCED    — Surface 6: enforcement gate passed.
         *  4. UCS1_ROUTED      — Surface 4: execution route determined.
         *  5. MATCHING_RESOLVED — contractor matched deterministically.
         *  6. EXECUTION_INVOKED — contractor executor invoked.
         *  7. ARTIFACT_PRODUCED — result artifact captured from contractor.
         */
        private val UCS1_PIPELINE_STEPS = listOf(
            "UCS1_VALIDATED",
            "UCS1_NORMALIZED",
            "UCS1_ENFORCED",
            "UCS1_ROUTED",
            "MATCHING_RESOLVED",
            "EXECUTION_INVOKED",
            "ARTIFACT_PRODUCED"
        )
    }
}
