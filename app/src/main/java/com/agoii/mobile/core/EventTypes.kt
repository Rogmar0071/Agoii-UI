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

    // ── ELVC Admission Layer events (AGOII-ELVC-ADMISSION-LAYER) ─────────────

    /** Emitted when the Execution Load (EL) has been computed from the execution graph. */
    const val EXECUTION_LOAD_COMPUTED        = "execution_load_computed"

    /** Emitted when the Validation Capacity (VC) has been computed from IRS outputs. */
    const val VALIDATION_CAPACITY_COMPUTED   = "validation_capacity_computed"

    /** Emitted when the safety condition (EL ≤ VC) has been evaluated. */
    const val SAFETY_CONDITION_EVALUATED     = "safety_condition_evaluated"

    /** Emitted when the execution graph is decomposed into subgraphs (SPLIT decision). */
    const val CONTRACT_SPLIT                 = "contract_split"

    /** Emitted when the admission layer rejects a contract (REJECT decision). */
    const val CONTRACT_REJECTED              = "contract_rejected"

    /** Emitted when the admission layer admits a contract (ALLOW or ALLOW_WITH_BOUNDARY). */
    const val CONTRACT_ADMITTED              = "contract_admitted"

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
        EXECUTION_LOAD_COMPUTED,
        VALIDATION_CAPACITY_COMPUTED,
        SAFETY_CONDITION_EVALUATED,
        CONTRACT_SPLIT,
        CONTRACT_REJECTED,
        CONTRACT_ADMITTED
    )

    /** Default number of contracts generated per intent. */
    const val DEFAULT_TOTAL_CONTRACTS = 3
}
