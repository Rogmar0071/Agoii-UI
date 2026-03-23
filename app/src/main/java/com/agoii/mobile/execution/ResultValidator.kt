package com.agoii.mobile.execution

import com.agoii.mobile.tasks.Task

// ─── ResultValidator ──────────────────────────────────────────────────────────

/**
 * Outcome of a result validation pass.
 *
 * @property verdict        [ValidationVerdict.VALIDATED] or [ValidationVerdict.FAILED].
 * @property failureReasons Ordered list of rule violations found; empty when VALIDATED.
 */
data class ValidationResult(
    val verdict:        ValidationVerdict,
    val failureReasons: List<String> = emptyList()
)

/** Verdict of [ResultValidator.validate]. */
enum class ValidationVerdict { VALIDATED, FAILED }

/**
 * ResultValidator — evaluates a [ContractorExecutionOutput] against the originating [Task].
 *
 * Validation criteria (all three must pass for VALIDATED):
 *  1. Output matches task objective — result artifact is non-empty and contains a taskId.
 *  2. Constraint compliance — all task constraints are represented in the artifact.
 *  3. Structural correctness — execution status is SUCCESS and no error is present.
 *
 * Rules:
 *  - Pure function: no side effects, no event emissions (caller handles events).
 *  - All rule failures are collected before returning (no early-exit on first failure).
 *  - Returns VALIDATED only when ALL rules pass.
 */
class ResultValidator {

    /**
     * Validate [output] produced for [task].
     *
     * @param task   The originating task carrying the objective and validation rules.
     * @param output The artifact returned by [ContractorExecutor.execute].
     * @return       [ValidationResult] with VALIDATED verdict or accumulated failures.
     */
    fun validate(task: Task, output: ContractorExecutionOutput): ValidationResult {
        val failures = mutableListOf<String>()

        // Rule 1 — objective match: artifact must be non-empty and reference the task.
        if (!objectiveMatches(task, output)) {
            failures += "Output does not satisfy task objective: " +
                "'${task.description}' (artifact taskId mismatch or empty result)"
        }

        // Rule 2 — constraint compliance: task constraints must appear in the artifact.
        val missingConstraints = constraintViolations(task, output)
        if (missingConstraints.isNotEmpty()) {
            failures += "Constraint violations: ${missingConstraints.joinToString()}"
        }

        // Rule 3 — structural correctness: execution must have succeeded with no error.
        if (!structurallyCorrect(output)) {
            failures += "Structural failure: execution status=${output.status}" +
                if (output.error != null) ", error='${output.error}'" else ""
        }

        return if (failures.isEmpty()) {
            ValidationResult(verdict = ValidationVerdict.VALIDATED)
        } else {
            ValidationResult(verdict = ValidationVerdict.FAILED, failureReasons = failures)
        }
    }

    // ─── Individual rule implementations ─────────────────────────────────────

    private fun objectiveMatches(task: Task, output: ContractorExecutionOutput): Boolean {
        if (output.resultArtifact.isEmpty()) return false
        val artifactTaskId = output.resultArtifact["taskId"] as? String ?: return false
        return artifactTaskId == task.taskId
    }

    private fun constraintViolations(task: Task, output: ContractorExecutionOutput): List<String> {
        if (task.constraints.isEmpty()) return emptyList()
        @Suppress("UNCHECKED_CAST")
        val metConstraints = output.resultArtifact["constraintsMet"] as? List<String>
            ?: return task.constraints
        return task.constraints.filter { it !in metConstraints }
    }

    private fun structurallyCorrect(output: ContractorExecutionOutput): Boolean =
        output.status == ExecutionStatus.SUCCESS && output.error == null
}
