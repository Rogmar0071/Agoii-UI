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
    const val ASSEMBLY_FAILED     = "assembly_failed"
    const val ICS_STARTED         = "ics_started"
    const val ICS_COMPLETED       = "ics_completed"

    // ── Commit contract lifecycle events ─────────────────────────────────────
    const val COMMIT_CONTRACT     = "commit_contract"
    const val COMMIT_EXECUTED     = "commit_executed"
    const val COMMIT_ABORTED      = "commit_aborted"

    // ── Conversational layer events (MQP-PHASE-3) ─────────────────────────────
    // Emitted by CoreBridge to record the conversational surface in the ledger.
    // USER_MESSAGE_SUBMITTED: user input captured before execution.
    // SYSTEM_MESSAGE_EMITTED: formatted execution result after the ICS cycle.
    const val USER_MESSAGE_SUBMITTED = "user_message_submitted"
    const val SYSTEM_MESSAGE_EMITTED = "system_message_emitted"

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
    const val DELTA_CONTRACT_CREATED  = "delta_contract_created"

    // ── UCS-1 contract ingestion lifecycle events ─────────────────────────────
    // Emitted by ExecutionAuthority.ingestUniversalContract() to replace
    // in-memory lifecycle tracking (ledger determinism — UCS-1 directive).
    const val CONTRACT_CREATED   = "contract_created"
    const val CONTRACT_VALIDATED = "contract_validated"
    const val CONTRACT_APPROVED  = "contract_approved"

    // ── Capability derivation events (MQP-CAPABILITY-DERIVATION-v1) ───────────
    // CAPABILITY_DERIVED:    emitted by ExecutionEntryPoint after CONTRACTS_GENERATED;
    //                        records the deterministic capability set for the intent (AERP-1).
    // CAPABILITY_REINJECTED: emitted on recovery reinjection (RCF-1);
    //                        signals that capabilities have been re-derived after failure.
    const val CAPABILITY_DERIVED    = "capability_derived"
    const val CAPABILITY_REINJECTED = "capability_reinjected"

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
        ASSEMBLY_FAILED,
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
        DELTA_CONTRACT_CREATED,
        CONTRACT_CREATED,
        CONTRACT_VALIDATED,
        CONTRACT_APPROVED,
        CAPABILITY_DERIVED,
        CAPABILITY_REINJECTED,
        COMMIT_CONTRACT,
        COMMIT_EXECUTED,
        COMMIT_ABORTED,
        USER_MESSAGE_SUBMITTED,
        SYSTEM_MESSAGE_EMITTED
    )

    /** Default number of contracts generated per intent. */
    const val DEFAULT_TOTAL_CONTRACTS = 3
}
