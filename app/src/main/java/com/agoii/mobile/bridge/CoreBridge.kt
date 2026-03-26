package com.agoii.mobile.bridge

import android.content.Context
import com.agoii.mobile.core.*
import com.agoii.mobile.execution.BuildExecutor
import com.agoii.mobile.execution.ExecutionEntryPoint
import com.agoii.mobile.governor.Governor
import com.agoii.mobile.irs.*
import com.agoii.mobile.observability.ExecutionObservability
import com.agoii.mobile.observability.ExecutionTimeline
import com.agoii.mobile.observability.ExecutionTrace

/**
 * CoreBridge — mobile runtime adapter.
 *
 * Responsibilities:
 *  - Provide a single entry point for the UI layer to call core functions.
 *  - When the last ledger event is INTENT_SUBMITTED:
 *      → Delegate derivation + authorization to ExecutionEntryPoint
 *      → React to ledger state (NOT decision result)
 *      → Trigger Governor progression ONLY if event was written
 *  - Delegate all other transitions to Governor
 *  - Enforce BuildExecutor gate before progression
 *
 * Core Law:
 *  - ZERO validation logic
 *  - ZERO authorization logic
 *  - ZERO contract derivation
 *  - ZERO payload construction
 *
 * Bridge reacts to ledger — never interprets execution decisions.
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
    private val executionEntryPoint = ExecutionEntryPoint(ledger)

    // ✅ Read-only observability layer
    private val observability       = ExecutionObservability(ledger)

    /** Append an intent_submitted event directly to the ledger. */
    fun submitIntent(projectId: String, objective: String) {
        ledger.appendEvent(
            projectId,
            EventTypes.INTENT_SUBMITTED,
            mapOf("objective" to objective)
        )
    }

    /**
     * Trigger one execution step.
     *
     * Returns:
     *  - Event when state advanced
     *  - null when blocked / waiting / terminal
     */
    fun runGovernorStep(projectId: String): Event? {
        val events    = ledger.loadEvents(projectId)
        val lastEvent = events.lastOrNull()

        // ── Intent → Contracts (ExecutionEntryPoint ONLY) ───────────────────────
        if (lastEvent?.type == EventTypes.INTENT_SUBMITTED) {
            val result = executionEntryPoint.executeIntent(projectId, lastEvent.payload)

            // 🔴 CRITICAL: react to ledger write, not decision
            if (result.event != null) {
                governor.runGovernor(projectId)
            }

            return result.event
        }

        // ── Build gate ──────────────────────────────────────────────────────────
        if (lastEvent?.type == EventTypes.CONTRACT_STARTED) {
            val contractId = lastEvent.payload["contract_id"]?.toString() ?: ""
            val contractName = resolveContractName(events, contractId)

            if (!buildExecutor.execute(contractName)) return null
        }

        // ── Governor progression ────────────────────────────────────────────────
        val result = governor.runGovernor(projectId)

        return if (result == Governor.GovernorResult.ADVANCED) {
            ledger.loadEvents(projectId).lastOrNull()
        } else {
            null
        }
    }

    private fun resolveContractName(events: List<Event>, contractId: String): String {
        val contractsEvent = events.firstOrNull {
            it.type == EventTypes.CONTRACTS_GENERATED
        }

        val contracts =
            contractsEvent?.payload?.get("contracts") as? List<*>

        val match = contracts
            ?.filterIsInstance<Map<*, *>>()
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

    /** ✅ Read-only execution trace (observability layer) */
    fun getExecutionTrace(projectId: String): ExecutionTrace =
        observability.trace(projectId)

    /** ✅ Read-only execution timeline (observability layer) */
    fun getExecutionTimeline(projectId: String): ExecutionTimeline =
        observability.timeline(projectId)

    // ─── IRS delegation (interface only; all logic lives in IrsOrchestrator) ──

    fun createIrsSession(
        sessionId:         String,
        rawFields:         Map<String, String>,
        evidence:          Map<String, List<EvidenceRef>>,
        swarmConfig:       SwarmConfig,
        availableEvidence: Map<String, List<EvidenceRef>> = emptyMap()
    ): IrsSession =
        irsOrchestrator.createSession(sessionId, rawFields, evidence, swarmConfig, availableEvidence)

    fun stepIrs(sessionId: String): StepResult =
        irsOrchestrator.step(sessionId)

    fun replayIrs(sessionId: String): List<IrsSnapshot> =
        irsOrchestrator.replayHistory(sessionId)
}
