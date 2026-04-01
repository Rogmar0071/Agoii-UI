package com.agoii.mobile.contracts

import com.agoii.mobile.execution.AnchorState

// AGOII CONTRACT — UNIVERSAL CONTRACT RECOVERY (UCS-1)
// SURFACE 8: CONTRACT FAILURE + RECOVERY (RCF-1 BINDING)
// CLASSIFICATION:
// - Class: Structural
// - Reversibility: Forward-only
// - Execution Scope: Both
// - Mutation Authority: Tier C
//
// PURPOSE:
// Issues [ExecutionRecoveryContract] instances for [UniversalContract] execution failures.
//
// RCF-1 BINDING RULES:
//   R1 — One [ExecutionRecoveryContract] per VIOLATION SURFACE (never grouped).
//   R2 — [AnchorState] is IMMUTABLE once extracted; no modification permitted.
//   R3 — Every recovery contract MUST be ledger-materialized (caller responsibility).
//   R4 — reportReference is propagated from the originating [UniversalContract] (RRIL-1).
//   R5 — reportReference MUST appear in every recovery contract (FAILURE_REFERENCE).

/**
 * UniversalContractRecovery — RCF-1 recovery contract issuer for [UniversalContract] failures.
 *
 * Entry point: [issueRecovery].
 *
 * Rules:
 *  - One call to [issueRecovery] = one violation surface = one [ExecutionRecoveryContract] (R1).
 *  - The caller is responsible for ledger-materializing each returned recovery contract (R3).
 *  - The [AnchorState] passed in is embedded as-is; it MUST NOT be modified by the caller
 *    after the recovery contract is returned (R2).
 *  - [reportReference] from the originating [UniversalContract] is propagated verbatim (R4).
 *
 * The class is stateless and side-effect-free.
 */
class UniversalContractRecovery {

    /**
     * Issue an [ExecutionRecoveryContract] for a single violation surface (RCF-1, R1).
     *
     * @param contract         The [UniversalContract] that failed.
     * @param failureClass     Category of failure ([FailureClass] enum).
     * @param violationSurface Atomic failing unit description. ONE surface per call (R1).
     * @param anchorState      Immutable [AnchorState] snapshot from the AERP-1 report (R2).
     * @return A single [ExecutionRecoveryContract] for the given violation surface.
     */
    fun issueRecovery(
        contract:         UniversalContract,
        failureClass:     FailureClass,
        violationSurface: String,
        anchorState:      AnchorState
    ): ExecutionRecoveryContract = ExecutionRecoveryContract(
        reportReference     = contract.reportReference,
        contractId          = "RCF_${contract.contractId}_${failureClass.name}",
        failureClass        = failureClass,
        violationField      = violationSurface,
        correctionDirective = buildCorrectionDirective(contract, failureClass, violationSurface),
        anchorState         = anchorState,
        successCondition    = "Contract '${contract.contractId}' at position ${contract.position} " +
                              "executed with SUCCESS via route derived from " +
                              "executionType=${contract.executionType}, targetDomain=${contract.targetDomain}"
    )

    private fun buildCorrectionDirective(
        contract:         UniversalContract,
        failureClass:     FailureClass,
        violationSurface: String
    ): String =
        "Resolve ${failureClass.name} for contract '${contract.contractId}' " +
        "(class=${contract.contractClass}, type=${contract.executionType}, " +
        "domain=${contract.targetDomain}) at position ${contract.position}: $violationSurface"
}
