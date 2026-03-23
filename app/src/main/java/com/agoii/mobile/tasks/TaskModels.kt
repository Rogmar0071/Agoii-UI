package com.agoii.mobile.tasks

// ─── Task System — Foundation Models ─────────────────────────────────────────

// ─── Task ─────────────────────────────────────────────────────────────────────

/**
 * Assignment status of a single [Task].
 *
 *  ASSIGNED              — a verified contractor has been matched.
 *  BLOCKED               — no contractor available; awaiting discovery.
 */
enum class TaskAssignmentStatus { ASSIGNED, BLOCKED }

/**
 * A single atomic, execution-ready task derived from one [ExecutionStep] of a
 * contract's execution plan.
 *
 * Rules:
 *  - Atomic: corresponds to exactly one [ExecutionStep].
 *  - Single responsibility: one module, one action.
 *  - No interpretation needed: [expectedOutput] and [validationRules] fully specify success.
 *
 * @property taskId               Unique task identifier (format: "<contractRef>-step<stepPos>").
 * @property contractReference    Human-readable identifier of the originating contract.
 * @property stepReference        1-based position in the contract's execution plan.
 * @property module               The contract module this task touches.
 * @property description          What this task accomplishes.
 * @property requiredCapabilities Dimension → minimum score needed by the contractor.
 * @property constraints          Constraint labels that the assigned contractor must respect.
 * @property expectedOutput       Human-readable description of the expected output.
 * @property validationRules      Ordered list of rules used to validate the output.
 * @property assignedContractorId The id of the assigned contractor, or null when BLOCKED.
 * @property assignmentStatus     ASSIGNED or BLOCKED.
 */
data class Task(
    val taskId:               String,
    val contractReference:    String,
    val stepReference:        Int,
    val module:               String,
    val description:          String,
    val requiredCapabilities: Map<String, Int>,
    val constraints:          List<String>,
    val expectedOutput:       String,
    val validationRules:      List<String>,
    val assignedContractorId: String?               = null,
    val assignmentStatus:     TaskAssignmentStatus  = TaskAssignmentStatus.BLOCKED
)

// ─── TaskGraph ────────────────────────────────────────────────────────────────

/**
 * Ordered, dependency-aware graph of [Task] instances derived from a single contract.
 *
 * Rules:
 *  - No circular dependencies.
 *  - Full coverage: one task per execution step.
 *  - Tasks at the same depth level may execute in parallel when their modules differ.
 *
 * @property contractReference  Identifier of the contract this graph was derived from.
 * @property tasks              Tasks in execution order (by [Task.stepReference]).
 * @property fullyAssigned      true when every task has [TaskAssignmentStatus.ASSIGNED].
 */
data class TaskGraph(
    val contractReference: String,
    val tasks:             List<Task>
) {
    /** true when every task is ASSIGNED. */
    val fullyAssigned: Boolean get() = tasks.all { it.assignmentStatus == TaskAssignmentStatus.ASSIGNED }

    /** Tasks that are BLOCKED and waiting for a contractor. */
    val blockedTasks: List<Task> get() = tasks.filter { it.assignmentStatus == TaskAssignmentStatus.BLOCKED }
}

// ─── Task Events ──────────────────────────────────────────────────────────────

/** All event type strings emitted by the task system. */
object TaskEventTypes {
    const val TASK_CREATED                   = "task_created"
    const val TASK_READY                     = "task_ready"
    const val TASK_ASSIGNMENT_REQUESTED      = "task_assignment_requested"
    const val CONTRACTOR_NOT_FOUND           = "contractor_not_found"
    const val CONTRACTOR_DISCOVERY_TRIGGERED = "contractor_discovery_triggered"
    const val CONTRACTOR_ASSIGNED            = "contractor_assigned"
    const val TASK_BLOCKED                   = "task_blocked"
    const val TASK_READY_FOR_EXECUTION       = "task_ready_for_execution"
}
