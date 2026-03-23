package com.agoii.mobile.tasks

// ─── TaskEventEmitter ─────────────────────────────────────────────────────────

/**
 * Pure event record produced by task-system state transitions.
 *
 * @property type    One of the [TaskEventTypes] string constants.
 * @property payload Key-value data describing the event.
 */
data class TaskEvent(
    val type:    String,
    val payload: Map<String, Any> = emptyMap()
)

/**
 * TaskEventEmitter — builds typed [TaskEvent] records for every task lifecycle
 * transition.
 *
 * Rules:
 *  - Every emitter method MUST be called before or after the corresponding
 *    state change — no silent transitions anywhere.
 *  - This class is stateless; callers decide how to persist / route events.
 */
class TaskEventEmitter {

    /** Emit when a new [Task] is created by [TaskDecomposer]. */
    fun taskCreated(task: Task): TaskEvent =
        TaskEvent(
            type    = TaskEventTypes.TASK_CREATED,
            payload = mapOf(
                "taskId"            to task.taskId,
                "contractReference" to task.contractReference,
                "stepReference"     to task.stepReference,
                "module"            to task.module
            )
        )

    /** Emit when a [Task] is ready for assignment processing. */
    fun taskReady(task: Task): TaskEvent =
        TaskEvent(
            type    = TaskEventTypes.TASK_READY,
            payload = mapOf(
                "taskId"            to task.taskId,
                "contractReference" to task.contractReference
            )
        )

    /** Emit when assignment lookup begins for a [Task]. */
    fun taskAssignmentRequested(task: Task): TaskEvent =
        TaskEvent(
            type    = TaskEventTypes.TASK_ASSIGNMENT_REQUESTED,
            payload = mapOf(
                "taskId"                 to task.taskId,
                "requiredCapabilities"   to task.requiredCapabilities
            )
        )

    /** Emit when no contractor was found in the registry for a [Task]. */
    fun contractorNotFound(task: Task): TaskEvent =
        TaskEvent(
            type    = TaskEventTypes.CONTRACTOR_NOT_FOUND,
            payload = mapOf(
                "taskId"                 to task.taskId,
                "requiredCapabilities"   to task.requiredCapabilities
            )
        )

    /** Emit when [KnowledgeScout] is triggered to find a contractor for a [Task]. */
    fun contractorDiscoveryTriggered(task: Task): TaskEvent =
        TaskEvent(
            type    = TaskEventTypes.CONTRACTOR_DISCOVERY_TRIGGERED,
            payload = mapOf(
                "taskId"                 to task.taskId,
                "requiredCapabilities"   to task.requiredCapabilities
            )
        )

    /** Emit when a contractor is successfully assigned to a [Task]. */
    fun contractorAssigned(task: Task, contractorId: String): TaskEvent =
        TaskEvent(
            type    = TaskEventTypes.CONTRACTOR_ASSIGNED,
            payload = mapOf(
                "taskId"        to task.taskId,
                "contractorId"  to contractorId
            )
        )

    /** Emit when a [Task] is blocked because no contractor could be found or verified. */
    fun taskBlocked(task: Task): TaskEvent =
        TaskEvent(
            type    = TaskEventTypes.TASK_BLOCKED,
            payload = mapOf(
                "taskId"            to task.taskId,
                "contractReference" to task.contractReference
            )
        )

    /** Emit when a [Task] transitions to fully ready for execution (contractor assigned). */
    fun taskReadyForExecution(task: Task): TaskEvent =
        TaskEvent(
            type    = TaskEventTypes.TASK_READY_FOR_EXECUTION,
            payload = mapOf(
                "taskId"              to task.taskId,
                "assignedContractor"  to (task.assignedContractorId ?: "")
            )
        )
}
