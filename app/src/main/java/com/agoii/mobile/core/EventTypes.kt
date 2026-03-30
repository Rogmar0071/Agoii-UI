package com.agoii.mobile.core

/**
 * Single source of truth for all event type string literals and shared constants.
 * Centralises what would otherwise be duplicated string constants across
 * Governor, LedgerAudit, Replay, and UI.
 */
object EventTypes {
    const val INTENT_SUBMITTED    = "intent_submitted"
    const val CONTRACTS_GENERATED = "contracts_generated"
    const val CONTRACTS_READY     = "contracts_ready"
    const val CONTRACTS_APPROVED  = "contracts_approved"
    const val EXECUTION_STARTED   = "execution_started"
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

    // ── ICS Interaction loop events (AGOII-MQP-ICS-ACTIVATION-CORRECTION-02) ─
    // Emitted during the pre-execution intent clarification loop.
    // INTERACTION_CONTRACT — ICS issues a clarifying question via a contractor.
    // INTERACTION_RESPONSE — User's answer is recorded by the ICS.
    // INTENT_UPDATED       — ICS determines the intent is sufficiently qualified.
    const val INTERACTION_CONTRACT = "interaction_contract"
    const val INTERACTION_RESPONSE = "interaction_response"
    const val INTENT_UPDATED       = "intent_updated"

    val ALL: Set<String> = setOf(
        INTENT_SUBMITTED,
        CONTRACTS_GENERATED,
        CONTRACTS_READY,
        CONTRACTS_APPROVED,
        EXECUTION_STARTED,
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
        COMMIT_ABORTED,
        INTERACTION_CONTRACT,
        INTERACTION_RESPONSE,
        INTENT_UPDATED
    )

    /** Default number of contracts generated per intent. */
    const val DEFAULT_TOTAL_CONTRACTS = 3
}
