package com.agoii.mobile.assembly

// ── Input models (ledger-derived only; no external state) ────────────────────

/**
 * A single contract entry in the ordered assembly input.
 * Derived exclusively from the CONTRACTS_GENERATED event.
 */
data class AssemblyContract(
    val contractId: String,
    val position:   Int
)

/**
 * Ledger-derived input for the Assembly Module.
 *
 * ALL fields MUST be reconstructed from the EventLedger exclusively.
 * NO external memory, NO inference permitted (RRIL-1 / Assembly spec).
 */
data class AssemblyInput(
    val reportReference:   String,
    val contractSetId:     String,
    val totalContracts:    Int,
    /** Contracts ordered by position (1 → N). */
    val orderedContracts:  List<AssemblyContract>,
    /** contractId → artifactReference, sourced from successful TASK_EXECUTED events. */
    val taskArtifacts:     Map<String, String>
)

// ── Output models ─────────────────────────────────────────────────────────────

/**
 * Per-contract output entry in the [FinalArtifact].
 * Preserves RRIL-1 lineage via [artifactReference].
 */
data class ContractOutput(
    val contractId:        String,
    val position:          Int,
    val artifactReference: String,
    val artifactStructure: Map<String, Any>
)

/**
 * FINAL_ARTIFACT — single, structured, deterministic output produced by
 * the Assembly Module from all CONTRACT_COMPLETED outputs.
 *
 * Ordered by contract position (1 → N); RRIL-1 lineage preserved via
 * [reportReference] and each [ContractOutput.artifactReference].
 */
data class FinalArtifact(
    val reportReference: String,
    /** Ordered list of per-contract outputs, position ascending. */
    val contractOutputs: List<ContractOutput>
)

/**
 * Assembly contract report (AERP-1 compliant).
 *
 * Produced by the Assembly Module after a successful assembly run.
 * [taskId] is always "ASSEMBLY::<reportReference>".
 */
data class AssemblyContractReport(
    val reportReference: String,
    /** Deterministic task identifier: "ASSEMBLY::<reportReference>". */
    val taskId:          String,
    val contractSetId:   String,
    val totalContracts:  Int,
    val finalArtifact:   FinalArtifact
)

// ── Result ────────────────────────────────────────────────────────────────────

/**
 * Result of [AssemblyModule.assemble].
 */
sealed class AssemblyExecutionResult {

    /** Assembly completed successfully; ASSEMBLY_COMPLETED written to ledger. */
    data class Assembled(
        val finalArtifact:      FinalArtifact,
        val assemblyReport:     AssemblyContractReport
    ) : AssemblyExecutionResult()

    /**
     * Trigger conditions not met (no EXECUTION_COMPLETED, not all contracts done,
     * no CONTRACTS_GENERATED, etc.).  No ledger writes occur.
     */
    object NotTriggered : AssemblyExecutionResult()

    /**
     * ASSEMBLY_COMPLETED already exists for this [reportReference].
     * Idempotency guard; no ledger writes occur.
     */
    data class AlreadyCompleted(val reportReference: String) : AssemblyExecutionResult()

    /**
     * Required artifact(s) are missing; assembly cannot proceed.
     * No ledger writes occur (pre-flight guard fires before ASSEMBLY_STARTED).
     */
    data class Blocked(
        val reason:           String,
        val missingContracts: List<String> = emptyList()
    ) : AssemblyExecutionResult()
}
