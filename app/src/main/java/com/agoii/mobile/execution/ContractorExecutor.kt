package com.agoii.mobile.execution

import com.agoii.mobile.contractor.ContractorProfile

// ─── ContractorExecutor ───────────────────────────────────────────────────────

/**
 * Input contract for a single contractor execution call.
 *
 * @property taskId              Unique task identifier.
 * @property taskDescription     What the task must accomplish.
 * @property taskPayload         Structured key-value data the contractor needs to execute.
 * @property contractConstraints Constraint labels that bound the execution.
 * @property expectedOutputSchema Human-readable description of the expected output structure.
 */
data class ContractorExecutionInput(
    val taskId:               String,
    val taskDescription:      String,
    val taskPayload:          Map<String, Any>,
    val contractConstraints:  List<String>,
    val expectedOutputSchema: String
)

/**
 * Output contract for a single contractor execution call.
 *
 * @property taskId          Unique task identifier (mirrors the input).
 * @property resultArtifact  The structured output produced by the contractor.
 * @property status          [ExecutionStatus.SUCCESS] or [ExecutionStatus.FAILURE].
 * @property error           Optional error message when [status] is FAILURE.
 */
data class ContractorExecutionOutput(
    val taskId:         String,
    val resultArtifact: Map<String, Any>,
    val status:         ExecutionStatus,
    val error:          String? = null
)

/** Status of a single contractor execution. */
enum class ExecutionStatus { SUCCESS, FAILURE }

/**
 * ContractorExecutor — the ONLY component allowed to invoke a contractor for task execution.
 *
 * Rules:
 *  - The execution layer MUST NOT contain contractor logic; only interface calls.
 *  - Input is always a [ContractorExecutionInput]; output is always a [ContractorExecutionOutput].
 *  - Execution failures are reported via [ExecutionStatus.FAILURE], never thrown as exceptions.
 *  - The executor reads the contractor profile from [ContractorProfile] and invokes the
 *    action deterministically.
 */
class ContractorExecutor {

    /**
     * Execute a task using the given [contractor].
     *
     * Delegation rule: execution logic lives in the contractor (capability vector);
     * the executor only marshals input/output and captures errors.
     *
     * A contractor is considered capable of executing a task when its
     * [ContractorProfile.capabilities.capabilityScore] is greater than zero
     * (at least one capability dimension is non-zero). A score of 0 indicates
     * a contractor with no usable capabilities and is rejected immediately.
     *
     * @param input      The execution contract (task + constraints + schema).
     * @param contractor The verified contractor profile assigned to the task.
     * @return           A [ContractorExecutionOutput] describing the outcome.
     */
    fun execute(
        input:      ContractorExecutionInput,
        contractor: ContractorProfile
    ): ContractorExecutionOutput {
        return try {
            val artifact = runExecution(input, contractor)
            ContractorExecutionOutput(
                taskId         = input.taskId,
                resultArtifact = artifact,
                status         = ExecutionStatus.SUCCESS
            )
        } catch (e: Exception) {
            ContractorExecutionOutput(
                taskId         = input.taskId,
                resultArtifact = emptyMap(),
                status         = ExecutionStatus.FAILURE,
                error          = e.message ?: "Unknown execution error"
            )
        }
    }

    /**
     * Internal execution delegate — maps contractor capabilities onto task payload.
     * This is where the contractor's role is exercised deterministically.
     * No external calls; no randomness; no side effects.
     */
    private fun runExecution(
        input:      ContractorExecutionInput,
        contractor: ContractorProfile
    ): Map<String, Any> {
        val cap = contractor.capabilities

        // Deterministic capability check — no silent failures.
        require(cap.capabilityScore > 0) {
            "Contractor '${contractor.id}' has zero capability score; cannot execute task."
        }

        // Build the result artifact from the task payload and contractor metadata.
        return mapOf(
            "taskId"              to input.taskId,
            "contractorId"        to contractor.id,
            "capabilityScore"     to cap.capabilityScore,
            "constraintsMet"      to input.contractConstraints,
            "outputSchema"        to input.expectedOutputSchema,
            "executionPayload"    to input.taskPayload,
            "reliabilityRatio"    to contractor.reliabilityRatio
        )
    }
}
