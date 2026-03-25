package com.agoii.mobile.ui.modules

import com.agoii.mobile.ui.core.UIState

/**
 * Immutable presentation model produced by [ExecutionModuleUI].
 *
 * Every field is a truthful reflection of structural state.
 * No task lifecycle, retry counts, or string-based status signals are present.
 *
 * @property executionStarted   Derived from structural execution state via [UIState.executionStarted].
 * @property executionCompleted Derived from structural execution state via [UIState.executionCompleted].
 * @property assemblyStarted    Whether the assembly phase has been started.
 * @property assemblyValidated  Whether assembly validation has passed.
 * @property assemblyCompleted  Whether the assembly phase has fully completed.
 */
data class ExecutionModuleState(
    val executionStarted: Boolean,
    val executionCompleted: Boolean,
    val assemblyStarted: Boolean,
    val assemblyValidated: Boolean,
    val assemblyCompleted: Boolean
)

/**
 * Data presenter for the execution module.
 *
 * Responsibility: map [UIState] into an [ExecutionModuleState] using only
 * structural signals. No task lifecycle simulation, no placeholders,
 * no inferred progress. Every field reflects structural truth available in [UIState].
 */
class ExecutionModuleUI {

    fun present(state: UIState): ExecutionModuleState = ExecutionModuleState(
        executionStarted   = state.executionStarted,
        executionCompleted = state.executionCompleted,
        assemblyStarted    = state.assemblyStarted,
        assemblyValidated  = state.assemblyValidated,
        assemblyCompleted  = state.assemblyCompleted
    )
}
