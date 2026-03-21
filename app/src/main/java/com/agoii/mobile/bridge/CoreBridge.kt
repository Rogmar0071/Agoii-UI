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

/**
 * CoreBridge — mobile runtime adapter.
 *
 * Responsibilities:
 *  - Provide a single entry point for the UI layer to call core functions.
 *  - Never introduce logic; only delegate to core modules.
 *  - Each method corresponds to exactly one core operation.
 */
class CoreBridge(context: Context) {

    private val eventStore  = EventStore(context)
    private val governor    = Governor(eventStore)
    private val ledgerAudit = LedgerAudit(eventStore)
    private val replay      = Replay(eventStore)
    private val replayTest  = ReplayTest(eventStore)

    /** Append an intent_submitted event. Called when the user sends an objective. */
    fun submitIntent(projectId: String, objective: String) {
        eventStore.appendEvent(
            projectId,
            "intent_submitted",
            mapOf("objective" to objective)
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
