package com.agoii.mobile.interaction

import com.agoii.mobile.core.ReplayStructuralState

/**
 * Extracts structural fields from [ReplayStructuralState] into a [StateSlice].
 *
 * Responsibility: pure derivation only — no formatting, no business logic,
 * no filtering, no scope-based branching.
 * Every field in the returned [StateSlice] is derived directly from its source.
 */
class InteractionMapper {

    /**
     * Derive a [StateSlice] from [state].
     *
     * All structural fields are always mapped — no conditions, no defaults,
     * no substitution, no scope filtering.
     */
    fun extract(state: ReplayStructuralState): StateSlice {
        val av = state.auditView
        val executionCompleted =
            av.execution.totalTasks > 0 && av.execution.validatedTasks == av.execution.totalTasks
        return StateSlice(
            executionStarted   = av.execution.assignedTasks > 0,
            executionCompleted = executionCompleted,
            assemblyStarted    = av.assembly.assemblyStarted,
            assemblyValidated  = av.assembly.assemblyValidated
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
    val references: List<String> = emptyList()
)
