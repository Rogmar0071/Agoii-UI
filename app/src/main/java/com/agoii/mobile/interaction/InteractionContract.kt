package com.agoii.mobile.interaction

/**
 * Describes a query against the ledger-derived structural state.
 *
 * The UI creates one of these, passes it to [InteractionEngine.execute], and
 * renders the resulting [InteractionResult].  No business logic lives here.
 *
 * @param contractId  Identifies this interaction (typically the project id).
 * @param query       Human-readable description of what is being asked.
 * @param scope       Structural boundary of the state slice exposed to this contract.
 * @param outputType  How the response should be formatted.
 * @param sourceType  Whether the backing data is from the ledger or a simulation view.
 */
data class InteractionContract(
    val contractId: String,
    val query: String,
    val scope: InteractionScope = InteractionScope.FULL_SYSTEM,
    val outputType: OutputType,
    val sourceType: SourceType = SourceType.LEDGER
)

/** Controls the formatting strategy applied by [InteractionEngine]. */
enum class OutputType {
    SUMMARY,
    DETAILED,
    EXPLANATION,
    STATUS
}
