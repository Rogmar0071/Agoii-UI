package com.agoii.mobile.ui.core

import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.core.ReplayState

/**
 * Immutable UI-facing representation of the current ledger state.
 * Produced by [StateProjection] from a [ReplayState].
 * No logic lives here — this is a pure data carrier.
 */
data class UIState(
    val phase: String,
    val activeContractId: String?,
    val activeTaskId: String?,
    val progress: Float,
    val isComplete: Boolean
)

/**
 * Pure mapper: [ReplayState] → [UIState].
 * No business logic — every field is a deterministic derivation from the input.
 */
class StateProjection {

    fun project(state: ReplayState): UIState {
        val progress = deriveProgress(state)
        val isComplete = state.phase == EventTypes.ASSEMBLY_COMPLETED

        return UIState(
            phase = state.phase,
            activeContractId = deriveActiveContractId(state),
            activeTaskId = null,
            progress = progress,
            isComplete = isComplete
        )
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private fun deriveProgress(state: ReplayState): Float {
        if (state.totalContracts == 0) return 0f
        return (state.contractsCompleted.toFloat() / state.totalContracts.toFloat())
            .coerceIn(0f, 1f)
    }

    private fun deriveActiveContractId(state: ReplayState): String? {
        return when (state.phase) {
            // contractsCompleted counts fully finished contracts, so the contract
            // currently being executed is position (contractsCompleted + 1).
            EventTypes.CONTRACT_STARTED -> "contract-${state.contractsCompleted + 1}"
            // The contract that just finished is at position contractsCompleted.
            EventTypes.CONTRACT_COMPLETED -> "contract-${state.contractsCompleted}"
            else -> null
        }
    }
}
