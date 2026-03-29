package com.agoii.mobile.assembly

// ── Input models (ledger-derived only; no external state) ────────────────────

/**
 * A single contract entry in the ordered assembly input.
 * Derived exclusively from CONTRACT_COMPLETED events (authoritative source per spec 5.1).
 */
data class AssemblyContract(
    val contractId:      String,
    val position:        Int,
    /** RRID carried by this CONTRACT_COMPLETED event; used for RRIL-1 lineage check (spec 5.3). */
    val reportReference: String
)

/**
 * Per-contract execution surface sourced exclusively from a successful TASK_EXECUTED event.
 *
 * Only [artifactReference] is legal to read from the ledger (AERP-1: no upstream mutation).
 */
data class ContractExecutionData(
    val artifactReference: String
)

/**
 * Ledger-derived input for the Assembly Module.
 *
 * ALL fields MUST be reconstructed from the EventLedger exclusively.
 * NO external memory, NO inference permitted (RRIL-1 / Assembly spec).
 */
data class AssemblyInput(
    val reportReference:  String,
    val contractSetId:    String,
    val totalContracts:   Int,
    /** Contracts ordered by position (1 → N); identity sourced from CONTRACT_COMPLETED. */
    val orderedContracts: List<AssemblyContract>,
    /** contractId → execution surface (artifactReference) from TASK_EXECUTED SUCCESS. */
    val taskArtifacts:    Map<String, ContractExecutionData>
)

// ── Output models ─────────────────────────────────────────────────────────────

/**
 * Per-contract output entry in the [FinalArtifact].
 *
 * [reportReference] preserves RRIL-1 lineage from the originating CONTRACT_COMPLETED event.
 * artifactStructure is intentionally absent — it is not persisted in the EventLedger
 * and MUST NOT be synthesized (AERP-1 §3).
 */
data class ContractOutput(
    val contractId:        String,
    val position:          Int,
    val reportReference:   String,
    val artifactReference: String
)

/**
 * FINAL_ARTIFACT — single, structured, deterministic output produced by
 * the Assembly Module from all CONTRACT_COMPLETED outputs.
 *
 * Ordered by contract position (1 → N); RRIL-1 lineage preserved via
 * [reportReference] and each [ContractOutput.reportReference].
 * [traceMap] maps every contractId to its report_reference (spec §7.2).
 */
data class FinalArtifact(
    val reportReference: String,
    val contractSetId:   String,
    val totalContracts:  Int,
    /** Ordered list of per-contract outputs, position ascending. */
    val contractOutputs: List<ContractOutput>,
    /** contractId → report_reference trace map (RRIL-1 lineage, spec §7.2). */
    val traceMap:        Map<String, String>
)

/**
 * Assembly contract report (AERP-1 compliant).
 *
 * Produced by the Assembly Module after a successful assembly run.
 * [taskId] / [assemblyId] are always "assembly_<reportReference>".
 */
data class AssemblyContractReport(
    val reportReference: String,
    /** Deterministic task identifier: "assembly_<reportReference>". */
    val taskId:          String,
    /** Deterministic assembly identifier — equal to [taskId] (spec §7.3). */
    val assemblyId:      String,
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
        val finalArtifact:  FinalArtifact,
        val assemblyReport: AssemblyContractReport
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
     * Required artifact(s) are missing or a convergence rule was violated.
     * No ledger writes occur (pre-flight guard fires before ASSEMBLY_STARTED).
     *
     * [reason] contains the violation class (e.g. MISSING_ARTIFACTS, RRID_VIOLATION,
     * INCOMPLETE_EXECUTION_SURFACE, POSITION_VIOLATION).
     */
    data class Blocked(
        val reason:           String,
        val missingContracts: List<String> = emptyList()
    ) : AssemblyExecutionResult()
}

