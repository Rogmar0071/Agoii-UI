package com.agoii.mobile.observability

import com.agoii.mobile.core.*

class ExecutionObservability(
    private val ledger: EventLedger
) {

    fun trace(projectId: String): ExecutionTrace {
        val events = ledger.loadEvents(projectId)
        val last   = events.lastOrNull()

        val stage  = resolveStage(last)
        val status = resolveStatus(events, last)
        val failure = resolveFailureSurface(events, last)

        val contractsTotal     = extractContractsTotal(events)
        val contractsCompleted = countCompletedContracts(events)

        return ExecutionTrace(
            projectId = projectId,
            stage = stage,
            status = status,
            totalEvents = events.size,
            contractsTotal = contractsTotal,
            contractsCompleted = contractsCompleted,
            failure = failure
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

    private fun resolveStatus(events: List<Event>, last: Event?): ExecutionStatus {
        if (events.isEmpty()) return ExecutionStatus.NOT_STARTED
        if (last?.type == EventTypes.EXECUTION_COMPLETED) return ExecutionStatus.COMPLETED

        if (last?.type == EventTypes.INTENT_SUBMITTED) return ExecutionStatus.BLOCKED

        if (last?.type == EventTypes.CONTRACTS_GENERATED &&
            events.none { it.type == EventTypes.CONTRACTS_APPROVED }) {
            return ExecutionStatus.BLOCKED
        }

        if (last?.type == EventTypes.CONTRACT_STARTED &&
            events.none { it.type == EventTypes.CONTRACT_COMPLETED }) {
            return ExecutionStatus.BLOCKED
        }

        if (last?.type == EventTypes.CONTRACT_FAILED) return ExecutionStatus.BLOCKED
        if (last?.type == EventTypes.TASK_FAILED) return ExecutionStatus.BLOCKED

        return ExecutionStatus.IN_PROGRESS
    }

    private fun resolveFailureSurface(events: List<Event>, last: Event?): FailureSurface {
        if (events.isEmpty()) {
            return FailureSurface(FailureType.NONE, null, null)
        }

        if (last?.type == EventTypes.EXECUTION_COMPLETED) {
            return FailureSurface(FailureType.NONE, null, null)
        }

        // INTENT BLOCK (no contracts generated)
        if (last?.type == EventTypes.INTENT_SUBMITTED) {
            val hasContracts = events.any { it.type == EventTypes.CONTRACTS_GENERATED }
            if (!hasContracts) {
                return FailureSurface(
                    FailureType.CONTRACT_GENERATION_FAILED,
                    "No contracts generated from intent",
                    last.type
                )
            }
        }

        // AUTHORIZATION BLOCK (contracts not approved)
        if (last?.type == EventTypes.CONTRACTS_GENERATED &&
            events.none { it.type == EventTypes.CONTRACTS_APPROVED }) {
            return FailureSurface(
                FailureType.AUTHORIZATION_FAILED,
                "Contracts generated but not approved",
                last.type
            )
        }

        // CONTRACT FAILURE
        if (last?.type == EventTypes.CONTRACT_FAILED) {
            return FailureSurface(
                FailureType.CONTRACT_FAILED,
                "Contract execution failed",
                last.type
            )
        }

        // TASK FAILURE
        if (last?.type == EventTypes.TASK_FAILED) {
            return FailureSurface(
                FailureType.TASK_FAILED,
                "Task execution failed",
                last.type
            )
        }

        // EXECUTION STALL (stuck in contract, no prior completions)
        if (last?.type == EventTypes.CONTRACT_STARTED &&
            events.none { it.type == EventTypes.CONTRACT_COMPLETED }) {
            return FailureSurface(
                FailureType.EXECUTION_BLOCKED,
                "Execution stalled at contract stage",
                last.type
            )
        }

        return FailureSurface(FailureType.NONE, null, null)
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
