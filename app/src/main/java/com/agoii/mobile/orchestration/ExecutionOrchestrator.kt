package com.agoii.mobile.orchestration

import com.agoii.mobile.core.EventRepository
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.governance.ContractDescriptor
import com.agoii.mobile.governance.ContractGate
import com.agoii.mobile.governance.SurfaceType

// ─── Orchestrator — Execution Sequencing and Lifecycle Closure Authority ──────

/**
 * ExecutionOrchestrator — sole authority for contract and execution lifecycle closure.
 *
 * Responsibilities:
 *  - Owns the decision of whether a given execution position may proceed.
 *  - Calls [ContractGate.approve] for each position; never evaluates CSL directly.
 *  - SOLE emitter of [EventTypes.CONTRACT_COMPLETED] and [EventTypes.EXECUTION_COMPLETED].
 *  - [closeContract] is the ONLY path through which those two events enter the ledger.
 *
 * Laws:
 *  - No other module may emit CONTRACT_COMPLETED or EXECUTION_COMPLETED.
 *  - [closeContract] emits CONTRACT_COMPLETED unconditionally after TASK_VALIDATED.
 *  - [closeContract] emits EXECUTION_COMPLETED only when [position] equals the total
 *    contract count, meaning all contracts have completed.
 *  - No substitutions, no defaults, no inferred state.
 */
class ExecutionOrchestrator(
    private val gate:       ContractGate,
    private val eventStore: EventRepository
) {

    /**
     * Returns true when the contract at [position] is permitted to execute,
     * as determined by the governance gate.
     */
    fun canExecute(position: Int): Boolean {
        return gate.approve(
            ContractDescriptor(
                surface           = SurfaceType.LG,
                executionCount    = position,
                conditionCount    = 0,
                validationCapacity = VC
            )
        )
    }

    /**
     * Closes the contract identified by [contractId] at [position] after TASK_VALIDATED.
     *
     * Emits:
     *  1. [EventTypes.CONTRACT_COMPLETED] — always, immediately.
     *  2. [EventTypes.EXECUTION_COMPLETED] — only when [position] equals
     *     [EventTypes.DEFAULT_TOTAL_CONTRACTS], indicating all contracts are done.
     *
     * This is the SOLE write path for these two events. No other module may emit them.
     *
     * @param projectId  The project whose ledger receives the events.
     * @param contractId The identifier of the completed contract.
     * @param position   One-based position of this contract in the execution sequence.
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
        if (position >= EventTypes.DEFAULT_TOTAL_CONTRACTS) {
            eventStore.appendEvent(projectId, EventTypes.EXECUTION_COMPLETED, emptyMap())
        }
    }

    companion object {
        private const val VC = 5
    }
}
