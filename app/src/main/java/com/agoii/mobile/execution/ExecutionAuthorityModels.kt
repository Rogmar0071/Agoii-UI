package com.agoii.mobile.execution

import com.agoii.mobile.contracts.ContractReport
import com.agoii.mobile.contracts.UniversalContract

// ══════════════════════════════════════════════════════════════════════════════
// AGOII-CONVERGENCE-ENFORCEMENT-LOCK-003
// EXECUTION AUTHORITY MODELS — CONVERGENCE HARDENING
// ══════════════════════════════════════════════════════════════════════════════

// ── Execution Contract (Phase 1 Model) ────────────────────────────────────────

/**
 * Phase 1 execution contract model used by ExecutionEntryPoint.
 *
 * @property contractId      Unique contract identifier.
 * @property name            Human-readable contract description.
 * @property position        Ordinal position in execution sequence (1-indexed).
 * @property reportReference Immutable report reference (RRID) for traceability.
 */
data class ExecutionContract(
    val contractId:      String,
    val name:            String,
    val position:        Int,
    val reportReference: String
)

/**
 * Input to [ExecutionAuthority.evaluate] — batch of contracts + report reference.
 *
 * @property contracts       List of contracts to authorize.
 * @property reportReference Report reference shared across all contracts.
 */
data class ExecutionContractInput(
    val contracts:       List<ExecutionContract>,
    val reportReference: String
)

// ── Authority Evaluation Result ───────────────────────────────────────────────

/**
 * Result of [ExecutionAuthority.evaluate] — pre-ledger authorization gate.
 */
sealed class ExecutionAuthorityResult {
    
    /**
     * Contracts approved for execution.
     *
     * @property orderedContracts Approved contracts in execution order.
     */
    data class Approved(
        val orderedContracts: List<ExecutionContract>
    ) : ExecutionAuthorityResult()
    
    /**
     * Contracts blocked due to validation failure.
     *
     * @property reason Human-readable block reason.
     */
    data class Blocked(
        val reason: String
    ) : ExecutionAuthorityResult()
}

// ── Execution Result ──────────────────────────────────────────────────────────

/**
 * Result of [ExecutionAuthority.executeFromLedger] — ledger-driven execution.
 */
sealed class ExecutionAuthorityExecutionResult {
    
    /**
     * Task executed successfully.
     *
     * @property taskId          Task identifier.
     * @property executionStatus SUCCESS or FAILURE.
     * @property report          Contract report (null for FAILURE).
     */
    data class Executed(
        val taskId:          String,
        val executionStatus: ExecutionStatus,
        val report:          ContractReport?
    ) : ExecutionAuthorityExecutionResult()
    
    /**
     * ICS processing completed (final ICS phase).
     */
    object IcsCompleted : ExecutionAuthorityExecutionResult()
    
    /**
     * No TASK_STARTED event found; nothing to execute.
     */
    object NotTriggered : ExecutionAuthorityExecutionResult()
    
    /**
     * Execution blocked due to convergence ceiling or validation failure.
     *
     * @property reason Human-readable block reason.
     */
    data class Blocked(
        val reason: String
    ) : ExecutionAuthorityExecutionResult()
}

// ── Universal Contract Ingestion Result ───────────────────────────────────────

/**
 * Result of [ExecutionAuthority.ingestUniversalContract] — universal contract validation.
 */
sealed class UniversalIngestionResult {
    
    /**
     * Contract ingested successfully; CONTRACT_CREATED + CONTRACT_VALIDATED emitted.
     *
     * @property contractId Contract identifier.
     */
    data class Ingested(
        val contractId: String
    ) : UniversalIngestionResult()
    
    /**
     * Validation failed; TASK_EXECUTED(FAILURE) emitted.
     * P1 handler will emit RECOVERY_CONTRACT on next executeFromLedger call.
     *
     * @property contractId Contract identifier.
     * @property reason     Validation failure reason.
     */
    data class ValidationFailed(
        val contractId: String,
        val reason:     String
    ) : UniversalIngestionResult()
    
    /**
     * Enforcement failed; TASK_EXECUTED(FAILURE) emitted.
     * P1 handler will emit RECOVERY_CONTRACT on next executeFromLedger call.
     *
     * @property contractId Contract identifier.
     * @property reason     Enforcement failure reason.
     */
    data class EnforcementFailed(
        val contractId: String,
        val reason:     String
    ) : UniversalIngestionResult()
    
    /**
     * Failure recorded to ledger; no recovery contract emitted yet.
     * Caller must invoke executeFromLedger to trigger P1 recovery.
     */
    object FailureRecorded : UniversalIngestionResult()
}

// ── Convergence Enforcement Models ────────────────────────────────────────────

/**
 * Mutation surface declaration — explicit list of allowed changes during delta execution.
 *
 * @property allowedFields   List of field paths that may be modified.
 * @property allowedSections List of structural sections that may be mutated.
 */
data class MutationSurface(
    val allowedFields:   List<String>,
    val allowedSections: List<String>
)

/**
 * Validated sections tracker — immutable set of validated sections from prior execution.
 *
 * @property sections Set of section identifiers marked as validated.
 */
data class ValidatedSections(
    val sections: Set<String>
)

/**
 * Convergence tracker — recovery chain length per contractId.
 *
 * @property recoveryChains Map of contractId → recovery attempt count.
 */
data class ConvergenceTracker(
    val recoveryChains: Map<String, Int> = emptyMap()
) {
    /**
     * Check if recovery chain exceeds MAX_DELTA for given contractId.
     *
     * @param contractId Contract identifier.
     * @param maxDelta   Maximum allowed delta attempts.
     * @return True if chain exceeds limit.
     */
    fun exceedsLimit(contractId: String, maxDelta: Int): Boolean =
        (recoveryChains[contractId] ?: 0) >= maxDelta
    
    /**
     * Increment recovery chain for given contractId.
     *
     * @param contractId Contract identifier.
     * @return Updated tracker with incremented count.
     */
    fun increment(contractId: String): ConvergenceTracker =
        copy(recoveryChains = recoveryChains + (contractId to (recoveryChains[contractId] ?: 0) + 1))
}

// ── Anchor State (for Recovery Contracts) ─────────────────────────────────────

/**
 * Immutable anchor state for delta execution — validated sections + mutation surface.
 *
 * @property validatedSections Sections marked as validated in prior execution.
 * @property mutationSurface   Explicit list of allowed changes.
 */
data class AnchorState(
    val validatedSections: ValidatedSections,
    val mutationSurface:   MutationSurface
)

// ── Failure Classification ────────────────────────────────────────────────────

/**
 * Failure classification for recovery contracts.
 */
enum class FailureClass {
    VALIDATION_FAILURE,
    ENFORCEMENT_FAILURE,
    CONVERGENCE_FAILURE,
    REGRESSION_DETECTED,
    MUTATION_VIOLATION
}

// ══════════════════════════════════════════════════════════════════════════════
// END OF MODELS
// ══════════════════════════════════════════════════════════════════════════════
