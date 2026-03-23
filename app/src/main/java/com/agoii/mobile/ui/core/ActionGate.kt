package com.agoii.mobile.ui.core

import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.core.ReplayState

/**
 * Availability flags for user-facing actions.
 * All fields are derived exclusively from [ReplayState] — no external dependencies.
 */
data class ActionAvailability(
    val canApproveContracts: Boolean,
    val canStartExecution: Boolean,
    val canRetry: Boolean
)

/**
 * Derives which actions are available to the user given the current [ReplayState].
 *
 * Rules:
 * - [ActionAvailability.canApproveContracts]: contracts must be ready but not yet approved.
 * - [ActionAvailability.canStartExecution]: contracts approved, execution not yet started.
 * - [ActionAvailability.canRetry]: execution started but not completed (mid-flight failure window).
 *
 * All rules are derived from [ReplayState] ONLY — no side effects, no I/O.
 */
class ActionGate {

    fun evaluate(state: ReplayState): ActionAvailability = ActionAvailability(
        canApproveContracts = canApproveContracts(state),
        canStartExecution   = canStartExecution(state),
        canRetry            = canRetry(state)
    )

    // ── private rules — each maps directly to a ReplayState field ─────────────

    private fun canApproveContracts(state: ReplayState): Boolean =
        state.phase == EventTypes.CONTRACTS_READY

    private fun canStartExecution(state: ReplayState): Boolean =
        state.phase == EventTypes.CONTRACTS_APPROVED && !state.executionStarted

    private fun canRetry(state: ReplayState): Boolean =
        state.executionStarted && !state.executionCompleted
}
