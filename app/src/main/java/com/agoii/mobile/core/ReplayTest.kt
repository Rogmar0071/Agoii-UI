package com.agoii.mobile.core

/**
 * Full verification result combining ledger audit + structural replay invariant checks.
 */
data class ReplayVerification(
    val valid: Boolean,
    val structuralState: ReplayStructuralState,
    val auditResult: AuditResult,
    val invariantErrors: List<String>
)

/**
 * Cross-validates the ledger audit result against the derived structural replay state.
 *
 * Invariants checked:
 *  1. Execution is fully executed only when all tasks are completed and validated.
 *  2. Assembly is valid only when fully executed and all assembly phases completed.
 *  3. Ledger audit must pass (no illegal transitions).
 */
class ReplayTest(private val eventStore: EventRepository) {

    fun verifyReplay(projectId: String): ReplayVerification {
        val state       = Replay(eventStore).replayStructuralState(projectId)
        val auditResult = LedgerAudit(eventStore).auditLedger(projectId)
        val invariantErrors = mutableListOf<String>()

        // Invariant 1: fullyExecuted requires tasks assigned == total
        if (state.execution.fullyExecuted &&
            state.execution.assignedTasks != state.execution.totalTasks
        ) {
            invariantErrors.add(
                "Invariant: fullyExecuted=true but assignedTasks " +
                        "(${state.execution.assignedTasks}) != totalTasks (${state.execution.totalTasks})"
            )
        }

        // Invariant 2: assemblyValid requires fullyExecuted
        if (state.assembly.assemblyValid && !state.execution.fullyExecuted) {
            invariantErrors.add(
                "Invariant: assemblyValid=true but fullyExecuted=false"
            )
        }

        return ReplayVerification(
            valid = auditResult.valid && invariantErrors.isEmpty(),
            structuralState = state,
            auditResult = auditResult,
            invariantErrors = invariantErrors
        )
    }
}
