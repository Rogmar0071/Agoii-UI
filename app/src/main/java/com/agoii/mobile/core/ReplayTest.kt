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
 *  1. assignedTasks == totalTasks (totalTasks is always derived from TASK_ASSIGNED).
 *  2. completedTasks <= assignedTasks.
 *  3. validatedTasks <= completedTasks.
 *  4. Assembly is valid only when fully executed and all assembly phases completed.
 *  5. Ledger audit must pass (no illegal transitions).
 */
class ReplayTest(private val eventStore: EventRepository) {

    fun verifyReplay(projectId: String): ReplayVerification {
        val state       = Replay(eventStore).replayStructuralState(projectId)
        val auditResult = LedgerAudit(eventStore).auditLedger(projectId)
        val invariantErrors = mutableListOf<String>()

        // Invariant 1: totalTasks is derived from TASK_ASSIGNED, so they must always match
        if (state.execution.assignedTasks != state.execution.totalTasks) {
            invariantErrors.add(
                "Invariant: assignedTasks (${state.execution.assignedTasks}) " +
                        "!= totalTasks (${state.execution.totalTasks})"
            )
        }

        // Invariant 2: completedTasks cannot exceed assignedTasks
        if (state.execution.completedTasks > state.execution.assignedTasks) {
            invariantErrors.add(
                "Invariant: completedTasks (${state.execution.completedTasks}) " +
                        "> assignedTasks (${state.execution.assignedTasks})"
            )
        }

        // Invariant 3: validatedTasks cannot exceed completedTasks
        if (state.execution.validatedTasks > state.execution.completedTasks) {
            invariantErrors.add(
                "Invariant: validatedTasks (${state.execution.validatedTasks}) " +
                        "> completedTasks (${state.execution.completedTasks})"
            )
        }

        // Invariant 4: assemblyValid requires fullyExecuted
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
