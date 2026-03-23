package com.agoii.mobile.ui.modules

import com.agoii.mobile.ui.core.UIState

/**
 * Task lifecycle states as displayable labels.
 */
enum class TaskLifecycleState {
    IDLE,
    ASSIGNED,
    STARTED,
    COMPLETED,
    VALIDATED,
    FAILED
}

/**
 * Immutable presentation model for a single task.
 *
 * @property taskId            Identifier of the task.
 * @property assignmentStatus  Whether the task has been assigned.
 * @property contractorId      Identifier of the assigned contractor, or null if unassigned.
 * @property lifecycleState    Current lifecycle state of the task.
 */
data class TaskEntry(
    val taskId: String,
    val assignmentStatus: String,
    val contractorId: String?,
    val lifecycleState: TaskLifecycleState
)

/**
 * Presentation model produced by [TaskModuleUI].
 *
 * @property tasks   Active task entries derived from the current [UIState].
 */
data class TaskModuleState(
    val tasks: List<TaskEntry>
)

/**
 * Data presenter for the task module.
 *
 * Responsibility: map [UIState] into a [TaskModuleState]. No mutations,
 * no event emission, no business logic.
 */
class TaskModuleUI {

    fun present(state: UIState): TaskModuleState {
        val taskId = state.activeTaskId
        if (taskId == null) {
            return TaskModuleState(tasks = emptyList())
        }

        val entry = TaskEntry(
            taskId           = taskId,
            assignmentStatus = deriveAssignmentStatus(state),
            contractorId     = null,
            lifecycleState   = deriveLifecycleState(state)
        )

        return TaskModuleState(tasks = listOf(entry))
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private fun deriveAssignmentStatus(state: UIState): String = when (state.phase) {
        "task_assigned",
        "task_started",
        "task_completed",
        "task_validated" -> "assigned"
        "task_failed"    -> "failed"
        else             -> "unassigned"
    }

    private fun deriveLifecycleState(state: UIState): TaskLifecycleState = when (state.phase) {
        "task_assigned"  -> TaskLifecycleState.ASSIGNED
        "task_started"   -> TaskLifecycleState.STARTED
        "task_completed" -> TaskLifecycleState.COMPLETED
        "task_validated" -> TaskLifecycleState.VALIDATED
        "task_failed"    -> TaskLifecycleState.FAILED
        else             -> TaskLifecycleState.IDLE
    }
}
