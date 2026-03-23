package com.agoii.mobile.ui.modules

import com.agoii.mobile.ui.core.UIState

/**
 * Immutable presentation model for a single contract entry.
 *
 * @property contractId  Identifier of the contract.
 * @property status      Current status label (e.g. "in_progress", "completed").
 * @property position    One-based position of this contract in the execution sequence.
 * @property total       Total number of contracts in the execution plan.
 */
data class ContractEntry(
    val contractId: String,
    val status: String,
    val position: Int,
    val total: Int
)

/**
 * Presentation model produced by [ContractModuleUI].
 *
 * @property contracts   Ordered list of contract entries.
 */
data class ContractModuleState(
    val contracts: List<ContractEntry>
)

/**
 * Data presenter for the contract module.
 *
 * Responsibility: map [UIState] into a [ContractModuleState] that the UI layer
 * can render directly. No mutations, no event emission, no business logic.
 */
class ContractModuleUI {

    fun present(state: UIState): ContractModuleState {
        val completedCount = deriveCompletedCount(state)
        val total = deriveTotalCount(state)

        val contracts = (1..total).map { position ->
            ContractEntry(
                contractId = "contract-$position",
                status     = deriveStatus(position, completedCount, state),
                position   = position,
                total      = total
            )
        }

        return ContractModuleState(contracts = contracts)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private fun deriveCompletedCount(state: UIState): Int {
        val activeId = state.activeContractId ?: return 0
        return activeId.removePrefix("contract-").toIntOrNull() ?: 0
    }

    private fun deriveTotalCount(state: UIState): Int {
        val progressDenominator = if (state.progress > 0f && state.progress < 1f) {
            deriveCompletedCount(state).toFloat() / state.progress
        } else if (state.isComplete) {
            deriveCompletedCount(state).toFloat()
        } else {
            0f
        }
        val derived = progressDenominator.toInt()
        return if (derived > 0) derived else 0
    }

    private fun deriveStatus(position: Int, completedCount: Int, state: UIState): String = when {
        position <= completedCount      -> "completed"
        state.activeContractId == "contract-$position" -> "in_progress"
        else                            -> "pending"
    }
}
