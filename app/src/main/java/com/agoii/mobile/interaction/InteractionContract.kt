package com.agoii.mobile.interaction

/**
 * Describes a query against the ledger-derived state or a simulation view.
 *
 * The UI creates one of these, passes it to [InteractionEngine.execute], and
 * renders the resulting [InteractionResult].  No business logic lives here.
 *
 * Exactly ONE source must be active:
 *  - [SourceType.LEDGER]     → a [InteractionInput.LedgerInput] must be supplied.
 *  - [SourceType.SIMULATION] → a [InteractionInput.SimulationInput] must be supplied.
 *
 * @param contractId   Identifies this interaction (typically the project id).
 * @param query        Human-readable description of what is being asked.
 * @param scope        Which slice of [com.agoii.mobile.core.ReplayState] to expose.
 * @param outputType   How the response should be formatted.
 * @param sourceType   Declares the input source for this contract.
 * @param simulationId Optional binding to a specific simulation run.
 */
data class InteractionContract(
    val contractId: String,
    val query: String,
    val scope: InteractionScope,
    val outputType: OutputType,
    val sourceType: SourceType,
    val simulationId: String? = null
)

/** Declares which input source drives this interaction. */
enum class SourceType {
    LEDGER,
    SIMULATION
}

/** Determines which portion of the replay state is extracted by [InteractionEngine]. */
enum class InteractionScope {
    FULL_SYSTEM,
    CONTRACT,
    TASK,
    EXECUTION,
    SIMULATION
}

/** Controls the formatting strategy applied by [InteractionEngine]. */
enum class OutputType {
    SUMMARY,
    DETAILED,
    EXPLANATION,
    STATUS
}
