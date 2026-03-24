package com.agoii.mobile.ui.core

import com.agoii.mobile.core.ReplayStructuralState

/**
 * Immutable UI-facing representation of the current ledger state.
 * Produced by [StateProjection] from a [ReplayStructuralState].
 * No logic lives here — this is a pure data carrier.
 */
data class UIState(
    val phase: String = "",
    val activeContractId: String? = null,
    val activeTaskId: String? = null,
    val progress: Float = 0f,
    val isComplete: Boolean,
    val assemblyStarted: Boolean = false,
    val assemblyValidated: Boolean = false,
    val assemblyCompleted: Boolean = false
)

/**
 * Pure mapper: [ReplayStructuralState] → [UIState].
 * No business logic — every field is a deterministic derivation from the input.
 */
class StateProjection {

    fun project(state: ReplayStructuralState): UIState {
        val progress = if (state.execution.totalTasks > 0)
            state.execution.completedTasks.toFloat() / state.execution.totalTasks.toFloat()
        else 0f

        return UIState(
            progress          = progress,
            isComplete        = state.assembly.assemblyCompleted,
            assemblyStarted   = state.assembly.assemblyStarted,
            assemblyValidated = state.assembly.assemblyValidated,
            assemblyCompleted = state.assembly.assemblyCompleted
        )
    }
}
