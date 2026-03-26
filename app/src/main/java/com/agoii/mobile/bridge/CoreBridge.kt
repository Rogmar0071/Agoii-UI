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
import com.agoii.mobile.execution.ExecutionEntryPoint
import com.agoii.mobile.irs.EvidenceRef
import com.agoii.mobile.irs.IrsOrchestrator
import com.agoii.mobile.irs.IrsSession
import com.agoii.mobile.irs.IrsSnapshot
import com.agoii.mobile.irs.StepResult
import com.agoii.mobile.irs.SwarmConfig

/**
 * CoreBridge — mobile runtime adapter (transport only).
 *
 * Responsibilities:
 *  - Provide a single entry point for the UI layer to call core functions.
 *  - When the last ledger event is [EventTypes.INTENT_SUBMITTED], delegate to
 *    [ExecutionEntryPoint] which coordinates CSO derivation → [ExecutionAuthority]
 *    → [EventLedger] write → [Governor] step.
 *  - Delegate all subsequent ledger transitions to [Governor.runGovernor].
 *  - When the last event is [EventTypes.CONTRACT_STARTED], delegate to [BuildExecutor]
 *    before the Governor step; block progression if execution fails.
 *
 * Bridge law:
 *  - CoreBridge makes ZERO authority decisions.
 *  - CoreBridge does NOT call [ExecutionAuthority] or [com.agoii.mobile.contracts.ContractSystemOrchestrator].
 *  - All execution authority flows through [ExecutionEntryPoint].
 *  - All writes flow through [EventLedger] — the single write authority.
 */
class CoreBridge(context: Context) {

    private val eventStore          = EventStore(context)
    private val ledger              = EventLedger(eventStore)
    private val governor            = Governor(ledger)
    private val ledgerAudit         = LedgerAudit(ledger)
    private val replay              = Replay(ledger)
    private val replayTest          = ReplayTest(ledger)
    private val buildExecutor       = BuildExecutor()
    private val irsOrchestrator     = IrsOrchestrator()
    private val executionEntryPoint = ExecutionEntryPoint(ledger, governor)

    /** Append an intent_submitted event directly to the ledger. */
    fun submitIntent(projectId: String, objective: String) {
        ledger.appendEvent(projectId, EventTypes.INTENT_SUBMITTED, mapOf("objective" to objective))
    }

    /**
     * Trigger one execution step. Returns the [Event] appended, or null if no
     * transition was made (wait state, derivation failure, execution gate block,
     * terminal state, or drift).
     *
     * When the last event is [EventTypes.INTENT_SUBMITTED]:
     *  - Delegates entirely to [ExecutionEntryPoint.executeIntent], which coordinates
     *    CSO derivation → [ExecutionAuthority] → ledger write → first Governor step.
     *  - Returns the persisted [EventTypes.CONTRACTS_GENERATED] event on success, or
     *    null when [ExecutionEntryPoint] returns a blocked result.
     *
     * When the last event is [EventTypes.CONTRACT_STARTED]:
     *  1. Resolves the contract name from the ledger.
     *  2. Calls [BuildExecutor.execute].
     *  3. If execution fails → returns null (blocks progression).
     *
     * All other transitions are delegated to [Governor.runGovernor].
     */
    fun runGovernorStep(projectId: String): Event? {
        val events    = ledger.loadEvents(projectId)
        val lastEvent = events.lastOrNull()

        // ── Delegate contract derivation + authorization to ExecutionEntryPoint ──
        if (lastEvent?.type == EventTypes.INTENT_SUBMITTED) {
            val result = executionEntryPoint.executeIntent(projectId, lastEvent.payload)
            return result.event
        }

        // ── BuildExecutor gate ────────────────────────────────────────────────────
        if (lastEvent?.type == EventTypes.CONTRACT_STARTED) {
            val contractId   = lastEvent.payload["contract_id"]?.toString() ?: ""
            val contractName = resolveContractName(events, contractId)
            if (!buildExecutor.execute(contractName)) return null
        }

        // ── Governor step ─────────────────────────────────────────────────────────
        val result = governor.runGovernor(projectId)
        return if (result == Governor.GovernorResult.ADVANCED) {
            ledger.loadEvents(projectId).lastOrNull()
        } else {
            null
        }
    }

    /**
     * Look up the human-readable contract name for the given [contractId] by
     * reading the contracts list stored in the [EventTypes.CONTRACTS_GENERATED] payload.
     * Falls back to the raw [contractId] string if the name cannot be resolved.
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

    /** Append a contracts_approved event directly to the ledger (explicit governance gate). */
    fun approveContracts(projectId: String) {
        ledger.appendEvent(projectId, EventTypes.CONTRACTS_APPROVED, emptyMap())
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

