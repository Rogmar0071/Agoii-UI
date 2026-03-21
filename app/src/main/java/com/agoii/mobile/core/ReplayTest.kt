package com.agoii.mobile.core

/**
 * Full verification result combining ledger audit + replay invariant checks.
 */
data class ReplayVerification(
    val valid: Boolean,
    val replayState: ReplayState,
    val auditResult: AuditResult,
    val invariantErrors: List<String>
)

/**
 * Cross-validates the ledger audit result against the derived replay state.
 *
 * Invariants checked:
 *  1. contracts_completed must not exceed total_contracts.
 *  2. execution_completed requires execution_started.
 *  3. assembly_completed phase requires all contracts to be completed.
 *  4. Ledger audit must pass (no illegal transitions).
 */
class ReplayTest(private val eventStore: EventRepository) {

    fun verifyReplay(projectId: String): ReplayVerification {
        val state       = Replay(eventStore).replay(projectId)
        val auditResult = LedgerAudit(eventStore).auditLedger(projectId)
        val invariantErrors = mutableListOf<String>()

        if (state.totalContracts > 0 && state.contractsCompleted > state.totalContracts) {
            invariantErrors.add(
                "Invariant: contracts_completed (${state.contractsCompleted}) " +
                        "> total_contracts (${state.totalContracts})"
            )
        }

        if (state.executionCompleted && !state.executionStarted) {
            invariantErrors.add(
                "Invariant: execution_completed=true but execution_started=false"
            )
        }

        if (state.phase == "assembly_completed" &&
            state.totalContracts > 0 &&
            state.contractsCompleted != state.totalContracts
        ) {
            invariantErrors.add(
                "Invariant: assembly_completed but contracts_completed " +
                        "(${state.contractsCompleted}) != total_contracts (${state.totalContracts})"
            )
        }

        return ReplayVerification(
            valid = auditResult.valid && invariantErrors.isEmpty(),
            replayState = state,
            auditResult = auditResult,
            invariantErrors = invariantErrors
        )
    }
}
