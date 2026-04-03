package com.agoii.mobile.execution.adapter

import com.agoii.mobile.execution.Artifact
import com.agoii.mobile.execution.ArtifactSection
import com.agoii.mobile.execution.ExecutionContract
import com.agoii.mobile.execution.ExecutionReport
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * NemoClawAdapter — Process-level execution isolation adapter for NemoClaw.
 *
 * CONTRACT: AGOII-EXECUTION-ADAPTER-001
 *
 * Responsibilities:
 *   - Serialize ExecutionContract to JSON
 *   - Spawn NemoClaw process via ProcessBuilder
 *   - Capture stdout as ExecutionReport JSON
 *   - Enforce timeout
 *   - Validate execution_id integrity
 *   - FAIL CLOSED on any error
 *
 * Rules:
 *   - NO business logic (pure pipe)
 *   - NO fallbacks (return FAILURE on error)
 *   - NO shared memory
 *   - Process isolation preserved
 *   - STRICT JSON validation
 *
 * This adapter is the SOLE mechanism for invoking NemoClaw execution.
 * NO direct execution, NO alternative paths.
 */
class NemoClawAdapter {

    companion object {
        /**
         * Path to NemoClaw execute.js (relative to project root).
         */
        private const val NEMOCLAW_EXECUTABLE = "execution/nemoclaw/execute.js"

        /**
         * Default timeout for NemoClaw execution (milliseconds).
         */
        private const val DEFAULT_TIMEOUT_MS = 300000L // 5 minutes

        /**
         * Execution status constants (must match NemoClaw output).
         */
        private const val STATUS_SUCCESS = "SUCCESS"
        private const val STATUS_FAILURE = "FAILURE"
        private const val STATUS_TIMEOUT = "TIMEOUT"
    }

    /**
     * Execute a contract via NemoClaw process.
     *
     * CONTRACT: AGOII-EXECUTION-AUTHORITY-WIRING-001
     *
     * @param contract ExecutionContract to execute.
     * @return ExecutionReport (never null; FAIL CLOSED on error).
     * @throws Exception if adapter fails (caught by ExecutionAuthority).
     */
    fun execute(contract: ExecutionContract): ExecutionReport {
        // Validate NemoClaw executable exists
        val nemoClawScript = File(NEMOCLAW_EXECUTABLE)
        if (!nemoClawScript.exists() || !nemoClawScript.canRead()) {
            throw IllegalStateException(
                "NemoClaw executable not found or not readable: $NEMOCLAW_EXECUTABLE"
            )
        }

        // Create temporary file for contract JSON
        val contractFile = createTemporaryContractFile(contract)

        try {
            // Spawn NemoClaw process
            val process = ProcessBuilder(
                "node",
                nemoClawScript.absolutePath,
                contractFile.absolutePath
            )
                .redirectErrorStream(false) // Keep stderr separate for diagnostics
                .start()

            // Wait for process with timeout
            val completed = process.waitFor(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)

            if (!completed) {
                // Timeout occurred
                process.destroyForcibly()
                return ExecutionReport(
                    executionId = contract.executionId,
                    status = STATUS_TIMEOUT,
                    exitCode = -1,
                    outputs = listOf("Execution timeout after ${DEFAULT_TIMEOUT_MS}ms"),
                    artifact = null,
                    failureSurface = mapOf("reason" to "TIMEOUT")
                )
            }

            // Read stdout (ExecutionReport JSON)
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            val exitCode = process.exitValue()

            // Log stderr for diagnostics (if present)
            if (stderr.isNotBlank()) {
                System.err.println("[NemoClawAdapter] stderr: $stderr")
            }

            // Parse ExecutionReport from stdout
            val executionReport = parseExecutionReport(stdout, contract.executionId, exitCode)

            // EXECUTION_ID INTEGRITY CHECK (CONTRACT STEP 7)
            if (executionReport.executionId != contract.executionId) {
                throw IllegalStateException(
                    "Execution ID mismatch: expected ${contract.executionId}, got ${executionReport.executionId}"
                )
            }

            return executionReport

        } finally {
            // Clean up temporary contract file
            contractFile.delete()
        }
    }

    /**
     * Create temporary file with contract JSON.
     *
     * Security: Restrict permissions to owner only (600).
     */
    private fun createTemporaryContractFile(contract: ExecutionContract): File {
        val contractJson = serializeContract(contract)
        
        val tempFile = Files.createTempFile("agoii-contract-", ".json").toFile()
        
        // Restrict permissions (owner read/write only)
        tempFile.setReadable(false, false)
        tempFile.setWritable(false, false)
        tempFile.setReadable(true, true)  // owner only
        tempFile.setWritable(true, true)  // owner only

        tempFile.writeText(contractJson)
        
        return tempFile
    }

    /**
     * Serialize ExecutionContract to NemoClaw JSON format.
     *
     * Expected format:
     * {
     *   "execution_id": string,
     *   "contractor_id": string,
     *   "input": { "prompt": string },
     *   "execution_policy": { "process": { "timeoutMs": number } }
     * }
     *
     * Note: contractor_id is hardcoded to "openai-inference" as this is currently
     * the only supported contractor in NemoClaw. Future enhancement: add contractor_id
     * field to ExecutionContract to support multiple contractors.
     *
     * Note: Using contract.name as the prompt is a temporary approach. In production,
     * ExecutionContract should include an explicit `input` field containing the full
     * prompt/task description. The contract name is used here as it's the most
     * relevant field available in the current model.
     */
    private fun serializeContract(contract: ExecutionContract): String {
        val json = JSONObject()
        json.put("execution_id", contract.executionId)
        json.put("contractor_id", "openai-inference") // TODO: Make configurable via ExecutionContract
        
        // Build input object
        val input = JSONObject()
        input.put("prompt", contract.name) // TODO: Use explicit input field when available
        json.put("input", input)
        
        // Build execution policy
        val policy = JSONObject()
        val processPolicy = JSONObject()
        processPolicy.put("timeoutMs", DEFAULT_TIMEOUT_MS)
        policy.put("process", processPolicy)
        json.put("execution_policy", policy)
        
        return json.toString(2)
    }

    /**
     * Parse ExecutionReport from NemoClaw stdout JSON.
     *
     * Expected format:
     * {
     *   "execution_id": string,
     *   "status": "SUCCESS" | "FAILURE" | "TIMEOUT",
     *   "exit_code": number,
     *   "outputs": [string],
     *   "artifact": {
     *     "execution_id": string,
     *     "sections": [
     *       {
     *         "section_id": string,
     *         "content": string,
     *         "content_hash": string
     *       }
     *     ]
     *   },
     *   "failure_surface": { ... }
     * }
     */
    private fun parseExecutionReport(
        stdout: String,
        expectedExecutionId: String,
        exitCode: Int
    ): ExecutionReport {
        if (stdout.isBlank()) {
            // Empty stdout = FAILURE
            return ExecutionReport(
                executionId = expectedExecutionId,
                status = STATUS_FAILURE,
                exitCode = exitCode,
                outputs = emptyList(),
                artifact = null,
                failureSurface = mapOf("reason" to "EMPTY_STDOUT")
            )
        }

        return try {
            val json = JSONObject(stdout)

            val executionId = json.getString("execution_id")
            val status = json.getString("status")
            val reportExitCode = json.optInt("exit_code", exitCode)
            
            // Parse outputs array
            val outputs = mutableListOf<String>()
            if (json.has("outputs")) {
                val outputsArray = json.getJSONArray("outputs")
                for (i in 0 until outputsArray.length()) {
                    outputs.add(outputsArray.getString(i))
                }
            }

            // Parse artifact (if present)
            val artifact = if (json.has("artifact") && !json.isNull("artifact")) {
                parseArtifact(json.getJSONObject("artifact"))
            } else {
                null
            }

            // Parse failure surface (if present)
            val failureSurface = if (json.has("failure_surface") && !json.isNull("failure_surface")) {
                val fsJson = json.getJSONObject("failure_surface")
                fsJson.keys().asSequence().associateWith { key ->
                    fsJson.get(key)
                }
            } else {
                null
            }

            ExecutionReport(
                executionId = executionId,
                status = status,
                exitCode = reportExitCode,
                outputs = outputs,
                artifact = artifact,
                failureSurface = failureSurface
            )

        } catch (e: Exception) {
            // JSON parse error = FAILURE
            System.err.println("[NemoClawAdapter] Failed to parse ExecutionReport: ${e.message}")
            System.err.println("[NemoClawAdapter] stdout: $stdout")
            
            ExecutionReport(
                executionId = expectedExecutionId,
                status = STATUS_FAILURE,
                exitCode = exitCode,
                outputs = listOf("JSON parse error: ${e.message}"),
                artifact = null,
                failureSurface = mapOf(
                    "reason" to "JSON_PARSE_ERROR",
                    "error" to e.message.orEmpty()
                )
            )
        }
    }

    /**
     * Parse Artifact from JSON.
     */
    private fun parseArtifact(json: JSONObject): Artifact {
        val executionId = json.getString("execution_id")
        val sections = mutableListOf<ArtifactSection>()

        if (json.has("sections")) {
            val sectionsArray = json.getJSONArray("sections")
            for (i in 0 until sectionsArray.length()) {
                val sectionJson = sectionsArray.getJSONObject(i)
                sections.add(
                    ArtifactSection(
                        sectionId = sectionJson.getString("section_id"),
                        content = sectionJson.getString("content"),
                        contentHash = sectionJson.getString("content_hash")
                    )
                )
            }
        }

        return Artifact(
            executionId = executionId,
            sections = sections
        )
    }
}
