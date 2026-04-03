package com.agoii.mobile.execution.adapter

import com.agoii.mobile.execution.ExecutionContract
import com.agoii.mobile.execution.ExecutionReport
import com.google.gson.Gson
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

// ══════════════════════════════════════════════════════════════════════════════
// AGOII–EXECUTION-ADAPTER-001
// NEMOCLAW ADAPTER — PROCESS-LEVEL EXECUTION PIPE
// ══════════════════════════════════════════════════════════════════════════════
//
// CORE PRINCIPLE: Agoii never executes logic, only invokes execution engines
//
// RESPONSIBILITY:
//   1. Serialize ExecutionContract → JSON
//   2. Launch NemoClaw process
//   3. Pass contract via temp file OR stdin
//   4. Capture stdout
//   5. Parse JSON → ExecutionReport
//   6. Return report to ExecutionAuthority
//
// HARD RULES:
//   - NO BUSINESS LOGIC (no modification, interpretation, or validation)
//   - NO FALLBACKS (on failure, return FAILURE report)
//   - STRICT JSON PIPE (stdin=contract, stdout=report, stderr=ignored/logged)
//   - TIMEOUT CONTROL (enforce timeout, kill process if exceeded)
//   - EXECUTION_ID INTEGRITY (verify report.execution_id == contract.execution_id)
//
// PROHIBITED:
//   - Direct function calls
//   - Shared libraries
//   - In-memory execution
//   - Retrying inside adapter
//
// ══════════════════════════════════════════════════════════════════════════════

/**
 * NemoClawAdapter — Process-level execution adapter for NemoClaw.
 *
 * CONTRACT: AGOII–EXECUTION-ADAPTER-001
 *
 * This adapter is a PIPE, not a brain. It:
 *   - Spawns NemoClaw as separate process
 *   - Passes ExecutionContract as JSON
 *   - Captures ExecutionReport as JSON
 *   - Returns report to ExecutionAuthority
 *
 * NO LOGIC, NO DECISIONS, NO RETRIES.
 *
 * CONFIGURATION NOTE:
 *   The default nemoClawScript path is a placeholder and MUST be configured
 *   externally (via dependency injection or configuration file) before use.
 *   Production systems should inject actual NemoClaw path via constructor.
 *
 * @property nemoClawExecutable Path to NemoClaw executable (e.g., "node", "nemoclaw")
 * @property nemoClawScript     Path to NemoClaw script (e.g., "/actual/path/to/execute.js")
 *                              Use empty string if nemoClawExecutable is itself a script.
 * @property defaultTimeoutMs   Default timeout in milliseconds (60000 = 60s)
 */
class NemoClawAdapter(
    private val nemoClawExecutable: String = "node",
    private val nemoClawScript:     String = "/path/to/nemoclaw/execute.js", // PLACEHOLDER - configure externally
    private val defaultTimeoutMs:   Long   = 60000L
) {

    private val gson = Gson()

    /**
     * Execute contract via NemoClaw process.
     *
     * FLOW:
     *   ExecutionContract → JSON → temp file → NemoClaw process → stdout → ExecutionReport
     *
     * RULES:
     *   - If process fails → return FAILURE report
     *   - If stdout invalid → return FAILURE report
     *   - If JSON parse fails → return FAILURE report
     *   - If execution_id mismatch → return FAILURE report
     *   - If timeout exceeded → kill process, return FAILURE report
     *
     * @param contract  Execution contract to execute.
     * @param timeoutMs Timeout in milliseconds (null = use default).
     * @return ExecutionReport from NemoClaw (or FAILURE report on error).
     */
    fun execute(
        contract:  ExecutionContract,
        timeoutMs: Long? = null
    ): ExecutionReport {
        val effectiveTimeout = timeoutMs ?: defaultTimeoutMs
        val executionId = UUID.randomUUID().toString()

        // STEP 1: Serialize contract → JSON
        val contractJson = try {
            serializeContract(contract, executionId)
        } catch (e: Exception) {
            return createFailureReport(executionId, "CONTRACT_SERIALIZATION_FAILED", e.message)
        }

        // STEP 2: Write contract to temp file with restricted permissions
        val contractFile = try {
            writeTempFileSecure(contractJson)
        } catch (e: Exception) {
            return createFailureReport(executionId, "TEMP_FILE_CREATION_FAILED", e.message)
        }

        try {
            // STEP 3: Launch NemoClaw process
            // Build command: if nemoClawScript is blank, use executable directly
            // Otherwise, use executable with script as argument
            val process = try {
                val command = if (nemoClawScript.isBlank()) {
                    listOf(nemoClawExecutable, contractFile.absolutePath)
                } else {
                    listOf(nemoClawExecutable, nemoClawScript, contractFile.absolutePath)
                }
                ProcessBuilder(command)
                    .redirectErrorStream(false) // Keep stderr separate (logged but ignored)
                    .start()
            } catch (e: Exception) {
                return createFailureReport(executionId, "PROCESS_LAUNCH_FAILED", e.message)
            }

            // STEP 4: Wait for process completion with timeout
            val completed = try {
                process.waitFor(effectiveTimeout, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                process.destroyForcibly()
                return createFailureReport(executionId, "PROCESS_WAIT_INTERRUPTED", e.message)
            }

            // STEP 4.1: Handle timeout
            if (!completed) {
                process.destroyForcibly()
                return createFailureReport(executionId, "TIMEOUT", "Process exceeded timeout: ${effectiveTimeout}ms")
            }

            // STEP 5: Capture stdout
            val stdout = try {
                process.inputStream.bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                return createFailureReport(executionId, "STDOUT_READ_FAILED", e.message)
            }

            // STEP 5.1: Log stderr (but ignore it per contract spec)
            val stderr = try {
                process.errorStream.bufferedReader().use { it.readText() }
            } catch (_: Exception) {
                "" // Ignore stderr read failures
            }
            if (stderr.isNotBlank()) {
                // Log stderr for debugging (not used for validation)
                // In production, could use proper logger: logger.debug("NemoClaw stderr: $stderr")
            }

            // STEP 6: Parse JSON → ExecutionReport
            val report = try {
                parseExecutionReport(stdout)
            } catch (e: Exception) {
                return createFailureReport(executionId, "JSON_PARSE_FAILED", e.message)
            }

            // STEP 7: EXECUTION_ID INTEGRITY CHECK
            if (report.executionId != executionId) {
                return createFailureReport(
                    executionId,
                    "EXECUTION_ID_MISMATCH",
                    "Expected: $executionId, Got: ${report.executionId}"
                )
            }

            // STEP 8: Return report (SUCCESS or FAILURE from NemoClaw)
            return report

        } finally {
            // CLEANUP: Delete temp file
            try {
                contractFile.delete()
            } catch (_: Exception) {
                // Ignore cleanup failures
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS (NO LOGIC, PURE SERIALIZATION/DESERIALIZATION)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Serialize ExecutionContract to JSON.
     *
     * Format:
     * {
     *   "execution_id": "<UUID>",
     *   "contract_id": "<contractId>",
     *   "name": "<name>",
     *   "position": <position>,
     *   "report_reference": "<reportReference>"
     * }
     *
     * @param contract    Execution contract.
     * @param executionId Generated execution ID.
     * @return JSON string.
     */
    private fun serializeContract(contract: ExecutionContract, executionId: String): String {
        val contractMap = mapOf(
            "execution_id"     to executionId,
            "contract_id"      to contract.contractId,
            "name"             to contract.name,
            "position"         to contract.position,
            "report_reference" to contract.reportReference
        )
        return gson.toJson(contractMap)
    }

    /**
     * Parse ExecutionReport from JSON stdout.
     *
     * Expected format:
     * {
     *   "execution_id": "<executionId>",
     *   "status": "SUCCESS|FAILURE|TIMEOUT|CONTRACT_REJECTED",
     *   "exit_code": <exitCode>,
     *   "outputs": ["<output1>", "<output2>", ...],
     *   "artifact": { ... },
     *   "failure_surface": { ... }
     * }
     *
     * @param json JSON string from stdout.
     * @return ExecutionReport.
     */
    private fun parseExecutionReport(json: String): ExecutionReport {
        // Gson will throw JsonSyntaxException if invalid
        return gson.fromJson(json, ExecutionReport::class.java)
    }

    /**
     * Write JSON to temporary file with restricted permissions.
     *
     * Security: Sets file to be readable/writable only by owner (600 permissions).
     *
     * @param json JSON string.
     * @return Temp file.
     */
    private fun writeTempFileSecure(json: String): File {
        val tempFile = File.createTempFile("agoii_contract_", ".json")
        // Restrict permissions: owner read/write only (no group/other access)
        tempFile.setReadable(false, false)   // Remove read for all
        tempFile.setWritable(false, false)   // Remove write for all
        tempFile.setReadable(true, true)     // Add read for owner only
        tempFile.setWritable(true, true)     // Add write for owner only
        tempFile.writeText(json)
        return tempFile
    }

    /**
     * Create FAILURE execution report.
     *
     * @param executionId Execution ID.
     * @param errorCode   Error code (e.g., "TIMEOUT", "PROCESS_FAILED").
     * @param errorDetail Error detail message.
     * @return ExecutionReport with FAILURE status.
     */
    private fun createFailureReport(
        executionId:  String,
        errorCode:    String,
        errorDetail:  String? = null
    ): ExecutionReport {
        return ExecutionReport(
            executionId = executionId,
            status      = "FAILURE",
            exitCode    = -1,
            outputs     = emptyList(),
            artifact    = null,
            failureSurface = mapOf(
                "error_code"   to errorCode,
                "error_detail" to (errorDetail ?: "")
            )
        )
    }
}
