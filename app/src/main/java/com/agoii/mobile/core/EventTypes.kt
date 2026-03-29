package com.agoii.mobile.core

/**
 * Single source of truth for all event type string literals and shared constants.
 * Centralises what would otherwise be duplicated string constants across
 * Governor, LedgerAudit, Replay, and UI.
 */
object EventTypes {
    const val INTENT_SUBMITTED    = "intent_submitted"
    // ── Intent evolution phase (MQP AGOII-MQP-INTENT-EVOLUTION-EXECUTION-ALIGNMENT-01) ─
    const val INTENT_UPDATED      = "intent_updated"      // 0..N before INTENT_FINALIZED
    const val INTENT_FINALIZED    = "intent_finalized"    // Commit boundary — freezes intent state
    const val CONTRACTS_GENERATED = "contracts_generated"
    const val CONTRACTS_READY     = "contracts_ready"
    const val CONTRACTS_APPROVED  = "contracts_approved"
    const val EXECUTION_STARTED   = "execution_started"
    // ── Execution authority separation (MQP AGOII-MQP-INTENT-EVOLUTION-EXECUTION-ALIGNMENT-01) ─
    const val EXECUTION_AUTHORIZED  = "execution_authorized"   // User approval gate (canonical)
    const val EXECUTION_IN_PROGRESS = "execution_in_progress"  // System event: execution begins
    const val EXECUTION_ABORTED     = "execution_aborted"      // Interruption
    const val RETURN_TO_INTENT_STATE = "return_to_intent_state" // Recovery: returns to intent phase
    const val CONTRACT_STARTED    = "contract_started"
    const val CONTRACT_COMPLETED  = "contract_completed"
    const val EXECUTION_COMPLETED = "execution_completed"
    const val ASSEMBLY_STARTED    = "assembly_started"
    const val ASSEMBLY_VALIDATED  = "assembly_validated"
    const val ASSEMBLY_COMPLETED  = "assembly_completed"
    const val ICS_STARTED         = "ics_started"
    const val ICS_COMPLETED       = "ics_completed"

    // ── Commit contract lifecycle events ─────────────────────────────────────
    const val COMMIT_CONTRACT     = "commit_contract"
    const val COMMIT_EXECUTED     = "commit_executed"
    const val COMMIT_ABORTED      = "commit_aborted"

    // ── Task execution lifecycle events ──────────────────────────────────────
    const val TASK_CREATED            = "task_created"
    const val TASK_ASSIGNED           = "task_assigned"
    const val TASK_STARTED            = "task_started"
    const val TASK_EXECUTED           = "task_executed"
    const val TASK_COMPLETED          = "task_completed"
    const val TASK_VALIDATED          = "task_validated"
    const val TASK_FAILED             = "task_failed"
    const val CONTRACTOR_REASSIGNED   = "contractor_reassigned"
    const val CONTRACT_FAILED         = "contract_failed"
    const val RECOVERY_CONTRACT       = "recovery_contract"

    // ── UCS-1 contract ingestion lifecycle events ─────────────────────────────
    // Emitted by ExecutionAuthority.ingestUniversalContract() to replace
    // in-memory lifecycle tracking (ledger determinism — UCS-1 directive).
    const val CONTRACT_CREATED   = "contract_created"
    const val CONTRACT_VALIDATED = "contract_validated"
    const val CONTRACT_APPROVED  = "contract_approved"

    val ALL: Set<String> = setOf(
        INTENT_SUBMITTED,
        INTENT_UPDATED,
        INTENT_FINALIZED,
        CONTRACTS_GENERATED,
        CONTRACTS_READY,
        CONTRACTS_APPROVED,
        EXECUTION_STARTED,
        EXECUTION_AUTHORIZED,
        EXECUTION_IN_PROGRESS,
        EXECUTION_ABORTED,
        RETURN_TO_INTENT_STATE,
        CONTRACT_STARTED,
        CONTRACT_COMPLETED,
        EXECUTION_COMPLETED,
        ASSEMBLY_STARTED,
        ASSEMBLY_VALIDATED,
        ASSEMBLY_COMPLETED,
        ICS_STARTED,
        ICS_COMPLETED,
        TASK_CREATED,
        TASK_ASSIGNED,
        TASK_STARTED,
        TASK_EXECUTED,
        TASK_COMPLETED,
        TASK_VALIDATED,
        TASK_FAILED,
        CONTRACTOR_REASSIGNED,
        CONTRACT_FAILED,
        RECOVERY_CONTRACT,
        CONTRACT_CREATED,
        CONTRACT_VALIDATED,
        CONTRACT_APPROVED,
        COMMIT_CONTRACT,
        COMMIT_EXECUTED,
        COMMIT_ABORTED
    )

    /** Default number of contracts generated per intent. */
    const val DEFAULT_TOTAL_CONTRACTS = 3
}
