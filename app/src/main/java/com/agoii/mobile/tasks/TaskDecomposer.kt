package com.agoii.mobile.tasks

import com.agoii.mobile.contracts.ContractDerivation
import com.agoii.mobile.contracts.ContractOutcome
import com.agoii.mobile.contracts.ExecutionStep

// ─── TaskDecomposer ───────────────────────────────────────────────────────────

/**
 * TaskDecomposer — converts a [ContractDerivation] into an ordered [TaskGraph].
 *
 * Mapping rules (one task per execution step):
 *  - [Task.taskId]               = "<contractRef>-step<stepPosition>"
 *  - [Task.contractReference]    = [contractReference]
 *  - [Task.stepReference]        = [ExecutionStep.position]
 *  - [Task.module]               = [ExecutionStep.module].name
 *  - [Task.description]          = [ExecutionStep.description]
 *  - [Task.requiredCapabilities] = derived from [ExecutionStep.load] and module weight
 *  - [Task.constraints]          = constraint violation labels from the derivation
 *  - [Task.expectedOutput]       = "Completed step <pos>: <description>"
 *  - [Task.validationRules]      = ["output_matches_description", "no_constraint_violation"]
 *  - Initial [Task.assignmentStatus] = BLOCKED (allocated by [TaskAllocator])
 *
 * Rules:
 *  - Only APPROVED derivations produce a non-empty [TaskGraph].
 *  - Pure function: no state, no side effects (events are returned, not emitted).
 *  - Equal inputs always produce equal outputs.
 *
 * @param emitter  Event emitter; callers receive and route the returned events.
 */
class TaskDecomposer(
    private val emitter: TaskEventEmitter = TaskEventEmitter()
) {

    /**
     * Decompose [derivation] into a [TaskGraph].
     *
     * @param contractReference  Human-readable contract identifier for task IDs.
     * @param derivation         The [ContractDerivation] produced by [ContractEngine].
     * @return [DecompositionResult] containing the [TaskGraph] and all emitted events.
     */
    fun decompose(
        contractReference: String,
        derivation:        ContractDerivation
    ): DecompositionResult {
        val events = mutableListOf<TaskEvent>()

        if (derivation.outcome == ContractOutcome.REJECTED) {
            return DecompositionResult(
                taskGraph = TaskGraph(contractReference = contractReference, tasks = emptyList()),
                events    = events
            )
        }

        // Collect constraint labels from the derivation for attachment to tasks.
        val constraintLabels = derivation.constraints.violations.map { it.constraint }

        val tasks = derivation.executionPlan.steps.map { step ->
            val task = buildTask(contractReference, step, constraintLabels)
            events += emitter.taskCreated(task)
            events += emitter.taskReady(task)
            task
        }

        return DecompositionResult(
            taskGraph = TaskGraph(contractReference = contractReference, tasks = tasks),
            events    = events
        )
    }

    // ─── Internal helpers ─────────────────────────────────────────────────────

    private fun buildTask(
        contractReference: String,
        step:              ExecutionStep,
        constraintLabels:  List<String>
    ): Task {
        val taskId = "$contractReference-step${step.position}"

        // Derive minimum capability requirements from the step's execution load.
        // Higher load → higher minimum scores required.
        val minScore = if (step.load >= 3) 2 else 1
        val requiredCapabilities = mapOf(
            "constraintObedience" to minScore,
            "structuralAccuracy"  to minScore,
            "complexityCapacity"  to minScore,
            "reliability"         to minScore
        )

        return Task(
            taskId               = taskId,
            contractReference    = contractReference,
            stepReference        = step.position,
            module               = step.module.name,
            description          = step.description,
            requiredCapabilities = requiredCapabilities,
            constraints          = constraintLabels,
            expectedOutput       = "Completed step ${step.position}: ${step.description}",
            validationRules      = listOf(
                "output_matches_description",
                "no_constraint_violation"
            ),
            assignedContractorId = null,
            assignmentStatus     = TaskAssignmentStatus.BLOCKED
        )
    }
}

// ─── Result ───────────────────────────────────────────────────────────────────

/**
 * Output of [TaskDecomposer.decompose].
 *
 * @property taskGraph  The fully decomposed [TaskGraph].
 * @property events     Ordered list of all events emitted during decomposition.
 */
data class DecompositionResult(
    val taskGraph: TaskGraph,
    val events:    List<TaskEvent>
)
