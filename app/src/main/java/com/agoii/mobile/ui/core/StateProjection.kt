package com.agoii.mobile.ui.core

import com.agoii.mobile.core.ReplayStructuralState

/**
 * Immutable UI-facing representation of the current ledger state.
 * Produced by [StateProjection] from a [ReplayStructuralState].
 * No logic lives here — this is a pure data carrier.
 *
 * All 5 fields are mandatory structural derivations. No defaults, no inferred state.
 */
data class UIState(
    val executionStarted:  Boolean,
    val executionCompleted: Boolean,
    val assemblyStarted:   Boolean,
    val assemblyValidated: Boolean,
    val assemblyCompleted: Boolean
)

/**
 * Pure mapper: [ReplayStructuralState] → [UIState].
 * No business logic — every field is a deterministic structural derivation from the input.
 *
 * Derivation Law:
 *   executionStarted   = state.execution.assignedTasks > 0
 *   executionCompleted = state.execution.fullyExecuted
 */
class StateProjection {

    fun project(state: ReplayStructuralState): UIState {
        return UIState(
            executionStarted   = state.execution.assignedTasks > 0,
            executionCompleted = state.execution.fullyExecuted,
            assemblyStarted    = state.assembly.assemblyStarted,
            assemblyValidated  = state.assembly.assemblyValidated,
            assemblyCompleted  = state.assembly.assemblyCompleted
        )
    }
}
