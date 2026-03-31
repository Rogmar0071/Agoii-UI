package com.agoii.mobile.execution

import com.agoii.mobile.contractor.ContractorProfile

// ─── ContractorExecution ─────────────────────────────────────────────────────

data class ContractorExecutionInput(
    val taskId:               String,
    val taskDescription:      String,
    val taskPayload:          Map<String, Any>,
    val contractConstraints:  List<String>,
    val expectedOutputSchema: String
) {
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

// ─── ContractorExecutor ─────────────────────────────────────────────────────

class ContractorExecutor(
    private val driverRegistry: DriverRegistry
) {

    fun execute(
        input:      ContractorExecutionInput,
        contractor: ContractorProfile
    ): ContractorExecutionOutput {

        // STEP 1: Resolve driver (NO THROW)
        val driver = driverRegistry.resolve(contractor.source)

        if (driver == null) {
            return ContractorExecutionOutput(
                taskId         = input.taskId,
                resultArtifact = emptyMap(),
                status         = ExecutionStatus.FAILURE,
                error          = "NO_DRIVER_FOUND"
            )
        }

        // STEP 2: Execute safely (NO THROW ESCAPE)
        val rawOutput = try {
            driver.execute(input)
        } catch (_: Exception) {
            return ContractorExecutionOutput(
                taskId         = input.taskId,
                resultArtifact = emptyMap(),
                status         = ExecutionStatus.FAILURE,
                error          = "DRIVER_EXECUTION_EXCEPTION"
            )
        }

        // STEP 3: Normalize result (NO THROW)
        if (rawOutput.status != ExecutionStatus.SUCCESS) {
            return ContractorExecutionOutput(
                taskId         = input.taskId,
                resultArtifact = rawOutput.resultArtifact,
                status         = ExecutionStatus.FAILURE,
                error          = rawOutput.error ?: "EXECUTION_FAILED"
            )
        }

        // STEP 4: Ensure artifact presence (NO THROW)
        val artifact = rawOutput.resultArtifact

        return ContractorExecutionOutput(
            taskId         = input.taskId,
            resultArtifact = artifact,
            status         = ExecutionStatus.SUCCESS,
            error          = null
        )
    }
}
