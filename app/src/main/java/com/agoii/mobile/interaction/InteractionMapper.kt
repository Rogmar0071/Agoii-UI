package com.agoii.mobile.interaction

import com.agoii.mobile.core.ReplayStructuralState

/**
 * Maps a [ReplayStructuralState] into a [StateSlice] for downstream formatting.
 *
 * Responsibility: mapping only — no formatting, no business logic.
 * Every field in the returned [StateSlice] is derived from [ReplayStructuralState] only.
 */
class InteractionMapper {

    /**
     * Derive a [StateSlice] from [state].
     *
     * All five structural fields are always mapped from [ReplayStructuralState].
     * No branching, no false assignment, no null assignment.
     */
    fun extract(state: ReplayStructuralState): StateSlice = StateSlice(
        executionStarted   = state.execution.assignedTasks > 0,
        executionCompleted = state.execution.fullyExecuted,
        assemblyStarted    = state.assembly.assemblyStarted,
        assemblyValidated  = state.assembly.assemblyValidated,
        assemblyCompleted  = state.assembly.assemblyCompleted
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
