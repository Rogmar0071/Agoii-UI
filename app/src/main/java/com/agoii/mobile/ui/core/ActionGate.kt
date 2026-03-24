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
 * All rules are derived from [ReplayStructuralState] ONLY — no side effects, no I/O.
 */
class ActionGate {

    fun evaluate(state: ReplayStructuralState): ActionAvailability = ActionAvailability(
        canApproveContracts = canApproveContracts(state),
        canStartExecution   = canStartExecution(state),
        canRetry            = canRetry(state)
    )

    // ── private rules ─────────────────────────────────────────────────────────

    private fun canApproveContracts(state: ReplayStructuralState): Boolean = false

    private fun canStartExecution(state: ReplayStructuralState): Boolean = false

    private fun canRetry(state: ReplayStructuralState): Boolean = false
}
