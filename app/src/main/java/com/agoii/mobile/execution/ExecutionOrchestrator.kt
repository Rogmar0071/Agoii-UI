package com.agoii.mobile.execution

import com.agoii.mobile.contractor.ContractorProfile
import com.agoii.mobile.contractor.ContractorRegistry
import com.agoii.mobile.core.EventRepository
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.tasks.Task
import com.agoii.mobile.tasks.TaskGraph

// ─── ExecutionOrchestrator ────────────────────────────────────────────────────

/**
 * Result of a single [ExecutionOrchestrator.step] call.
 *
 *  ADVANCED  — one event was emitted and the task moved to the next state.
 *  COMPLETED — the task reached a terminal state (VALIDATED or FAILED).
 *  WAITING   — no contractor is available for the active task.
 *  NO_TASKS  — the task graph contains no tasks to process.
 */
enum class ExecutionStepResult { ADVANCED, COMPLETED, WAITING, NO_TASKS }

/**
 * ExecutionOrchestrator — drives the task execution lifecycle one step at a time.
 *
 * Architecture:
 *  - Reads the latest event state from [eventStore].
 *  - Determines the next valid lifecycle transition for the active task.
 *  - Emits EXACTLY ONE event per [step] call via [ExecutionEventEmitter].
 *  - Delegates contractor invocation to [ContractorExecutor].
 *  - Delegates result validation to [ResultValidator].
 *  - Delegates failure recovery to [RetryEngine].
 *
 * Principles:
 *  - Event-driven: every state change produces an event; no silent transitions.
 *  - Append-only: no mutation of past state.
 *  - One step = one transition: no batch transitions, no hidden loops.
 *  - Deterministic: same event stream → same outcome.
 *  - Execution never bypasses the core [EventRepository].
 *
 * @property eventStore  Core event repository — the single source of truth.
 * @property registry    Contractor registry — used by the retry engine for reassignment.
 * @property executor    Contractor execution interface.
 * @property validator   Result validation engine.
 * @property lifecycle   Transition guard enforcing valid lifecycle state changes.
 * @property emitter     Event emitter — the only path to write execution events.
 * @property retryEngine Failure recovery decision engine.
 */
class ExecutionOrchestrator(
    private val eventStore:  EventRepository,
    private val registry:    ContractorRegistry,
    private val executor:    ContractorExecutor    = ContractorExecutor(),
    private val validator:   ResultValidator       = ResultValidator(),
    private val lifecycle:   TaskLifecycleManager  = TaskLifecycleManager(),
    private val emitter:     ExecutionEventEmitter = ExecutionEventEmitter(eventStore),
    private val retryEngine: RetryEngine           = RetryEngine(registry)
) {

    // Per-task in-memory attempt tracking (reset on reassignment).
    // Key: taskId, Value: current attempt count for the current contractor.
    private val attemptCounts: MutableMap<String, Int> = mutableMapOf()

    // Per-task lifecycle state — kept in sync with the event ledger on every step.
    private val taskStates: MutableMap<String, TaskLifecycleState> = mutableMapOf()

    // Per-task last execution output — stored after TASK_STARTED so TASK_COMPLETED
    // can validate the same artifact without re-running the contractor.
    private val lastOutputs: MutableMap<String, ContractorExecutionOutput> = mutableMapOf()

    /**
     * Process one step of the execution lifecycle for the first non-terminal task
     * in [graph].
     *
     * One call = one event emitted. The caller must invoke [step] repeatedly until
     * every task reaches a terminal state.
     *
     * @param projectId Identifies the ledger in [EventRepository].
     * @param graph     The task graph produced by the task allocation system.
     * @return          [ExecutionStepResult] describing what happened.
     */
    fun step(projectId: String, graph: TaskGraph): ExecutionStepResult {
        if (graph.tasks.isEmpty()) return ExecutionStepResult.NO_TASKS

        // Sync in-memory state with the event ledger before every step.
        syncStateFromEvents(projectId, graph)

        // Find the first task that has not yet reached a terminal state.
        val activeTask = graph.tasks.firstOrNull { task ->
            val state = taskStates[task.taskId] ?: TaskLifecycleState.TASK_READY
            !lifecycle.isTerminal(state)
        } ?: return ExecutionStepResult.COMPLETED

        val contractor = resolveContractor(activeTask)
            ?: return ExecutionStepResult.WAITING

        return advanceTask(projectId, activeTask, contractor)
    }

    // ─── Lifecycle step dispatch ───────────────────────────────────────────────

    private fun advanceTask(
        projectId:  String,
        task:       Task,
        contractor: ContractorProfile
    ): ExecutionStepResult {
        val currentState = taskStates[task.taskId] ?: TaskLifecycleState.TASK_READY

        return when (currentState) {

            // ── TASK_READY → TASK_ASSIGNED ────────────────────────────────────
            TaskLifecycleState.TASK_READY -> {
                emitter.taskAssigned(projectId, task.taskId, contractor.id)
                taskStates[task.taskId] = TaskLifecycleState.TASK_ASSIGNED
                ExecutionStepResult.ADVANCED
            }

            // ── TASK_ASSIGNED → TASK_STARTED ──────────────────────────────────
            TaskLifecycleState.TASK_ASSIGNED -> {
                emitter.taskStarted(projectId, task.taskId, contractor.id)
                taskStates[task.taskId] = TaskLifecycleState.TASK_STARTED
                ExecutionStepResult.ADVANCED
            }

            // ── TASK_STARTED → TASK_COMPLETED (or task_failed on error) ───────
            TaskLifecycleState.TASK_STARTED -> {
                val input  = buildExecutionInput(task)
                val output = executor.execute(input, contractor)

                return if (output.status == ExecutionStatus.SUCCESS) {
                    lastOutputs[task.taskId] = output
                    emitter.taskCompleted(projectId, task.taskId, contractor.id)
                    taskStates[task.taskId] = TaskLifecycleState.TASK_COMPLETED
                    ExecutionStepResult.ADVANCED
                } else {
                    handleFailure(
                        projectId, task, contractor,
                        output.error ?: "Execution error"
                    )
                }
            }

            // ── TASK_COMPLETED → TASK_VALIDATED / TASK_FAILED ─────────────────
            // Validates the artifact produced during TASK_STARTED; never re-runs
            // the contractor.
            TaskLifecycleState.TASK_COMPLETED -> {
                val output = lastOutputs[task.taskId]
                    ?: ContractorExecutionOutput(
                        taskId         = task.taskId,
                        resultArtifact = emptyMap(),
                        status         = ExecutionStatus.FAILURE,
                        error          = "No execution output found for validation"
                    )
                val result = validator.validate(task, output)

                return if (result.verdict == ValidationVerdict.VALIDATED) {
                    emitter.taskValidated(projectId, task.taskId)
                    taskStates[task.taskId] = TaskLifecycleState.TASK_VALIDATED
                    ExecutionStepResult.COMPLETED
                } else {
                    val reason = result.failureReasons.joinToString("; ")
                    handleFailure(projectId, task, contractor, reason)
                }
            }

            // ── Terminal states — nothing to do ───────────────────────────────
            TaskLifecycleState.TASK_VALIDATED,
            TaskLifecycleState.TASK_FAILED -> ExecutionStepResult.COMPLETED
        }
    }

    // ─── Failure handling ─────────────────────────────────────────────────────

    private fun handleFailure(
        projectId:  String,
        task:       Task,
        contractor: ContractorProfile,
        reason:     String
    ): ExecutionStepResult {
        val attempts = attemptCounts.merge(task.taskId, 1, Int::plus) ?: 1
        val outcome  = retryEngine.evaluate(task, contractor, attempts)

        return when (outcome.decision) {
            RetryDecision.RETRY -> {
                emitter.taskFailed(projectId, task.taskId, contractor.id, outcome.reason)
                taskStates[task.taskId] = TaskLifecycleState.TASK_READY
                ExecutionStepResult.ADVANCED
            }

            RetryDecision.REASSIGN -> {
                val newContractor = outcome.assignedContractor!!
                emitter.taskFailed(projectId, task.taskId, contractor.id, reason)
                emitter.contractorReassigned(
                    projectId,
                    task.taskId,
                    contractor.id,
                    newContractor.id
                )
                attemptCounts[task.taskId] = 0
                taskStates[task.taskId] = TaskLifecycleState.TASK_READY
                ExecutionStepResult.ADVANCED
            }

            RetryDecision.ESCALATE -> {
                emitter.taskFailed(projectId, task.taskId, contractor.id, reason)
                emitter.contractFailed(projectId, task.taskId, outcome.reason)
                taskStates[task.taskId] = TaskLifecycleState.TASK_FAILED
                ExecutionStepResult.COMPLETED
            }
        }
    }

    // ─── State synchronisation ────────────────────────────────────────────────

    /**
     * Derive per-task lifecycle states by replaying the event ledger in order.
     * This is the only source of truth: in-memory [taskStates] always mirrors events.
     */
    private fun syncStateFromEvents(projectId: String, graph: TaskGraph) {
        val events = eventStore.loadEvents(projectId)
        for (task in graph.tasks) {
            val id = task.taskId
            for (event in events) {
                if (event.payload["taskId"] != id) continue
                when (event.type) {
                    EventTypes.TASK_ASSIGNED   -> taskStates[id] = TaskLifecycleState.TASK_ASSIGNED
                    EventTypes.TASK_STARTED    -> taskStates[id] = TaskLifecycleState.TASK_STARTED
                    EventTypes.TASK_COMPLETED  -> taskStates[id] = TaskLifecycleState.TASK_COMPLETED
                    EventTypes.TASK_VALIDATED  -> taskStates[id] = TaskLifecycleState.TASK_VALIDATED
                    EventTypes.TASK_FAILED     -> taskStates[id] = TaskLifecycleState.TASK_READY
                    EventTypes.CONTRACT_FAILED -> taskStates[id] = TaskLifecycleState.TASK_FAILED
                }
            }
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun resolveContractor(task: Task): ContractorProfile? =
        task.assignedContractorId?.let { id ->
            registry.allVerified().find { it.id == id }
        } ?: registry.findBestMatch(task.requiredCapabilities)

    private fun buildExecutionInput(task: Task): ContractorExecutionInput =
        ContractorExecutionInput(
            taskId               = task.taskId,
            taskDescription      = task.description,
            taskPayload          = mapOf(
                "module"        to task.module,
                "stepReference" to task.stepReference
            ),
            contractConstraints  = task.constraints,
            expectedOutputSchema = task.expectedOutput
        )
}
