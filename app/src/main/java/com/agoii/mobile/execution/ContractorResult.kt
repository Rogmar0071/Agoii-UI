// AGOII CONTRACT — CONTRACTOR RESULT MODULE
// CLASSIFICATION:
// - Class: Structural
// - Reversibility: Forward-only
// - Execution Scope: Both
// - Mutation Authority: Tier C
//
// PURPOSE:
// Capture the outcome of contractor invocation through Execution Authority.
// Enforces AERP-1 validation and deterministic execution results.
//
// RULES:
// - ONLY created by Execution Authority
// - Carries complete execution + validation state
// - Immutable once created
// - report_reference MUST persist (RRIL-1)

package com.agoii.mobile.execution

/**
 * Result of contractor invocation authorized and executed by Execution Authority.
 *
 * This is the ONLY output type produced when Execution Authority processes
 * a TaskAssignedContract and invokes the Contractor Module.
 *
 * Sealed hierarchy ensures exhaustive handling and type safety.
 */
sealed class ContractorResult {

    /**
     * Contractor execution was authorized and completed successfully.
     *
     * @property taskId           Unique task identifier from TaskAssignedContract
     * @property contractorId     ID of the contractor that executed the task
     * @property reportReference  RRIL-1 anchor - MUST persist across all contracts
     * @property executionOutput  Raw output from ContractorExecutor
     * @property validationPassed Whether output met task requirements
     */
    data class Authorized(
        val taskId: String,
        val contractorId: String,
        val reportReference: String,
        val executionOutput: ContractorExecutionOutput,
        val validationPassed: Boolean
    ) : ContractorResult()

    /**
     * Contractor execution was blocked by Execution Authority.
     *
     * @property taskId          Unique task identifier from TaskAssignedContract
     * @property contractorId    ID of the contractor that was blocked
     * @property reportReference RRIL-1 anchor - MUST persist across all contracts
     * @property reason          Human-readable blocking reason (AERP-1 validation failure)
     * @property stage           Which validation stage blocked: "STRUCTURE" | "AUTHORIZATION" | "EXECUTION"
     */
    data class Blocked(
        val taskId: String,
        val contractorId: String,
        val reportReference: String,
        val reason: String,
        val stage: String
    ) : ContractorResult()
}

/**
 * Input contract for Execution Authority re-entry after TASK_ASSIGNED.
 *
 * This is extracted from the ledger event payload and validated by AERP-1.
 *
 * @property taskId          Unique task identifier
 * @property contractorId    Contractor assigned by Governor
 * @property reportReference RRIL-1 anchor from original ExecutionContract
 * @property position        Contract position in execution sequence
 * @property total           Total number of contracts in execution plan
 */
data class TaskAssignedContract(
    val taskId: String,
    val contractorId: String,
    val reportReference: String,
    val position: Int,
    val total: Int
)

/**
 * Recovery contract generated when contractor execution fails.
 *
 * Enforces RCF-1: ALL failures produce Recovery Contract, no retries.
 *
 * @property taskId          Failing task identifier
 * @property contractorId    Contractor that failed
 * @property reportReference RRIL-1 anchor
 * @property failureReason   Detailed failure description
 * @property recoveryAction  Recommended recovery: "REASSIGN" | "ESCALATE" | "ABORT"
 */
data class RecoveryContract(
    val taskId: String,
    val contractorId: String,
    val reportReference: String,
    val failureReason: String,
    val recoveryAction: String
)
