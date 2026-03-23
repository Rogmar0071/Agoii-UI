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
        taskStarted      = isTaskStarted(state),
        taskCompleted    = isTaskCompleted(state),
        taskFailed       = isTaskFailed(state),
        retryCount       = deriveRetryCount(state),
        validationStatus = deriveValidationStatus(state)
    )

    // ── private helpers ───────────────────────────────────────────────────────

    private fun isTaskStarted(state: UIState): Boolean = state.phase in setOf(
        "task_started", "task_completed", "task_validated", "task_failed",
        "execution_completed", "assembly_started", "assembly_validated", "assembly_completed"
    )

    private fun isTaskCompleted(state: UIState): Boolean = state.phase in setOf(
        "task_completed", "task_validated",
        "execution_completed", "assembly_started", "assembly_validated", "assembly_completed"
    )

    private fun isTaskFailed(state: UIState): Boolean = state.phase == "task_failed"

    private fun deriveRetryCount(state: UIState): Int = when (state.phase) {
        "contractor_reassigned" -> 1
        else                    -> 0
    }

    private fun deriveValidationStatus(state: UIState): String = when (state.phase) {
        "task_validated"    -> "validated"
        "task_failed"       -> "failed"
        "assembly_validated" -> "validated"
        else                -> "pending"
    }
}
