package com.agoii.mobile.governance

import com.agoii.mobile.execution.ExecutionResult
import com.agoii.mobile.execution.ValidationVerdict

// ─── ExecutionModuleAdapter — Execution Module Structural State ───────────────

/**
 * ExecutionModuleAdapter — exposes the full structural state of the execution module
 * after a task-execution or task-validation pass.
 *
 * The Governor queries this adapter before appending task lifecycle events.
 *
 * Two validation modes are supported:
 *  - **execution mode** ([checkValidation] == false): validation is complete when
 *    the execution succeeded ([ExecutionResult.success] == true).
 *  - **validation mode** ([checkValidation] == true): validation is complete only when
 *    the result validator produced a [ValidationVerdict.VALIDATED] verdict.
 *
 * @property result           The full execution result from [ExecutionOrchestrator].
 * @property checkValidation  When true, validates the ResultValidator verdict rather
 *                            than the raw execution success flag.
 */
class ExecutionModuleAdapter(
    private val result:           ExecutionResult,
    private val checkValidation:  Boolean = false
) : ModuleState {

    override fun getStateSignature(): Map<String, Any> = mapOf(
        "executionSuccess"    to result.success,
        "validationVerdict"   to result.validationResult.verdict.name,
        "validationFailures"  to result.validationResult.failureReasons,
        "executionError"      to (result.error ?: ""),
        "artifactKeys"        to result.artifact.keys.toList()
    )

    override fun isValidationComplete(): Boolean =
        if (checkValidation) result.validationResult.verdict == ValidationVerdict.VALIDATED
        else result.success

    override fun getValidationErrors(): List<String> = when {
        checkValidation && result.validationResult.verdict != ValidationVerdict.VALIDATED ->
            result.validationResult.failureReasons
                .ifEmpty { listOf("Task validation failed with no specific reason") }
        !checkValidation && !result.success ->
            listOf(result.error ?: "Task execution failed")
        else -> emptyList()
    }
}
