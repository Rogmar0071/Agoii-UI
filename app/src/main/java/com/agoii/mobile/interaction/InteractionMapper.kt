package com.agoii.mobile.interaction

import com.agoii.mobile.core.ReplayStructuralState
import com.agoii.mobile.simulation.SimulationView

/**
 * Extracts a bounded [StateSlice] from either a [ReplayStructuralState] or a
 * [SimulationView], according to the requested [InteractionScope].
 *
 * Responsibility: pure derivation only — no formatting, no business logic,
 * no filtering beyond the explicit scope contract.
 * Every field in the returned [StateSlice] is derived directly from its source.
 */
class InteractionMapper {

    /**
     * Derive a [StateSlice] from [state] constrained to [scope].
     *
     * Each scope exposes a deterministic, named subset of structural flags.
     */
    fun extract(scope: InteractionScope, state: ReplayStructuralState): StateSlice {
        val av = state.auditView
        val executionCompleted =
            av.execution.totalTasks > 0 && av.execution.validatedTasks == av.execution.totalTasks

        return when (scope) {
            InteractionScope.FULL_SYSTEM -> StateSlice(
                executionStarted   = av.execution.assignedTasks > 0,
                executionCompleted = executionCompleted,
                assemblyStarted    = av.assembly.assemblyStarted,
                assemblyValidated  = av.assembly.assemblyValidated,
                references         = listOf(
                    "executionStarted", "executionCompleted",
                    "assemblyStarted",  "assemblyValidated"
                )
            )
            InteractionScope.CONTRACT -> StateSlice(
                executionStarted   = false,
                executionCompleted = false,
                assemblyStarted    = false,
                assemblyValidated  = false,
                references         = listOf("contractsGenerated", "contractsValid")
            )
            InteractionScope.TASK -> StateSlice(
                executionStarted   = av.execution.assignedTasks > 0,
                executionCompleted = false,
                assemblyStarted    = false,
                assemblyValidated  = false,
                references         = listOf("executionStarted", "assignedTasks")
            )
            InteractionScope.EXECUTION -> StateSlice(
                executionStarted   = av.execution.assignedTasks > 0,
                executionCompleted = executionCompleted,
                assemblyStarted    = false,
                assemblyValidated  = false,
                references         = listOf("executionStarted", "executionCompleted")
            )
            InteractionScope.SIMULATION -> StateSlice(
                executionStarted   = false,
                executionCompleted = false,
                assemblyStarted    = false,
                assemblyValidated  = false,
                references         = listOf("simulation")
            )
        }
    }

    /**
     * Derive a [StateSlice] from a [SimulationView].
     *
     * Structural boolean flags are suppressed — the simulation result is final
     * and carries no ledger-derived state.
     * [SimulationView.details] are copied verbatim as references.
     * [SimulationView.summary] is preserved for display purposes only.
     */
    fun extractFromSimulationView(view: SimulationView): StateSlice =
        StateSlice(
            executionStarted   = false,
            executionCompleted = false,
            assemblyStarted    = false,
            assemblyValidated  = false,
            references         = view.details,
            simulationSummary  = view.summary
        )
}

/**
 * A bounded, immutable subset of structural state scoped to a single interaction.
 *
 * Used as the intermediate value between [InteractionMapper] and
 * [InteractionFormatter].  Contains no methods and performs no computation.
 *
 * @param simulationSummary  Non-null only when derived from a [SimulationView].
 *                           Carries the simulation's primary conclusion for display.
 */
data class StateSlice(
    val executionStarted: Boolean,
    val executionCompleted: Boolean,
    val assemblyStarted: Boolean,
    val assemblyValidated: Boolean,
    val references: List<String>,
    val simulationSummary: String? = null
)
