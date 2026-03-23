package com.agoii.mobile.interaction

/**
 * Immutable output produced by [InteractionEngine.execute].
 *
 * The UI renders [content] directly; [references] lists the ledger fields that
 * contributed to the output so callers can trace back to the source of truth.
 *
 * This class carries no business logic and performs no computation.
 *
 * @param contractId  Echo of the originating [InteractionContract.contractId].
 * @param content     Human-readable, formatted representation of the state slice.
 * @param references  Ledger field names that were read to produce [content].
 */
data class InteractionResult(
    val contractId: String,
    val content: String,
    val references: List<String>
)
