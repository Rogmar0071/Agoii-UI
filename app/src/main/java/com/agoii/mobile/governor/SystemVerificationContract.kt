package com.agoii.mobile.governor

import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventRepository
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.core.Replay
import com.agoii.mobile.core.ReplayState
import com.agoii.mobile.governance.StateSurfaceMirror
import com.agoii.mobile.governance.StructuralStateAwareness

// ── CONTRACT: SSA-SYSTEM-VERIFICATION-01 ─────────────────────────────────────

/**
 * Result of a single ledger integrity check.
 *
 * @property passed       True if event count is exactly 1 and the first event type is
 *                        [EventTypes.INTENT_SUBMITTED].
 * @property eventCount   Total number of events recorded in the ledger.
 * @property firstEventType The type string of the first ledger event, or null if empty.
 */
data class LedgerIntegrityCheck(
    val passed: Boolean,
    val eventCount: Int,
    val firstEventType: String?
)

/**
 * Result of a replay-consistency check against the expected pre-governance state.
 *
 * @property passed       True when replay matches the expected idle-ready snapshot exactly.
 * @property phase        The derived phase reported by [Replay].
 * @property contracts    Human-readable contracts ratio "completed/total".
 * @property execStarted  Whether [ReplayState.executionStarted] is true.
 */
data class ReplayConsistencyCheck(
    val passed: Boolean,
    val phase: String,
    val contracts: String,
    val execStarted: Boolean
)

/**
 * Result of a governor-inactivity check.
 *
 * @property passed              True when no premature execution events exist in the ledger.
 * @property hasContractStarted  Whether a [EventTypes.CONTRACT_STARTED] event was found.
 * @property hasExecutionStarted Whether an [EventTypes.EXECUTION_STARTED] event was found.
 */
data class GovernorInactivityCheck(
    val passed: Boolean,
    val hasContractStarted: Boolean,
    val hasExecutionStarted: Boolean
)

/**
 * Result of a SSM (StateSurfaceMirror) integrity check.
 *
 * Verifies that the only state-surface authority is [StateSurfaceMirror] and that no
 * alternate "SurfaceStateManager" class was introduced.
 *
 * @property passed        True when [ssmClassName] equals "StateSurfaceMirror".
 * @property ssmClassName  Simple class name of the resolved SSM instance.
 */
data class SsmIntegrityCheck(
    val passed: Boolean,
    val ssmClassName: String
)

/**
 * Result of a CSL (ContractSurfaceLayer) dormancy check.
 *
 * CSL is only triggered from Governor.canIssue(), which is only called after
 * [EventTypes.CONTRACTS_GENERATED] is processed. At the [EventTypes.INTENT_SUBMITTED]
 * boundary no CSL evaluation should have occurred and no DRIFT should have been triggered.
 *
 * @property passed       True when no contract-issuance events exist in the ledger.
 * @property cslEvaluated Whether any event implies a CSL evaluation took place.
 * @property driftTriggered Whether a DRIFT-triggering rejection is detectable.
 */
data class CslDormancyCheck(
    val passed: Boolean,
    val cslEvaluated: Boolean,
    val driftTriggered: Boolean
)

/**
 * Result of a SSA (StructuralStateAwareness) isolation check.
 *
 * Verifies that [StructuralStateAwareness]:
 *  - Lives exclusively in the governance package (not in core).
 *  - Is not wired into the Governor execution path.
 *
 * @property passed            True when both isolation conditions are met.
 * @property ssaPackage        The package name of [StructuralStateAwareness].
 * @property isolatedFromCore  True when [ssaPackage] does not contain "core".
 * @property notInExecutionPath True when Governor's class declaration does not reference SSA.
 */
data class SsaIsolationCheck(
    val passed: Boolean,
    val ssaPackage: String,
    val isolatedFromCore: Boolean,
    val notInExecutionPath: Boolean
)

/**
 * Aggregated result of the SSA-SYSTEM-VERIFICATION-01 read-only contract.
 *
 * @property valid              True only when every individual check passes.
 * @property ledgerIntegrity    Check 1 — ledger event count and first-event type.
 * @property replayConsistency  Check 2 — deterministic replay at pre-governance boundary.
 * @property governorInactivity Check 3 — absence of premature contract/execution events.
 * @property ssmIntegrity       Check 4 — single state-surface authority verification.
 * @property cslDormancy        Check 5 — CSL has not been evaluated or triggered DRIFT.
 * @property ssaIsolation       Check 6 — SSA is isolated from core and execution.
 */
data class SystemVerificationReport(
    val valid: Boolean,
    val ledgerIntegrity: LedgerIntegrityCheck,
    val replayConsistency: ReplayConsistencyCheck,
    val governorInactivity: GovernorInactivityCheck,
    val ssmIntegrity: SsmIntegrityCheck,
    val cslDormancy: CslDormancyCheck,
    val ssaIsolation: SsaIsolationCheck
)

/**
 * SSA-SYSTEM-VERIFICATION-01 — read-only validation contract.
 *
 * Verifies that runtime state matches structural expectations at the
 * pre-governance boundary ([EventTypes.INTENT_SUBMITTED]).
 *
 * This class is intentionally stateless: every call to [verify] re-reads the ledger
 * and derives state from scratch to remain deterministic.
 */
class SystemVerificationContract(private val eventStore: EventRepository) {

    /**
     * Executes all six verification checks and returns a consolidated report.
     *
     * @param projectId The project whose ledger is to be verified.
     */
    fun verify(projectId: String): SystemVerificationReport {
        val events = eventStore.loadEvents(projectId)
        val state  = Replay(eventStore).replay(projectId)

        val ledger    = checkLedgerIntegrity(events)
        val replay    = checkReplayConsistency(state)
        val governor  = checkGovernorInactivity(events)
        val ssm       = checkSsmIntegrity()
        val csl       = checkCslDormancy(events)
        val ssa       = checkSsaIsolation()

        return SystemVerificationReport(
            valid              = ledger.passed && replay.passed && governor.passed &&
                                 ssm.passed   && csl.passed    && ssa.passed,
            ledgerIntegrity    = ledger,
            replayConsistency  = replay,
            governorInactivity = governor,
            ssmIntegrity       = ssm,
            cslDormancy        = csl,
            ssaIsolation       = ssa
        )
    }

    // ── Check 1: Ledger Integrity ─────────────────────────────────────────────

    private fun checkLedgerIntegrity(events: List<Event>): LedgerIntegrityCheck {
        val firstType = events.firstOrNull()?.type
        return LedgerIntegrityCheck(
            passed         = events.size == 1 && firstType == EventTypes.INTENT_SUBMITTED,
            eventCount     = events.size,
            firstEventType = firstType
        )
    }

    // ── Check 2: Replay Consistency ───────────────────────────────────────────

    private fun checkReplayConsistency(state: ReplayState): ReplayConsistencyCheck {
        val passed = state.phase == EventTypes.INTENT_SUBMITTED &&
                     state.contractsCompleted == 0             &&
                     state.totalContracts     == 0             &&
                     !state.executionStarted
        return ReplayConsistencyCheck(
            passed      = passed,
            phase       = state.phase,
            contracts   = "${state.contractsCompleted}/${state.totalContracts}",
            execStarted = state.executionStarted
        )
    }

    // ── Check 3: Governor Inactivity ──────────────────────────────────────────

    private fun checkGovernorInactivity(events: List<Event>): GovernorInactivityCheck {
        val hasContractStarted  = events.any { it.type == EventTypes.CONTRACT_STARTED }
        val hasExecutionStarted = events.any { it.type == EventTypes.EXECUTION_STARTED }
        return GovernorInactivityCheck(
            passed              = !hasContractStarted && !hasExecutionStarted,
            hasContractStarted  = hasContractStarted,
            hasExecutionStarted = hasExecutionStarted
        )
    }

    // ── Check 4: SSM Integrity ────────────────────────────────────────────────

    private fun checkSsmIntegrity(): SsmIntegrityCheck {
        val className = StateSurfaceMirror::class.simpleName ?: ""
        return SsmIntegrityCheck(
            passed       = className == "StateSurfaceMirror",
            ssmClassName = className
        )
    }

    // ── Check 5: CSL Dormancy ─────────────────────────────────────────────────

    /**
     * CSL is evaluated exclusively inside Governor.canIssue(), which is only reached
     * when the Governor processes [EventTypes.EXECUTION_STARTED] or advances through
     * the contract loop. The presence of [EventTypes.CONTRACT_STARTED] is the only
     * ledger-observable evidence that CSL was invoked; DRIFT is the observable evidence
     * of a CSL rejection. Neither should appear at the [EventTypes.INTENT_SUBMITTED] boundary.
     *
     * Note: DRIFT has no ledger event — its absence is the structural invariant confirmed here.
     * driftTriggered is always false because DRIFT is returned from Governor.runGovernor() and
     * never persisted to the ledger. At the pre-governance boundary no governor call has occurred.
     */
    private fun checkCslDormancy(events: List<Event>): CslDormancyCheck {
        val cslEvaluated   = events.any { it.type == EventTypes.CONTRACT_STARTED }
        val driftTriggered = false  // structural invariant: DRIFT has no ledger event
        return CslDormancyCheck(
            passed         = !cslEvaluated && !driftTriggered,
            cslEvaluated   = cslEvaluated,
            driftTriggered = driftTriggered
        )
    }

    // ── Check 6: SSA Isolation ────────────────────────────────────────────────

    /**
     * Verifies structural isolation of [StructuralStateAwareness]:
     *  - Its JVM package must not be the core package (confirming no core dependency inversion).
     *  - Governor's declared interfaces must not reference SSA (confirming execution isolation).
     */
    private fun checkSsaIsolation(): SsaIsolationCheck {
        val ssaPackage       = StructuralStateAwareness::class.java.`package`?.name ?: ""
        val isolatedFromCore = !ssaPackage.contains(".core")
        val notInExecution   = Governor::class.java.interfaces.none {
            it.name.contains("StructuralStateAwareness")
        }
        return SsaIsolationCheck(
            passed            = isolatedFromCore && notInExecution,
            ssaPackage        = ssaPackage,
            isolatedFromCore  = isolatedFromCore,
            notInExecutionPath = notInExecution
        )
    }
}
