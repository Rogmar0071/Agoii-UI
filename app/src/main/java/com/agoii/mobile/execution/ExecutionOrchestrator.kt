package com.agoii.mobile.execution

import com.agoii.mobile.contractor.ContractorProfile
import com.agoii.mobile.tasks.Task

// ─── ExecutionOrchestrator ────────────────────────────────────────────────────

/**
 * Result of [ExecutionOrchestrator.execute].
 *
 * @property success          True when the contractor executed successfully.
 * @property artifact         The output artifact produced by the contractor.
 * @property validationResult Full validation verdict for the artifact.
 * @property error            Optional error message when [success] is false.
 */
data class ExecutionResult(
    val success:          Boolean,
    val artifact:         Map<String, Any>,
    val validationResult: ValidationResult,
    val error:            String? = null
)

/**
 * ExecutionOrchestrator — pure execution worker (DEPRECATED — not part of primary flow).
 *
 * This class performs task execution and validation as a pure compute step with no event
 * emission. It is not part of the primary ledger-driven execution flow and should not be
 * wired into any event-producing pipeline.
 *
 * Responsibilities (only):
 *  - Receive a [Task] and a [ContractorProfile].
 *  - Call [ContractorExecutor] to execute the task.
 *  - Call [ResultValidator] to validate the output.
 *  - Return [ExecutionResult].
 *
 * Rules:
 *  - NO event emission.
 *  - NO lifecycle decisions.
 *  - NO internal state progression.
 *  - Deterministic: same task + same contractor → same result.
 *
 * @property executor  Contractor execution interface.
 * @property validator Result validation engine.
 */
@Deprecated(
    message = "Not part of the primary ledger-driven execution flow. Use Governor for lifecycle events.",
    level = DeprecationLevel.WARNING
)
class ExecutionOrchestrator(
    private val executor:  ContractorExecutor = ContractorExecutor(),
    private val validator: ResultValidator    = ResultValidator()
) {

    /**
     * Execute [task] using [contractor] and validate the output.
     *
     * @param task       The task to execute.
     * @param contractor The verified contractor assigned to the task.
     * @return           [ExecutionResult] with execution status and validation verdict.
     */
    fun execute(task: Task, contractor: ContractorProfile): ExecutionResult {
        val input = ContractorExecutionInput(
            taskId               = task.taskId,
            taskDescription      = task.description,
            taskPayload          = mapOf(
                "module"        to task.module,
                "stepReference" to task.stepReference
            ),
            contractConstraints  = task.constraints,
            expectedOutputSchema = task.expectedOutput
        )
        val output     = executor.execute(input, contractor)
        val validation = validator.validate(task, output)
        return ExecutionResult(
            success          = output.status == ExecutionStatus.SUCCESS,
            artifact         = output.resultArtifact,
            validationResult = validation,
            error            = output.error
        )
    }
}
