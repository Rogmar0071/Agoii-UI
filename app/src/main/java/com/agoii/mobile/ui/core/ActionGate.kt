package com.agoii.mobile.ui.core

import com.agoii.mobile.core.ReplayStructuralState

/**
 * Availability flags for user-facing actions.
 * All fields are derived exclusively from [ReplayStructuralState] — no external dependencies.
 */
data class ActionAvailability(
    val canApproveContracts: Boolean,
    val canStartExecution: Boolean,
    val canRetry: Boolean
)

/**
 * Derives which actions are available to the user given the current [ReplayStructuralState].
 *
 * Rules:
 * - [ActionAvailability.canApproveContracts]: contracts must be valid and execution not yet started.
 * - [ActionAvailability.canStartExecution]: contracts valid, execution not yet started.
 * - [ActionAvailability.canRetry]: execution started but not fully completed.
 *
 * All rules are derived from [ReplayStructuralState] ONLY — no side effects, no I/O.
 */
class ActionGate {

    fun evaluate(state: ReplayStructuralState): ActionAvailability = ActionAvailability(
        canApproveContracts = canApproveContracts(state),
        canStartExecution   = canStartExecution(state),
        canRetry            = canRetry(state)
    )

    // ── private rules — each maps directly to ReplayStructuralState fields ────

    private fun canApproveContracts(state: ReplayStructuralState): Boolean =
        state.contracts.valid && state.execution.assignedTasks == 0

    private fun canStartExecution(state: ReplayStructuralState): Boolean =
        state.contracts.valid && state.execution.assignedTasks == 0

    private fun canRetry(state: ReplayStructuralState): Boolean =
        state.execution.assignedTasks > 0 && !state.execution.fullyExecuted
}
