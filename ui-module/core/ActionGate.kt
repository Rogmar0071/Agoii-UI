package agoii.ui.core

import agoii.ui.bridge.UIReplayState

/**
 * Availability flags for user-facing actions.
 * All fields are derived exclusively from [UIReplayState] — no external dependencies.
 */
data class ActionAvailability(
    val canApproveContracts: Boolean,
    val canStartExecution: Boolean,
    val canRetry: Boolean
)

/**
 * Derives which actions are available to the user given the current [UIReplayState].
 *
 * Rules:
 * - [ActionAvailability.canApproveContracts]: contracts must be valid and execution not yet started.
 * - [ActionAvailability.canStartExecution]: contracts valid, execution not yet started.
 * - [ActionAvailability.canRetry]: execution started but not fully completed.
 *
 * All rules are derived from [UIReplayState] ONLY — no side effects, no I/O.
 */
class ActionGate {

    fun evaluate(state: UIReplayState): ActionAvailability = ActionAvailability(
        canApproveContracts = canApproveContracts(state),
        canStartExecution   = canStartExecution(state),
        canRetry            = canRetry(state)
    )

    // ── private rules — each maps directly to UIReplayState fields ────

    private fun canApproveContracts(state: UIReplayState): Boolean =
        state.auditView.contracts.valid && state.auditView.execution.assignedTasks == 0

    private fun canStartExecution(state: UIReplayState): Boolean =
        state.auditView.contracts.valid && state.auditView.execution.assignedTasks == 0

    private fun canRetry(state: UIReplayState): Boolean {
        val av = state.auditView
        val fullyExecuted = av.execution.totalTasks > 0 &&
            av.execution.validatedTasks == av.execution.totalTasks
        return av.execution.assignedTasks > 0 && !fullyExecuted
    }
}
