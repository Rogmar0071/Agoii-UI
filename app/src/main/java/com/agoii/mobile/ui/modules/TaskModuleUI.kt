package com.agoii.mobile.ui.modules

import com.agoii.mobile.ui.core.UIState

/**
 * Presentation model produced by [TaskModuleUI].
 *
 * The task list is always empty: structural state does not carry sufficient
 * information to assert any task lifecycle, assignment status, or identity.
 * No fake task states are introduced.
 */
data class TaskModuleState(
    val tasks: List<Nothing>
)

/**
 * Data presenter for the task module.
 *
 * Responsibility: map [UIState] into a [TaskModuleState]. Because the
 * available structural state cannot represent task lifecycle or assignment,
 * this module produces no-op output (empty task list) rather than asserting
 * any fake lifecycle. No mutations, no event emission, no business logic.
 */
class TaskModuleUI {

    fun present(state: UIState): TaskModuleState = TaskModuleState(tasks = emptyList())
}
