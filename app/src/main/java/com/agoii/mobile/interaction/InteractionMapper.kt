package com.agoii.mobile.interaction

import com.agoii.mobile.core.ReplayStructuralState
import com.agoii.mobile.simulation.SimulationView

/**
 * Maps a [ReplayStructuralState] into a [StateSlice] for downstream formatting,
 * and maps a [SimulationView] directly into a [StateSlice] for the simulation path.
 *
 * Responsibility: mapping only — no formatting, no business logic.
 * Every field in the returned [StateSlice] is derived from [ReplayStructuralState] only.
 *
 * Scope is accepted as an interface boundary and MUST NOT alter field values or
 * suppress any structural field from the output.
 */
class InteractionMapper {

    /**
     * Derive a [StateSlice] from [state] for the given [scope].
     *
     * [scope] is an input boundary only — it does NOT filter, suppress, or
     * conditionally assign any field in the returned [StateSlice].
     * All five structural fields are always mapped from [ReplayStructuralState].
     * No branching, no false assignment, no null assignment.
     */
    fun extract(scope: InteractionScope, state: ReplayStructuralState): StateSlice = StateSlice(
        executionStarted   = state.execution.assignedTasks > 0,
        executionCompleted = state.execution.fullyExecuted,
        assemblyStarted    = state.assembly.assemblyStarted,
        assemblyValidated  = state.assembly.assemblyValidated,
        assemblyCompleted  = state.assembly.assemblyCompleted
    )

    /**
     * Map a [SimulationView] directly into a [StateSlice].
     *
     * Rules:
     *  - Direct field mapping ONLY from available [SimulationView] signals.
     *  - Must not depend on [ReplayStructuralState].
     *  - No literal placeholder values. No conditional suppression.
     */
    fun extractFromSimulationView(view: SimulationView): StateSlice = StateSlice(
        executionStarted   = view.confidence > 0.0,
        executionCompleted = view.confidence == 1.0,
        assemblyStarted    = view.confidence > 0.0,
        assemblyValidated  = view.confidence >= 0.9,
        assemblyCompleted  = view.confidence == 1.0
    )
}

/**
 * An immutable structural snapshot derived from [ReplayStructuralState].
 *
 * Used as the intermediate value between [InteractionMapper] and
 * [InteractionFormatter]. Contains no methods and performs no computation.
 */
data class StateSlice(
    val executionStarted: Boolean,
    val executionCompleted: Boolean,
    val assemblyStarted: Boolean,
    val assemblyValidated: Boolean,
    val assemblyCompleted: Boolean
)
