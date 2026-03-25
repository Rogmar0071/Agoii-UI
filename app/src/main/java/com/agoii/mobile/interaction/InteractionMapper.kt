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
     * Fields not relevant to a given scope are omitted (set to false)
     * so that downstream formatting is always working with a clean, bounded slice.
     */
    fun extract(scope: InteractionScope, state: ReplayStructuralState): StateSlice = when (scope) {

        InteractionScope.FULL_SYSTEM -> StateSlice(
            executionStarted   = state.execution.assignedTasks > 0,
            executionCompleted = state.execution.fullyExecuted,
            assemblyStarted    = state.assembly.assemblyStarted,
            assemblyValidated  = state.assembly.assemblyValidated,
            assemblyCompleted  = state.assembly.assemblyCompleted,
            references         = listOf("executionStarted", "executionCompleted",
                                        "assemblyStarted", "assemblyValidated",
                                        "assemblyCompleted")
        )

        InteractionScope.CONTRACT -> StateSlice(
            executionStarted   = false,
            executionCompleted = false,
            assemblyStarted    = false,
            assemblyValidated  = false,
            assemblyCompleted  = false,
            references         = listOf("contracts.generated", "contracts.valid")
        )

        InteractionScope.TASK -> StateSlice(
            executionStarted   = state.execution.assignedTasks > 0,
            executionCompleted = false,
            assemblyStarted    = false,
            assemblyValidated  = false,
            assemblyCompleted  = false,
            references         = listOf("executionStarted")
        )

        InteractionScope.EXECUTION -> StateSlice(
            executionStarted   = state.execution.assignedTasks > 0,
            executionCompleted = state.execution.fullyExecuted,
            assemblyStarted    = false,
            assemblyValidated  = false,
            assemblyCompleted  = false,
            references         = listOf("executionStarted", "executionCompleted")
        )

        InteractionScope.SIMULATION -> StateSlice(
            executionStarted   = false,
            executionCompleted = false,
            assemblyStarted    = false,
            assemblyValidated  = false,
            assemblyCompleted  = false,
            references         = listOf("simulation")
        )
    }

    /**
     * Map a [SimulationView] directly into a [StateSlice].
     *
     * Rules:
     *  - Direct field mapping ONLY — no interpretation, no derived logic.
     *  - Must not depend on [ReplayStructuralState] or [SimulationResult].
     *  - [SimulationView] details are used as references; no meaning is inferred.
     */
    fun extractFromSimulationView(view: SimulationView): StateSlice {
        return StateSlice(
            executionStarted   = false,
            executionCompleted = false,
            assemblyStarted    = false,
            assemblyValidated  = false,
            assemblyCompleted  = false,
            references         = view.details
        )
    }
}

/**
 * A bounded, immutable subset of [ReplayStructuralState] scoped to a single interaction.
 *
 * Used as the intermediate value between [InteractionMapper] and
 * [InteractionFormatter].  Contains no methods and performs no computation.
 */
data class StateSlice(
    val executionStarted: Boolean,
    val executionCompleted: Boolean,
    val assemblyStarted: Boolean,
    val assemblyValidated: Boolean,
    val assemblyCompleted: Boolean = false,
    val references: List<String>
)
