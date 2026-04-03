package com.agoii.mobile.interaction

import com.agoii.mobile.core.ReplayStructuralState

/**
 * Extracts all structural fields from [ReplayStructuralState] into a [StateSlice].
 *
 * Responsibility: pure derivation only — no formatting, no business logic,
 * no filtering, no scope-based branching.
 * Every field in the returned [StateSlice] is derived directly from its source.
 */
class InteractionMapper {

    /**
     * Derive a [StateSlice] from [state].
     *
     * All 5 structural fields are always mapped — no conditions, no defaults,
     * no substitution.
     */
    fun extract(state: ReplayStructuralState): StateSlice {
        val av = state.auditView
        return StateSlice(
            executionStarted   = av.execution.assignedTasks > 0,
            executionCompleted = av.execution.fullyExecuted,
            assemblyStarted    = av.assembly.assemblyStarted,
            assemblyValidated  = av.assembly.assemblyValidated,
            assemblyCompleted  = av.assembly.assemblyCompleted
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
    val assemblyCompleted: Boolean
)
