package com.agoii.mobile.assembly

import com.agoii.mobile.core.ReplayStructuralState

/**
 * Pure, state-driven validation layer for the Assembly phase.
 *
 * Single entry point: [validate] accepts a [ReplayStructuralState] and returns an [AssemblyResult].
 *
 * Rules:
 *  - Operates ONLY on the provided [ReplayStructuralState] — no external inputs, no hidden state.
 *  - Does NOT execute or recompute logic; it only verifies structural integrity.
 *  - No events are emitted; no ledger mutations occur.
 *  - Pure function: same input always produces the same output.
 *
 * Validation checks:
 *  B. Execution Closure   — executionStarted and executionCompleted are both true.
 *  C. Task Resolution     — verified via fullyExecuted flag.
 *  D. Transition Integrity — assembly state is only reached after execution completion.
 */
class AssemblyValidator {

    /**
     * Validate the [replayState] and return an [AssemblyResult].
     *
     * This is the single, non-overloaded entry point. It is a pure function with
     * zero execution responsibility — it will not call contractors, trigger execution,
     * write events, or mutate state.
     */
    fun validate(replayState: ReplayStructuralState): AssemblyResult {
        val missingElements = mutableListOf<String>()
        val failedChecks    = mutableListOf<String>()

        val av = replayState.auditView
        val executionStarted   = av.execution.assignedTasks > 0
        val executionCompleted = av.execution.fullyExecuted

        // B. Execution Closure
        if (!executionStarted) {
            failedChecks.add("execution not started")
        }
        if (!executionCompleted) {
            failedChecks.add("execution_completed not found in ledger")
        }

        // D. Transition Integrity
        if (av.assembly.assemblyStarted && !executionCompleted) {
            failedChecks.add("assembly_started appeared before execution_completed")
        }
        if (av.assembly.assemblyValidated && !av.assembly.assemblyStarted) {
            failedChecks.add("assembly_validated appeared before assembly_started")
        }

        val isValid = missingElements.isEmpty() && failedChecks.isEmpty()
        return AssemblyResult(
            isValid           = isValid,
            completionStatus  = if (isValid) "COMPLETE" else "INCOMPLETE",
            missingElements   = missingElements,
            failedChecks      = failedChecks
        )
    }
}
