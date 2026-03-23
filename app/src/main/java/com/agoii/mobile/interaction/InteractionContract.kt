package com.agoii.mobile.interaction

/**
 * Describes a query against either ledger-derived state or a simulation result.
 *
 * The UI creates one of these, passes it to [InteractionEngine.execute] or
 * [InteractionEngine.executeSimulation], and renders the resulting [InteractionResult].
 * No business logic lives here.
 *
 * Source exclusivity rule: at most ONE of the two sources may be active per execution.
 *  - Ledger-based path  ([InteractionEngine.execute]):           [simulationId] is null.
 *  - Simulation-based path ([InteractionEngine.executeSimulation]): [simulationId] is set.
 *
 * @param contractId    Identifies this interaction (typically the project id).
 * @param query         Human-readable description of what is being asked.
 * @param scope         Which slice of the source to expose.
 * @param outputType    How the response should be formatted.
 * @param simulationId  Optional binding to a [com.agoii.mobile.simulation.SimulationResult].
 *                      When non-null the contract targets the simulation path, not the ledger.
 */
data class InteractionContract(
    val contractId: String,
    val query: String,
    val scope: InteractionScope,
    val outputType: OutputType,
    val simulationId: String? = null
)

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
