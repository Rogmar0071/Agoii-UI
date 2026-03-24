package com.agoii.mobile.governance

import com.agoii.mobile.execution.ExecutionResult

// ─── ExecutionModuleAdapter — Execution Module Structural State ───────────────

/**
 * ExecutionModuleAdapter — exposes the full structural state of the execution module
 * after a task-execution or task-validation pass.
 *
 * This adapter exposes structural state only. The Governor reads [getStateSignature]
 * and decides which event to emit (TASK_COMPLETED, TASK_VALIDATED, or TASK_FAILED);
 * the adapter does not validate, decide, or branch.
 *
 * The signature always exposes both [executionSuccess] and [validationVerdict] so the
 * Governor can check whichever field is relevant for the current event boundary.
 *
 * @property result The full execution result from ExecutionOrchestrator.
 */
class ExecutionModuleAdapter(
    private val result: ExecutionResult
) : ModuleState {

    override fun getStateSignature(): Map<String, Any> = mapOf(
        "executionSuccess"   to result.success,
        "validationVerdict"  to result.validationResult.verdict.name,
        "validationFailures" to result.validationResult.failureReasons,
        "executionError"     to (result.error ?: ""),
        "artifactKeys"       to result.artifact.keys.toList()
    )
}
