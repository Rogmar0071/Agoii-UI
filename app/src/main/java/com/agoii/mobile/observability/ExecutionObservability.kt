package com.agoii.mobile.observability

import com.agoii.mobile.core.*

class ExecutionObservability(
    private val ledger: EventLedger
) {

    fun trace(projectId: String): ExecutionTrace {
        val events = ledger.loadEvents(projectId)
        val last   = events.lastOrNull()

        val stage = resolveStage(last)
        val (status, failureReason, failureStage) = resolveFailure(events, last)

        val contractsTotal     = extractContractsTotal(events)
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
            EventTypes.INTENT_SUBMITTED      -> ExecutionStage.INTENT
            EventTypes.CONTRACTS_GENERATED   -> ExecutionStage.CONTRACTS_GENERATED
            EventTypes.CONTRACTS_APPROVED    -> ExecutionStage.CONTRACTS_APPROVED
            EventTypes.CONTRACT_STARTED      -> ExecutionStage.CONTRACT_STARTED
            EventTypes.CONTRACT_COMPLETED    -> ExecutionStage.CONTRACT_COMPLETED
            EventTypes.EXECUTION_COMPLETED   -> ExecutionStage.EXECUTION_COMPLETED
            else -> ExecutionStage.NONE
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

        // ✅ Correct classification — DO NOT CHANGE
        if (last?.type == EventTypes.INTENT_SUBMITTED) {
            return Triple(
                ExecutionStatus.BLOCKED,
                "Contracts not generated from intent",
                FailureStage.CONTRACT_GENERATION
            )
        }

        if (last?.type == EventTypes.CONTRACTS_GENERATED &&
            events.none { it.type == EventTypes.CONTRACTS_APPROVED }) {

            return Triple(
                ExecutionStatus.BLOCKED,
                "Contracts generated but not approved",
                FailureStage.CONTRACT_APPROVAL
            )
        }

        if (last?.type == EventTypes.CONTRACT_STARTED &&
            events.none { it.type == EventTypes.CONTRACT_COMPLETED }) {

            return Triple(
                ExecutionStatus.BLOCKED,
                "Contract execution started but not completed",
                FailureStage.CONTRACT_EXECUTION
            )
        }

        if (last?.type == EventTypes.CONTRACT_FAILED) {
            return Triple(
                ExecutionStatus.BLOCKED,
                "Contract execution failed",
                FailureStage.CONTRACT_EXECUTION
            )
        }

        if (last?.type == EventTypes.TASK_FAILED) {
            return Triple(
                ExecutionStatus.BLOCKED,
                "Task execution failed",
                FailureStage.TASK_EXECUTION
            )
        }

        return Triple(
            ExecutionStatus.IN_PROGRESS,
            null,
            FailureStage.NONE
        )
    }

    fun timeline(projectId: String): ExecutionTimeline {
        val events = ledger.loadEvents(projectId)

        val steps = events.mapIndexed { index, event ->
            TimelineStep(
                index = index + 1,
                eventType = event.type,
                label = mapLabel(event.type),
                description = mapDescription(event)
            )
        }

        return ExecutionTimeline(
            projectId = projectId,
            steps = steps
        )
    }

    private fun mapLabel(type: String): String {
        return when (type) {
            EventTypes.INTENT_SUBMITTED    -> "Intent Submitted"
            EventTypes.CONTRACTS_GENERATED -> "Contracts Generated"
            EventTypes.CONTRACTS_APPROVED  -> "Contracts Approved"
            EventTypes.CONTRACT_STARTED    -> "Contract Started"
            EventTypes.CONTRACT_COMPLETED  -> "Contract Completed"
            EventTypes.CONTRACT_FAILED     -> "Contract Failed"
            EventTypes.TASK_FAILED         -> "Task Failed"
            EventTypes.EXECUTION_COMPLETED -> "Execution Completed"
            else -> "Unknown Step"
        }
    }

    private fun mapDescription(event: Event): String {
        return when (event.type) {
            EventTypes.INTENT_SUBMITTED ->
                "Objective: ${event.payload["objective"] ?: "Not specified"}"

            EventTypes.CONTRACTS_GENERATED ->
                "Total contracts: ${event.payload["total"] ?: "Unknown"}"

            EventTypes.CONTRACT_STARTED ->
                "Executing contract: ${event.payload["contract_id"] ?: "Unknown"}"

            EventTypes.CONTRACT_COMPLETED ->
                "Contract completed: ${event.payload["contract_id"] ?: "Unknown"}"

            EventTypes.CONTRACT_FAILED ->
                "Contract failed: ${event.payload["contract_id"] ?: "Unknown"}"

            EventTypes.TASK_FAILED ->
                "Task failure occurred"

            EventTypes.EXECUTION_COMPLETED ->
                "Execution fully completed"

            else -> "No additional details"
        }
    }

    private fun extractContractsTotal(events: List<Event>): Int? {
        val event = events.firstOrNull { it.type == EventTypes.CONTRACTS_GENERATED }
        return (event?.payload?.get("total") as? Number)?.toInt()
    }

    private fun countCompletedContracts(events: List<Event>): Int {
        return events.count { it.type == EventTypes.CONTRACT_COMPLETED }
    }
}
