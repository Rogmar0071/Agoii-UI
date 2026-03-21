package com.agoii.mobile.bridge

import android.content.Context
import com.agoii.mobile.core.AuditResult
import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventStore
import com.agoii.mobile.core.Governor
import com.agoii.mobile.core.LedgerAudit
import com.agoii.mobile.core.Replay
import com.agoii.mobile.core.ReplayState
import com.agoii.mobile.core.ReplayTest
import com.agoii.mobile.core.ReplayVerification
import com.agoii.mobile.irs.CertifiedIntent

/**
 * CoreBridge — mobile runtime adapter.
 *
 * Responsibilities:
 *  - Provide a single entry point for the UI layer to call core functions.
 *  - Never introduce logic; only delegate to core modules.
 *  - Each method corresponds to exactly one core operation.
 *
 * IRS-01 invariant (enforced here):
 *  - Raw user input MUST NOT enter core.
 *  - Only a [CertifiedIntent] produced by IRS-01 may be submitted as intent_submitted.
 */
class CoreBridge(context: Context) {

    private val eventStore  = EventStore(context)
    private val governor    = Governor(eventStore)
    private val ledgerAudit = LedgerAudit(eventStore)
    private val replay      = Replay(eventStore)
    private val replayTest  = ReplayTest(eventStore)

    /**
     * Append an intent_submitted event from a certified intent.
     *
     * SYSTEM LAW: Only a [CertifiedIntent] produced by IRS-01 is accepted.
     * Raw user input must never be passed directly here; it must first pass
     * through [com.agoii.mobile.irs.IntentResolutionSystem.process].
     */
    fun submitCertifiedIntent(projectId: String, certifiedIntent: CertifiedIntent) {
        eventStore.appendEvent(
            projectId,
            "intent_submitted",
            mapOf(
                "intent_id"            to certifiedIntent.intentId,
                "objective"            to certifiedIntent.objective,
                "success_criteria"     to certifiedIntent.successCriteria,
                "constraints"          to certifiedIntent.constraints,
                "environment"          to certifiedIntent.environment,
                "resources"            to certifiedIntent.resources,
                "acceptance_boundary"  to certifiedIntent.acceptanceBoundary,
                "validation_status"    to certifiedIntent.validationStatus
            )
        )
    }

    /** Trigger one governor step. Returns the result of that step. */
    fun runGovernorStep(projectId: String): Governor.GovernorResult =
        governor.runGovernor(projectId)

    /** Append a contracts_approved event. Called when the user taps APPROVE. */
    fun approveContracts(projectId: String) {
        eventStore.appendEvent(projectId, "contracts_approved", emptyMap())
    }

    /** Load all events from the ledger (read-only). */
    fun loadEvents(projectId: String): List<Event> =
        eventStore.loadEvents(projectId)

    /** Derive current state by replaying the ledger (read-only). */
    fun replayState(projectId: String): ReplayState =
        replay.replay(projectId)

    /** Run the ledger audit (read-only). */
    fun auditLedger(projectId: String): AuditResult =
        ledgerAudit.auditLedger(projectId)

    /** Run full replay verification: audit + invariant checks (read-only). */
    fun verifyReplay(projectId: String): ReplayVerification =
        replayTest.verifyReplay(projectId)
}
