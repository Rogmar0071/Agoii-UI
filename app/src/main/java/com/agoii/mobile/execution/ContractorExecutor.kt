package com.agoii.mobile.execution

import com.agoii.mobile.contractor.ContractorProfile
import com.agoii.mobile.core.LedgerValidationException

// ─── ContractorExecution ─────────────────────────────────────────────────────

data class ContractorExecutionInput(
    val taskId:               String,
    val taskDescription:      String,
    val taskPayload:          Map<String, Any>,
    val contractConstraints:  List<String>,
    val expectedOutputSchema: String
) {
    /**
     * Canonical execution payload.
     *
     * This is the ONLY structure allowed to leave the system toward drivers.
     * No interpretation, no enrichment, no transformation beyond this mapping.
     */
    fun toExecutionPayload(): Map<String, Any> {
        return mapOf(
            "taskId"               to taskId,
            "taskDescription"      to taskDescription,
            "taskPayload"          to taskPayload,
            "contractConstraints"  to contractConstraints,
            "expectedOutputSchema" to expectedOutputSchema
        )
    }
}

data class ContractorExecutionOutput(
    val taskId:         String,
    val resultArtifact: Map<String, Any>,
    val status:         ExecutionStatus,
    val error:          String? = null
)

enum class ExecutionStatus { SUCCESS, FAILURE }

class ContractorExecutor(
    private val driverRegistry: DriverRegistry = DriverRegistry()
) {

    fun execute(
        input:      ContractorExecutionInput,
        contractor: ContractorProfile
    ): ContractorExecutionOutput {

        val driver = driverRegistry.resolve(contractor.source)
            ?: throw LedgerValidationException(
                "ICS BLOCKED: No execution driver for source: ${contractor.source}"
            )

        val output = driver.execute(input)

        if (output.status != ExecutionStatus.SUCCESS) {
            throw LedgerValidationException(
                "ICS BLOCKED: Execution failed — ${output.error ?: "unknown"}"
            )
        }

        if (output.resultArtifact.isEmpty()) {
            throw LedgerValidationException(
                "ICS BLOCKED: Empty execution artifact"
            )
        }

        return output
    }
}
