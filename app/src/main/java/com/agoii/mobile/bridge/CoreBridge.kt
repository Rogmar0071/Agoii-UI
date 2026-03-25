package com.agoii.mobile.bridge

import android.content.Context
import com.agoii.mobile.core.AuditResult
import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventLedger
import com.agoii.mobile.core.EventStore
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.governor.Governor
import com.agoii.mobile.core.LedgerAudit
import com.agoii.mobile.core.Replay
import com.agoii.mobile.core.ReplayStructuralState
import com.agoii.mobile.core.ReplayTest
import com.agoii.mobile.core.ReplayVerification
import com.agoii.mobile.execution.BuildExecutor
import com.agoii.mobile.irs.EvidenceRef
import com.agoii.mobile.irs.IrsOrchestrator
import com.agoii.mobile.irs.IrsSession
import com.agoii.mobile.irs.IrsSnapshot
import com.agoii.mobile.irs.StepResult
import com.agoii.mobile.irs.SwarmConfig

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
 *
 * Write authority:
 *  - All writes flow through [EventLedger] — the single write authority.
 *  - [EventStore] is the backing persistence layer; [EventLedger] wraps it with
 *    per-project locking, pre-write validation, and fail-fast integrity checks.
 */
class CoreBridge(context: Context) {

    private val eventStore    = EventStore(context)
    private val ledger        = EventLedger(eventStore)
    private val governor      = Governor(ledger)
    private val ledgerAudit   = LedgerAudit(ledger)
    private val replay        = Replay(ledger)
    private val replayTest    = ReplayTest(ledger)
    private val buildExecutor = BuildExecutor()
    private val irsOrchestrator = IrsOrchestrator()

    /** Append an intent_submitted event via Governor (sole mutation authority). */
    fun submitIntent(projectId: String, objective: String) {
        governor.submitIntent(projectId, objective)
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
        val events    = ledger.loadEvents(projectId)
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

    /** Append a contracts_approved event via Governor (sole mutation authority). */
    fun approveContracts(projectId: String) {
        governor.approveContracts(projectId)
    }

    /** Load all events from the ledger (read-only). */
    fun loadEvents(projectId: String): List<Event> =
        ledger.loadEvents(projectId)

    /** Derive current state by replaying the ledger (read-only). */
    fun replayState(projectId: String): ReplayStructuralState =
        replay.replayStructuralState(projectId)

    /** Run the ledger audit (read-only). */
    fun auditLedger(projectId: String): AuditResult =
        ledgerAudit.auditLedger(projectId)

    /** Run full replay verification: audit + invariant checks (read-only). */
    fun verifyReplay(projectId: String): ReplayVerification =
        replayTest.verifyReplay(projectId)

    // ─── IRS delegation (interface only; all logic lives in IrsOrchestrator) ──

    /**
     * Create a new IRS session.
     *
     * @param sessionId        Unique identifier for the session.
     * @param rawFields        Raw intent field values (objective, constraints, environment, resources).
     * @param evidence         Evidence refs keyed by field name.
     * @param swarmConfig      Swarm parameters; agentCount must be ≥ 2.
     * @param availableEvidence Supplementary evidence pool for the ScoutOrchestrator.
     */
    fun createIrsSession(
        sessionId:         String,
        rawFields:         Map<String, String>,
        evidence:          Map<String, List<EvidenceRef>>,
        swarmConfig:       SwarmConfig,
        availableEvidence: Map<String, List<EvidenceRef>> = emptyMap()
    ): IrsSession =
        irsOrchestrator.createSession(sessionId, rawFields, evidence, swarmConfig, availableEvidence)

    /**
     * Advance the IRS session by exactly one stage.
     * External driver must call this repeatedly until [StepResult.terminal] is true.
     */
    fun stepIrs(sessionId: String): StepResult =
        irsOrchestrator.step(sessionId)

    /**
     * Replay the full ordered snapshot history for an IRS session (read-only).
     * Supports audit and deterministic re-execution.
     */
    fun replayIrs(sessionId: String): List<IrsSnapshot> =
        irsOrchestrator.replayHistory(sessionId)
}
