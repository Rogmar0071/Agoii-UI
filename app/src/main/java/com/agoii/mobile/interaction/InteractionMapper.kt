package com.agoii.mobile.interaction

import com.agoii.mobile.core.ReplayState
import com.agoii.mobile.simulation.SimulationView

/**
 * Extracts the relevant subset of [ReplayState] for a given [InteractionScope],
 * and maps a [SimulationView] directly into a [StateSlice] for the simulation path.
 *
 * Responsibility: mapping only — no formatting, no business logic.
 * Every field in the returned [StateSlice] is copied verbatim from its source.
 */
class InteractionMapper {

    /**
     * Extract the state fields that belong to [scope] from [state].
     *
     * Fields not relevant to a given scope are omitted (set to their zero value)
     * so that downstream formatting is always working with a clean, bounded slice.
     */
    fun extract(scope: InteractionScope, state: ReplayState): StateSlice = when (scope) {

        InteractionScope.FULL_SYSTEM -> StateSlice(
            phase              = state.phase,
            objective          = state.objective,
            contractsCompleted = state.contractsCompleted,
            totalContracts     = state.totalContracts,
            executionStarted   = state.executionStarted,
            executionCompleted = state.executionCompleted,
            assemblyStarted    = state.assemblyStarted,
            assemblyValidated  = state.assemblyValidated,
            assemblyCompleted  = state.assemblyCompleted,
            references         = listOf("phase", "objective", "contractsCompleted",
                                        "totalContracts", "executionStarted",
                                        "executionCompleted", "assemblyStarted",
                                        "assemblyValidated", "assemblyCompleted")
        )

        InteractionScope.CONTRACT -> StateSlice(
            phase              = state.phase,
            objective          = state.objective,
            contractsCompleted = state.contractsCompleted,
            totalContracts     = state.totalContracts,
            executionStarted   = false,
            executionCompleted = false,
            assemblyStarted    = false,
            assemblyValidated  = false,
            assemblyCompleted  = false,
            references         = listOf("phase", "objective", "contractsCompleted",
                                        "totalContracts")
        )

        InteractionScope.TASK -> StateSlice(
            phase              = state.phase,
            objective          = null,
            contractsCompleted = state.contractsCompleted,
            totalContracts     = state.totalContracts,
            executionStarted   = state.executionStarted,
            executionCompleted = false,
            assemblyStarted    = false,
            assemblyValidated  = false,
            assemblyCompleted  = false,
            references         = listOf("phase", "contractsCompleted", "totalContracts",
                                        "executionStarted")
        )

        InteractionScope.EXECUTION -> StateSlice(
            phase              = state.phase,
            objective          = null,
            contractsCompleted = state.contractsCompleted,
            totalContracts     = state.totalContracts,
            executionStarted   = state.executionStarted,
            executionCompleted = state.executionCompleted,
            assemblyStarted    = false,
            assemblyValidated  = false,
            assemblyCompleted  = false,
            references         = listOf("phase", "contractsCompleted", "totalContracts",
                                        "executionStarted", "executionCompleted")
        )

        InteractionScope.SIMULATION -> StateSlice(
            phase              = state.phase,
            objective          = state.objective,
            contractsCompleted = 0,
            totalContracts     = 0,
            executionStarted   = false,
            executionCompleted = false,
            assemblyStarted    = false,
            assemblyValidated  = false,
            assemblyCompleted  = false,
            references         = listOf("phase", "objective")
        )
    }

    /**
     * Map a [SimulationView] directly into a [StateSlice].
     *
     * Rules:
     *  - Direct field mapping ONLY — no interpretation, no derived logic.
     *  - Must not depend on [ReplayState] or [SimulationResult].
     *  - [SimulationView] fields are copied verbatim; no meaning is inferred.
     */
    fun extractFromSimulationView(view: SimulationView): StateSlice {
        return StateSlice(
            phase              = "simulation_${view.mode}",
            objective          = view.summary,
            contractsCompleted = 0,
            totalContracts     = 0,
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
 * A bounded, immutable subset of [ReplayState] scoped to a single interaction.
 *
 * Used as the intermediate value between [InteractionMapper] and
 * [InteractionFormatter].  Contains no methods and performs no computation.
 */
data class StateSlice(
    val phase: String,
    val objective: String?,
    val contractsCompleted: Int,
    val totalContracts: Int,
    val executionStarted: Boolean,
    val executionCompleted: Boolean,
    val assemblyStarted: Boolean,
    val assemblyValidated: Boolean,
    val assemblyCompleted: Boolean = false,
    val references: List<String>
)
