package com.agoii.mobile.execution

import com.agoii.mobile.contractor.ContractorProfile
import java.security.MessageDigest

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
    val error:          String? = null,
    val artifact:       Artifact? = null  // AGOII-ARTIFACT-SPINE-001: Embedded artifact
)

enum class ExecutionStatus { SUCCESS, FAILURE }

// ─── ContractorExecutor ─────────────────────────────────────────────────────

class ContractorExecutor(
    private val driverRegistry: DriverRegistry
) {

    // ── Artifact Construction (AGOII-ARTIFACT-SPINE-001) ─────────────────────

    /**
     * Build deterministic artifact from execution output.
     *
     * CONTRACT: AGOII-ARTIFACT-SPINE-001
     *
     * NemoCore responsibility:
     *   - Transform raw output → Artifact
     *   - Hash each section (SHA-256)
     *   - Ensure deterministic structure
     *
     * @param executionId Execution identifier.
     * @param output      List of output strings (one per section).
     * @return [Artifact] with ordered, hashed sections.
     */
    fun buildArtifact(executionId: String, output: List<String>): Artifact {
        val sections = output.mapIndexed { index, content ->
            ArtifactSection(
                sectionId = "section_$index",
                content = content,
                contentHash = sha256(content)
            )
        }

        return Artifact(
            executionId = executionId,
            sections = sections
        )
    }

    /**
     * Compute SHA-256 hash of content (deterministic).
     *
     * @param content Content to hash.
     * @return Hexadecimal hash string.
     */
    private fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(content.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    // ── Execution ─────────────────────────────────────────────────────────────

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
                error          = rawOutput.error ?: "EXECUTION_FAILED",
                artifact       = null
            )
        }

        // STEP 4: Build artifact from outputs (AGOII-ARTIFACT-SPINE-001)
        val artifactMap = rawOutput.resultArtifact
        
        // Extract outputs as list of strings for artifact construction
        // Handle both List<String> and single String outputs
        val outputsList = when (val outputs = artifactMap["outputs"]) {
            is List<*> -> outputs.filterIsInstance<String>()
            is String -> listOf(outputs)
            else -> listOf(artifactMap.toString())
        }
        
        // Build deterministic artifact
        val artifact = if (outputsList.isNotEmpty()) {
            buildArtifact(input.taskId, outputsList)
        } else {
            // Empty outputs still produce valid artifact (empty sections)
            buildArtifact(input.taskId, emptyList())
        }

        return ContractorExecutionOutput(
            taskId         = input.taskId,
            resultArtifact = artifactMap,
            status         = ExecutionStatus.SUCCESS,
            error          = null,
            artifact       = artifact
        )
    }
}
