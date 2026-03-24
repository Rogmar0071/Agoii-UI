package com.agoii.mobile.interaction

import com.agoii.mobile.core.ReplayStructuralState
import com.agoii.mobile.simulation.SimulationView

/**
 * Extracts the relevant subset of [ReplayStructuralState] for a given [InteractionScope],
 * and maps a [SimulationView] directly into a [StateSlice] for the simulation path.
 *
 * Responsibility: mapping only — no formatting, no business logic.
 * Every field in the returned [StateSlice] is copied verbatim from its source.
 */
class InteractionMapper {

    /**
     * Extract the state fields that belong to [scope] from [state].
     *
     * Only structural fields from [ReplayStructuralState] are mapped.
     * Fields with no structural equivalent are omitted and resolved from data class defaults.
     */
    fun extract(scope: InteractionScope, state: ReplayStructuralState): StateSlice = when (scope) {

        InteractionScope.FULL_SYSTEM -> StateSlice(
            executionCompleted = state.execution.fullyExecuted,
            assemblyStarted    = state.assembly.assemblyStarted,
            assemblyValidated  = state.assembly.assemblyValidated,
            assemblyCompleted  = state.assembly.assemblyCompleted,
            references         = listOf("executionCompleted", "assemblyStarted",
                                        "assemblyValidated", "assemblyCompleted")
        )

        InteractionScope.CONTRACT -> StateSlice(
            assemblyStarted   = state.assembly.assemblyStarted,
            assemblyValidated = state.assembly.assemblyValidated,
            references        = listOf("assemblyStarted", "assemblyValidated")
        )

        InteractionScope.TASK -> StateSlice(
            executionCompleted = state.execution.fullyExecuted,
            assemblyStarted    = state.assembly.assemblyStarted,
            references         = listOf("executionCompleted", "assemblyStarted")
        )

        InteractionScope.EXECUTION -> StateSlice(
            executionCompleted = state.execution.fullyExecuted,
            assemblyStarted    = state.assembly.assemblyStarted,
            assemblyValidated  = state.assembly.assemblyValidated,
            assemblyCompleted  = state.assembly.assemblyCompleted,
            references         = listOf("executionCompleted", "assemblyStarted",
                                        "assemblyValidated", "assemblyCompleted")
        )

        InteractionScope.SIMULATION -> StateSlice(
            assemblyStarted = state.assembly.assemblyStarted,
            references      = listOf("assemblyStarted")
        )
    }

    /**
     * Map a [SimulationView] directly into a [StateSlice].
     *
     * Rules:
     *  - Direct field mapping ONLY — no interpretation, no derived logic.
     *  - Must not depend on [ReplayStructuralState] or [SimulationResult].
     *  - [SimulationView] fields are copied verbatim; no meaning is inferred.
     */
    fun extractFromSimulationView(view: SimulationView): StateSlice {
        return StateSlice(
            phase      = "simulation_${view.mode}",
            objective  = view.summary,
            references = view.details
        )
    }
}

/**
 * A bounded, immutable subset of [ReplayStructuralState] scoped to a single interaction.
 *
 * Used as the intermediate value between [InteractionMapper] and
 * [InteractionFormatter].  Contains no methods and performs no computation.
 * Fields with no structural equivalent carry their data class defaults.
 */
data class StateSlice(
    val phase: String = "",
    val objective: String? = null,
    val contractsCompleted: Int = 0,
    val totalContracts: Int = 0,
    val executionStarted: Boolean = false,
    val executionCompleted: Boolean = false,
    val assemblyStarted: Boolean = false,
    val assemblyValidated: Boolean = false,
    val assemblyCompleted: Boolean = false,
    val references: List<String> = emptyList()
)
