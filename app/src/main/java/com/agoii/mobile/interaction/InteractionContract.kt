package com.agoii.mobile.interaction

/**
 * Describes a query against the ledger-derived structural state.
 *
 * The UI creates one of these, passes it to [InteractionEngine.execute], and
 * renders the resulting [InteractionResult].  No business logic lives here.
 *
 * @param contractId  Identifies this interaction (typically the project id).
 * @param query       Human-readable description of what is being asked.
 * @param outputType  How the response should be formatted.
 */
data class InteractionContract(
    val contractId: String,
    val query: String,
    val outputType: OutputType
)

/** Controls the formatting strategy applied by [InteractionEngine]. */
enum class OutputType {
    SUMMARY,
    DETAILED,
    EXPLANATION,
    STATUS
}
