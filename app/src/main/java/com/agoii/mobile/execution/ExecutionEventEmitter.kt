package com.agoii.mobile.execution

import com.agoii.mobile.core.EventRepository
import com.agoii.mobile.core.EventTypes

// ─── ExecutionEventEmitter ────────────────────────────────────────────────────

/**
 * ExecutionEventEmitter — the sole authority for appending execution lifecycle
 * events to the core [EventRepository].
 *
 * Rules:
 *  - All execution state transitions MUST pass through this class.
 *  - No execution code appends events directly to the store.
 *  - Every method maps to exactly one event type constant from [EventTypes].
 *  - This is the ONLY bridge between the execution layer and the core event store.
 */
class ExecutionEventEmitter(private val eventStore: EventRepository) {

    /** Emit when a contractor has been assigned to a task. */
    fun taskAssigned(projectId: String, taskId: String, contractorId: String) {
        eventStore.appendEvent(
            projectId,
            EventTypes.TASK_ASSIGNED,
            mapOf(
                "taskId"       to taskId,
                "contractorId" to contractorId
            )
        )
    }

    /** Emit when a task transitions from assigned to actively executing. */
    fun taskStarted(projectId: String, taskId: String, contractorId: String) {
        eventStore.appendEvent(
            projectId,
            EventTypes.TASK_STARTED,
            mapOf(
                "taskId"       to taskId,
                "contractorId" to contractorId
            )
        )
    }

    /** Emit when a contractor finishes executing a task (before validation). */
    fun taskCompleted(projectId: String, taskId: String, contractorId: String) {
        eventStore.appendEvent(
            projectId,
            EventTypes.TASK_COMPLETED,
            mapOf(
                "taskId"       to taskId,
                "contractorId" to contractorId
            )
        )
    }

    /** Emit when a completed task's result passes validation. */
    fun taskValidated(projectId: String, taskId: String) {
        eventStore.appendEvent(
            projectId,
            EventTypes.TASK_VALIDATED,
            mapOf("taskId" to taskId)
        )
    }

    /** Emit when a task fails (execution error or validation failure). */
    fun taskFailed(
        projectId:  String,
        taskId:     String,
        contractorId: String,
        reason:     String
    ) {
        eventStore.appendEvent(
            projectId,
            EventTypes.TASK_FAILED,
            mapOf(
                "taskId"       to taskId,
                "contractorId" to contractorId,
                "reason"       to reason
            )
        )
    }

    /** Emit when the retry engine reassigns a task to a different contractor. */
    fun contractorReassigned(
        projectId:        String,
        taskId:           String,
        previousContractorId: String,
        newContractorId:  String
    ) {
        eventStore.appendEvent(
            projectId,
            EventTypes.CONTRACTOR_REASSIGNED,
            mapOf(
                "taskId"                 to taskId,
                "previousContractorId"   to previousContractorId,
                "newContractorId"        to newContractorId
            )
        )
    }

    /** Emit when all retry attempts are exhausted and the contract must be marked failed. */
    fun contractFailed(projectId: String, taskId: String, reason: String) {
        eventStore.appendEvent(
            projectId,
            EventTypes.CONTRACT_FAILED,
            mapOf(
                "taskId" to taskId,
                "reason" to reason
            )
        )
    }
}
