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
import com.agoii.mobile.ingress.ContractStatus
import com.agoii.mobile.ingress.IngressContract
import com.agoii.mobile.ingress.IntentType
import com.agoii.mobile.ingress.Payload
import com.agoii.mobile.ingress.References
import com.agoii.mobile.ingress.Scope
import com.agoii.mobile.irs.ConsensusRule
import com.agoii.mobile.irs.EvidenceRef
import com.agoii.mobile.irs.IrsOrchestrator
import com.agoii.mobile.irs.IrsSession
import com.agoii.mobile.irs.IrsSnapshot
import com.agoii.mobile.irs.OrchestratorResult
import com.agoii.mobile.irs.StepResult
import com.agoii.mobile.irs.SwarmConfig
import java.util.UUID

/**
 * CoreBridge — mobile runtime adapter.
 *
 * Responsibilities:
 *  - Provide a single entry point for the UI layer to call core functions.
 *  - Routes every intent submission through the Ingress + ICS authority chain
 *    before any ledger write is attempted.
 *  - No orchestration logic; no Governor internal state awareness.
 *  - When the last event is contract_started, delegates to BuildExecutor before
 *    calling the Governor. If execution fails, returns null (blocks progression).
 *
 * Intent authority chain ([submitIntent]):
 *  1. [IngressContract] — normalises and classifies raw input (entry contract boundary).
 *  2. [IrsOrchestrator] — 9-stage ICS certification pipeline.
 *  3. [EventLedger]     — write gate: INTENT_SUBMITTED only on [OrchestratorResult.Certified].
 *
 * Write authority:
 *  - All writes flow through [EventLedger] — the single write authority.
 *  - [EventStore] is the backing persistence layer; [EventLedger] wraps it with
 *    per-project locking, pre-write validation, and fail-fast integrity checks.
 */
class CoreBridge(context: Context) {

    private val eventStore      = EventStore(context)
    private val ledger          = EventLedger(eventStore)
    private val governor        = Governor()
    private val ledgerAudit     = LedgerAudit(ledger)
    private val replay          = Replay(ledger)
    private val replayTest      = ReplayTest(ledger)
    private val buildExecutor   = BuildExecutor()
    private val irsOrchestrator = IrsOrchestrator()

    companion object {
        // ── ICS default context for objective-only intent submission ───────────
        private const val DEFAULT_CONSTRAINTS = "standard"
        private const val DEFAULT_ENVIRONMENT = "mobile"
        private const val DEFAULT_RESOURCES   = "available"
    }

    /**
     * Submit an intent through the Ingress + ICS authority chain.
     *
     * Flow:
     *  1. Build an [IngressContract] from raw input — normalises and classifies the intent.
     *  2. Create an ICS (IRS) session and run the full 9-stage certification pipeline.
     *  3. Write INTENT_SUBMITTED to the ledger ONLY when the pipeline emits
     *     [OrchestratorResult.Certified].
     *  4. Throw [IllegalArgumentException] on any non-certified outcome.
     *
     * @throws IllegalArgumentException when the intent is rejected or requires clarification.
     */
    fun submitIntent(projectId: String, objective: String) {
        // ── 1. Ingress: build a typed contract from raw input ─────────────────
        val ingressContractId = UUID.randomUUID().toString()
        val normalized = objective.trim().replace(Regex("\\s+"), " ")
        val ingressContract = IngressContract(
            contractId = ingressContractId,
            intentType = IntentType.ACTION,
            scope      = Scope.SYSTEM,
            references = References(),
            payload    = Payload(
                rawInput         = objective,
                normalizedIntent = normalized,
                extractedFields  = emptyMap()
            ),
            status = ContractStatus.PENDING
        )

        // ── 2. ICS (IRS): run the full 9-stage certification pipeline ─────────
        //
        // System-default context fills the mandatory IRS fields that are not provided
        // by the basic intent submission path (objective only).  These defaults represent
        // the standard mobile-application deployment context and carry no semantic bias.
        irsOrchestrator.createSession(
            sessionId         = ingressContract.contractId,
            rawFields         = mapOf(
                "objective"   to ingressContract.payload.normalizedIntent,
                "constraints" to DEFAULT_CONSTRAINTS,
                "environment" to DEFAULT_ENVIRONMENT,
                "resources"   to DEFAULT_RESOURCES
            ),
            evidence          = mapOf(
                "objective"   to listOf(
                    EvidenceRef("ingress-${ingressContractId}-obj-a", "intent-objective-goal"),
                    EvidenceRef("ingress-${ingressContractId}-obj-b", "intent-requirement-spec")
                ),
                "constraints" to listOf(EvidenceRef("ingress-${ingressContractId}-con", "constraint-policy-rule")),
                "environment" to listOf(EvidenceRef("ingress-${ingressContractId}-env", "environment-platform-infra")),
                "resources"   to listOf(EvidenceRef("ingress-${ingressContractId}-res", "resource-system-service"))
            ),
            swarmConfig       = SwarmConfig(agentCount = 2, consensusRule = ConsensusRule.MAJORITY),
            availableEvidence = emptyMap()
        )

        var step = irsOrchestrator.step(ingressContract.contractId)
        while (!step.terminal) { step = irsOrchestrator.step(ingressContract.contractId) }

        // ── 3. Gate: only ICS-certified intent enters the ledger ──────────────
        when (val outcome = step.orchestratorResult) {
            OrchestratorResult.Certified ->
                ledger.appendEvent(
                    projectId, EventTypes.INTENT_SUBMITTED,
                    mapOf("objective" to ingressContract.payload.normalizedIntent)
                )
            is OrchestratorResult.NeedsClarification ->
                throw IllegalArgumentException(
                    "Intent rejected — clarification required: ${outcome.gaps.joinToString("; ")}"
                )
            is OrchestratorResult.Rejected ->
                throw IllegalArgumentException(
                    "Intent rejected [${outcome.reason}]: ${outcome.details.joinToString("; ")}"
                )
            null ->
                throw IllegalStateException(
                    "IRS reached terminal state without a result for session '${ingressContract.contractId}'"
                )
        }
    }

    /**
     * Trigger one governor step. Returns the next [Event] appended, or null if
     * the Governor has no transition to emit (wait state, terminal, or drift).
     *
     * When the last event is contract_started:
     *  1. Resolves the contract name from the ledger.
     *  2. Calls BuildExecutor.execute(contractName).
     *  3. If execution fails → returns null (blocks contract_completed).
     *  4. If execution passes → lets the Governor proceed naturally.
     */
    fun runGovernorStep(projectId: String): Event? {
        val events    = ledger.loadEvents(projectId)
        val lastEvent = events.lastOrNull()

        if (lastEvent?.type == EventTypes.CONTRACT_STARTED) {
            val contractId   = lastEvent.payload["contract_id"]?.toString() ?: ""
            val contractName = resolveContractName(events, contractId)
            val passed       = buildExecutor.execute(contractName)
            if (!passed) {
                return null
            }
        }

        val next = governor.nextEvent(events)
        if (next != null) {
            ledger.appendEvent(projectId, next.type, next.payload)
        }
        return next
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
