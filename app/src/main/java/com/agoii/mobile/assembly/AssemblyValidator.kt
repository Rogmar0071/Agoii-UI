package com.agoii.mobile.assembly

import com.agoii.mobile.core.ReplayState

/**
 * Pure, state-driven validation layer for the Assembly phase.
 *
 * Single entry point: [validate] accepts a [ReplayState] and returns an [AssemblyResult].
 *
 * Rules:
 *  - Operates ONLY on the provided [ReplayState] — no external inputs, no hidden state.
 *  - Does NOT execute or recompute logic; it only verifies structural integrity.
 *  - No events are emitted; no ledger mutations occur.
 *  - Pure function: same input always produces the same output.
 *
 * Validation checks:
 *  A. Contract Closure    — all generated contracts are completed.
 *  B. Execution Closure   — executionStarted and executionCompleted are both true.
 *  C. Task Resolution     — verified via contract closure (no partial contracts).
 *  D. Transition Integrity — assembly state is only reached after execution completion.
 *  E. Structural Completeness — required state elements are present.
 */
class AssemblyValidator {

    /**
     * Validate the [replayState] and return an [AssemblyResult].
     *
     * This is the single, non-overloaded entry point. It is a pure function with
     * zero execution responsibility — it will not call contractors, trigger execution,
     * write events, or mutate state.
     */
    fun validate(replayState: ReplayState): AssemblyResult {
        val missingElements = mutableListOf<String>()
        val failedChecks    = mutableListOf<String>()

        // A. Contract Closure
        if (replayState.totalContracts == 0) {
            missingElements.add("total_contracts")
        }
        if (replayState.contractsCompleted == 0) {
            missingElements.add("contract_completed events")
        }
        if (replayState.totalContracts > 0 &&
            replayState.contractsCompleted != replayState.totalContracts
        ) {
            failedChecks.add(
                "expected ${replayState.totalContracts} completed contracts " +
                "but found ${replayState.contractsCompleted}"
            )
        }

        // B. Execution Closure
        if (!replayState.executionStarted) {
            failedChecks.add("execution not started")
        }
        if (!replayState.executionCompleted) {
            failedChecks.add("execution_completed not found in ledger")
        }

        // D. Transition Integrity
        if (replayState.assemblyStarted && !replayState.executionCompleted) {
            failedChecks.add("assembly_started appeared before execution_completed")
        }
        if (replayState.assemblyValidated && !replayState.assemblyStarted) {
            failedChecks.add("assembly_validated appeared before assembly_started")
        }

        // E. Structural Completeness
        if (replayState.objective.isNullOrBlank()) {
            missingElements.add("objective")
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
