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
        val (status, failureReason, failureStage) = resolveFailure(events, last)

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
            failureReason = failureReason,
            failureStage = failureStage
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

    private fun resolveFailure(
        events: List<Event>,
        last: Event?
    ): Triple<ExecutionStatus, String?, FailureStage> {
        if (events.isEmpty()) {
            return Triple(ExecutionStatus.NOT_STARTED, null, FailureStage.NONE)
        }

        if (last?.type == EventTypes.EXECUTION_COMPLETED) {
            return Triple(ExecutionStatus.COMPLETED, null, FailureStage.NONE)
        }

        // B. Authorization Block: intent submitted but contracts never generated
        if (last?.type == EventTypes.INTENT_SUBMITTED &&
            events.none { it.type == EventTypes.CONTRACTS_GENERATED }) {
            return Triple(
                ExecutionStatus.BLOCKED,
                "Authorization block: no contracts generated after intent submission",
                FailureStage.INTENT
            )
        }

        // C. Execution Stall: contract started but not yet completed
        if (last?.type == EventTypes.CONTRACT_STARTED) {
            return Triple(
                ExecutionStatus.BLOCKED,
                "Execution stall: contract started but not completed",
                FailureStage.CONTRACT_EXECUTION
            )
        }

        // D. Lifecycle Dead-End: terminal failure event with no forward transition
        if (last?.type == EventTypes.CONTRACT_FAILED) {
            return Triple(
                ExecutionStatus.BLOCKED,
                "Lifecycle dead-end: no valid forward transition from '${last.type}'",
                FailureStage.CONTRACT_EXECUTION
            )
        }
        if (last?.type == EventTypes.TASK_FAILED) {
            return Triple(
                ExecutionStatus.BLOCKED,
                "Lifecycle dead-end: no valid forward transition from '${last.type}'",
                FailureStage.TASK_EXECUTION
            )
        }

        return Triple(ExecutionStatus.IN_PROGRESS, null, FailureStage.NONE)
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
