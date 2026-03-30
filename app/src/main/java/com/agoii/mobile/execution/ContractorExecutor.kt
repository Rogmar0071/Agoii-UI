package com.agoii.mobile.execution

import com.agoii.mobile.contractor.ContractorProfile
import com.agoii.mobile.core.LedgerValidationException

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
 * ContractorExecutor — routes contractor execution through the [DriverRegistry].
 *
 * CONTRACT: AGOII-RCF-EXECUTION-INFRASTRUCTURE-01
 *
 * RULES:
 *  - ALL execution MUST go through a registered [ExecutionDriver].
 *  - No driver for [ContractorProfile.source] → throws [LedgerValidationException].
 *  - No stub logic, no fake artifacts, no silent fallbacks.
 *  - The executor is the ONLY component that calls [DriverRegistry.resolve].
 *
 * @param driverRegistry  The registry of registered execution drivers.
 *                        Defaults to an empty registry — no drivers are pre-registered.
 */
class ContractorExecutor(
    private val driverRegistry: DriverRegistry = DriverRegistry()
) {

    /**
     * Execute [input] using the driver registered for [contractor.source].
     *
     * @param input      The execution contract (task + constraints + schema).
     * @param contractor The verified contractor profile assigned to the task.
     * @return           [ContractorExecutionOutput] from the resolved driver.
     * @throws LedgerValidationException when no driver is registered for [contractor.source].
     */
    fun execute(
        input:      ContractorExecutionInput,
        contractor: ContractorProfile
    ): ContractorExecutionOutput {
        val driver = driverRegistry.resolve(contractor.source)
            ?: throw LedgerValidationException(
                "ICS BLOCKED: No execution driver for source: ${contractor.source}"
            )
        return driver.execute(input)
    }
}
