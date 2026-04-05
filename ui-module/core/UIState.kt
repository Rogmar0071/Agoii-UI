package agoii.ui.core

import agoii.ui.bridge.UIReplayState

/**
 * Immutable UI-facing representation of the current ledger state.
 * Produced by [StateProjection] from a [UIReplayState].
 * No logic lives here — this is a pure data carrier.
 */
data class UIState(
    val isComplete: Boolean,
    val executionStarted: Boolean = false,
    val executionCompleted: Boolean = false,
    val assemblyStarted: Boolean = false,
    val assemblyValidated: Boolean = false,
    val assemblyCompleted: Boolean = false
)

/**
 * Pure mapper: [UIReplayState] → [UIState].
 * No business logic — every field is a deterministic derivation from the input.
 */
class StateProjection {

    fun project(state: UIReplayState): UIState {
        val av = state.auditView
        val fullyExecuted = av.execution.totalTasks > 0 &&
            av.execution.validatedTasks == av.execution.totalTasks
        return UIState(
            isComplete         = av.assembly.assemblyCompleted,
            executionStarted   = av.execution.assignedTasks > 0,
            executionCompleted = fullyExecuted,
            assemblyStarted    = av.assembly.assemblyStarted,
            assemblyValidated  = av.assembly.assemblyValidated,
            assemblyCompleted  = av.assembly.assemblyCompleted
        )
    }
}
