package com.agoii.mobile.observability

import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventLedger
import com.agoii.mobile.core.EventTypes

class ExecutionObservability(
    private val ledger: EventLedger
) {

    fun trace(projectId: String): ExecutionTrace {
        val events = ledger.loadEvents(projectId)
        val last = events.lastOrNull()

        val stage = resolveStage(last)
        val status = resolveStatus(events, last)

        val contractsTotal = extractContractsTotal(events)
        val contractsCompleted = countCompletedContracts(events)

        return ExecutionTrace(
            projectId = projectId,
            status = status,
            currentStage = stage,
            lastEvent = last,
            totalEvents = events.size,
            contractsTotal = contractsTotal,
            contractsCompleted = contractsCompleted,
            failureReason = null,
            failureStage = null
        )
    }

    private fun resolveStage(last: Event?): ExecutionStage {
        return when (last?.type) {
            EventTypes.INTENT_SUBMITTED    -> ExecutionStage.INTENT
            EventTypes.CONTRACTS_GENERATED -> ExecutionStage.CONTRACTS_GENERATED
            EventTypes.CONTRACTS_APPROVED  -> ExecutionStage.CONTRACTS_APPROVED
            EventTypes.CONTRACT_STARTED    -> ExecutionStage.CONTRACT_EXECUTION
            EventTypes.TASK_STARTED,
            EventTypes.TASK_COMPLETED      -> ExecutionStage.TASK_EXECUTION
            EventTypes.EXECUTION_COMPLETED -> ExecutionStage.COMPLETED
            else                           -> ExecutionStage.NONE
        }
    }

    private fun resolveStatus(events: List<Event>, last: Event?): ExecutionStatus {
        if (events.isEmpty()) return ExecutionStatus.NOT_STARTED

        if (last?.type == EventTypes.EXECUTION_COMPLETED) {
            return ExecutionStatus.COMPLETED
        }

        return ExecutionStatus.IN_PROGRESS
    }

    private fun extractContractsTotal(events: List<Event>): Int? {
        val event = events.firstOrNull { it.type == EventTypes.CONTRACTS_GENERATED }
        val total = event?.payload?.get("total")
        return when (total) {
            is Int    -> total
            is Double -> total.toInt()
            is Long   -> total.toInt()
            else      -> null
        }
    }

    private fun countCompletedContracts(events: List<Event>): Int {
        return events.count { it.type == EventTypes.CONTRACT_COMPLETED }
    }
}
