package com.agoii.mobile.bridge

import android.content.Context
import com.agoii.mobile.core.AuditResult
import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventStore
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.core.Governor
import com.agoii.mobile.core.LedgerAudit
import com.agoii.mobile.core.Replay
import com.agoii.mobile.core.ReplayState
import com.agoii.mobile.core.ReplayTest
import com.agoii.mobile.core.ReplayVerification
import com.agoii.mobile.execution.BuildExecutor

/**
 * CoreBridge — mobile runtime adapter.
 *
 * Responsibilities:
 *  - Provide a single entry point for the UI layer to call core functions.
 *  - Never introduce logic; only delegate to core modules.
 *  - Each method corresponds to exactly one core operation.
 *  - When the last event is contract_started, delegates to BuildExecutor before
 *    allowing the Governor to emit contract_completed. If execution fails, blocks
 *    progression (does not call the Governor).
 */
class CoreBridge(context: Context) {

    private val eventStore    = EventStore(context)
    private val governor      = Governor(eventStore)
    private val ledgerAudit   = LedgerAudit(eventStore)
    private val replay        = Replay(eventStore)
    private val replayTest    = ReplayTest(eventStore)
    private val buildExecutor = BuildExecutor()

    /** Append an intent_submitted event. Called when the user sends an objective. */
    fun submitIntent(projectId: String, objective: String) {
        eventStore.appendEvent(
            projectId,
            "intent_submitted",
            mapOf("objective" to objective)
        )
    }

    /**
     * Trigger one governor step. Returns the result of that step.
     *
     * When the last event is contract_started:
     *  1. Resolves the contract name from the ledger.
     *  2. Calls BuildExecutor.execute(contractName).
     *  3. If execution fails → returns NO_EVENT (blocks contract_completed).
     *  4. If execution passes → lets the Governor proceed naturally.
     */
    fun runGovernorStep(projectId: String): Governor.GovernorResult {
        val events    = eventStore.loadEvents(projectId)
        val lastEvent = events.lastOrNull()

        if (lastEvent?.type == EventTypes.CONTRACT_STARTED) {
            val contractId   = lastEvent.payload["contract_id"]?.toString() ?: ""
            val contractName = resolveContractName(events, contractId)
            val passed       = buildExecutor.execute(contractName)
            if (!passed) {
                return Governor.GovernorResult.NO_EVENT
            }
        }

        return governor.runGovernor(projectId)
    }

    /**
     * Look up the human-readable contract name for the given contract_id by
     * reading the contracts list stored in the contracts_generated event payload.
     * Falls back to the raw contract_id string if the name cannot be resolved.
     */
    private fun resolveContractName(events: List<Event>, contractId: String): String {
        val contractsGenEvent = events.firstOrNull { it.type == EventTypes.CONTRACTS_GENERATED }
        val contracts =
            @Suppress("UNCHECKED_CAST")
            contractsGenEvent?.payload?.get("contracts") as? List<*>
        val match = contracts?.filterIsInstance<Map<*, *>>()
            ?.firstOrNull { it["id"] == contractId }
        return match?.get("name")?.toString() ?: contractId
    }

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
