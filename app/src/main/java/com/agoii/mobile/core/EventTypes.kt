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
        CONTRACT_APPROVED
    )

    /** Default number of contracts generated per intent. */
    const val DEFAULT_TOTAL_CONTRACTS = 3
}
