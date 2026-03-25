package com.agoii.mobile.orchestration

import com.agoii.mobile.core.EventRepository
import com.agoii.mobile.core.EventTypes

// ─── ExecutionOrchestrator — Closure Authority ────────────────────────────────

/**
 * ExecutionOrchestrator — sole authority for contract closure events.
 *
 * Responsibilities:
 *  - Emit CONTRACT_COMPLETED for every contract closure.
 *  - Emit EXECUTION_COMPLETED when the final contract closes
 *    (position == DEFAULT_TOTAL_CONTRACTS).
 *
 * Rules:
 *  - MUST NOT emit CONTRACT_STARTED.
 *  - MUST NOT control contract sequencing beyond closure decision.
 *  - MUST NOT infer completion from any source other than position.
 */
class ExecutionOrchestrator(
    private val eventStore: EventRepository
) {

    /**
     * Close a contract at the given position.
     *
     * Always emits CONTRACT_COMPLETED.
     * Emits EXECUTION_COMPLETED only when position == DEFAULT_TOTAL_CONTRACTS.
     *
     * @param projectId  The project ledger to write to.
     * @param contractId The identifier of the contract being closed.
     * @param position   The 1-based position of the contract in the execution sequence.
     */
    fun closeContract(projectId: String, contractId: String, position: Int) {
        eventStore.appendEvent(
            projectId,
            EventTypes.CONTRACT_COMPLETED,
            mapOf(
                "contract_id" to contractId,
                "position"    to position
            )
        )
        if (position == EventTypes.DEFAULT_TOTAL_CONTRACTS) {
            eventStore.appendEvent(projectId, EventTypes.EXECUTION_COMPLETED, emptyMap())
        }
    }
}
