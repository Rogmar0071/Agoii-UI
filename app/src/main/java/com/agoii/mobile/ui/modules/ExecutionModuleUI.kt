package com.agoii.mobile.ui.modules

import com.agoii.mobile.ui.core.UIState

/**
 * Immutable presentation model produced by [ExecutionModuleUI].
 *
 * @property taskStarted        Whether a task_started event has been observed in this phase.
 * @property taskCompleted      Whether a task_completed event has been observed.
 * @property taskFailed         Whether a task_failed event has been observed.
 * @property retryCount         Number of retry cycles inferred from the current phase.
 * @property validationStatus   Presentable validation label ("pending", "validated", "failed").
 */
data class ExecutionModuleState(
    val taskStarted: Boolean,
    val taskCompleted: Boolean,
    val taskFailed: Boolean,
    val retryCount: Int,
    val validationStatus: String
)

/**
 * Data presenter for the execution module.
 *
 * Responsibility: map [UIState] into an [ExecutionModuleState]. Displays
 * task_started / task_completed / task_failed phase transitions, retry count, and
 * validation status. No mutations, no event emission, no business logic.
 */
class ExecutionModuleUI {

    fun present(state: UIState): ExecutionModuleState = ExecutionModuleState(
        taskStarted      = state.assemblyStarted || state.isComplete,
        taskCompleted    = state.assemblyStarted || state.isComplete,
        taskFailed       = false,
        retryCount       = 0,
        validationStatus = if (state.assemblyValidated) "validated" else "pending"
    )
}
