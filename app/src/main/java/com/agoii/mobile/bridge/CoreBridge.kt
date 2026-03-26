package com.agoii.mobile.bridge

import android.content.Context
import com.agoii.mobile.contracts.AgentProfile
import com.agoii.mobile.contracts.ContractIntent
import com.agoii.mobile.contracts.ContractSystemOrchestrator
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
 * CoreBridge — mobile runtime adapter and Execution Authority.
 *
 * Responsibilities:
 *  - Provide a single entry point for the UI layer to call core functions.
 *  - Act as the Execution Authority for contract derivation: when the last ledger
 *    event is [EventTypes.INTENT_SUBMITTED], CoreBridge calls [ContractSystemOrchestrator],
 *    transforms the result into a contracts payload, validates it, and persists
 *    [EventTypes.CONTRACTS_GENERATED] via [EventLedger] before Governor is called.
 *  - Delegate all subsequent ledger transitions to [Governor.runGovernor].
 *  - When the last event is [EventTypes.CONTRACT_STARTED], delegate to [BuildExecutor]
 *    before the Governor step; block progression if execution fails.
 *
 * Write authority:
 *  - All writes flow through [EventLedger] — the single write authority.
 *  - [EventStore] is the backing persistence layer; [EventLedger] wraps it with
 *    per-project locking, pre-write validation, and fail-fast integrity checks.
 *  - Governor writes directly through its own reference to [EventLedger].
 */
class CoreBridge(context: Context) {

    private val eventStore              = EventStore(context)
    private val ledger                  = EventLedger(eventStore)
    private val governor                = Governor(ledger)
    private val ledgerAudit             = LedgerAudit(ledger)
    private val replay                  = Replay(ledger)
    private val replayTest              = ReplayTest(ledger)
    private val buildExecutor           = BuildExecutor()
    private val irsOrchestrator         = IrsOrchestrator()
    private val contractSystemOrchestrator = ContractSystemOrchestrator()

    companion object {
        private const val DEFAULT_CONSTRAINTS = "standard"
        private const val DEFAULT_ENVIRONMENT = "mobile"
        private const val DEFAULT_RESOURCES   = "available"

        /**
         * Default agent profile used when evaluating [ContractSystemOrchestrator].
         * Represents a maximally capable agent for deterministic contract derivation.
         */
        private val DEFAULT_AGENT_PROFILE = AgentProfile(
            agentId             = "default-agent",
            constraintObedience = 3,
            structuralAccuracy  = 3,
            driftTendency       = 0,
            complexityHandling  = 3,
            outputReliability   = 3
        )
    }

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
     *  1. Extracts the objective from the event payload.
     *  2. Calls [ContractSystemOrchestrator.evaluate] to derive contracts.
     *  3. If not [readyForExecution] → returns null (blocks progression).
     *  4. Maps [ExecutionStep] list to contract descriptors.
     *  5. Persists [EventTypes.CONTRACTS_GENERATED] via [EventLedger] (Execution Authority).
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

        // ── Execution Authority: derive and persist CONTRACTS_GENERATED ──────────
        if (lastEvent?.type == EventTypes.INTENT_SUBMITTED) {
            return deriveAndWriteContracts(projectId, lastEvent.payload)
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
     * Derives contracts from [ContractSystemOrchestrator] for the given intent payload,
     * builds the [EventTypes.CONTRACTS_GENERATED] payload, and persists it via [EventLedger].
     *
     * Returns a shell [Event] representing the written event, or null if derivation
     * failed or the orchestrator did not produce a ready-for-execution plan.
     *
     * Mapping (locked):
     *  - `id`       = "contract_{step.position}"
     *  - `name`     = step.description
     *  - `position` = step.position
     */
    private fun deriveAndWriteContracts(
        projectId:     String,
        intentPayload: Map<String, Any>
    ): Event? {
        val objective = intentPayload["objective"] as? String ?: return null

        val intent = ContractIntent(
            objective    = objective,
            constraints  = DEFAULT_CONSTRAINTS,
            environment  = DEFAULT_ENVIRONMENT,
            resources    = DEFAULT_RESOURCES
        )

        val result = contractSystemOrchestrator.evaluate(intent, DEFAULT_AGENT_PROFILE)
        if (!result.readyForExecution) return null

        val steps = result.adaptedContract?.adaptedPlan?.steps
            ?: result.scoredContract?.derivation?.executionPlan?.steps
            ?: return null
        if (steps.isEmpty()) return null

        val contracts: List<Map<String, Any>> = steps.map { step ->
            mapOf(
                "id"       to "contract_${step.position}",
                "name"     to step.description,
                "position" to step.position
            )
        }

        val payload: Map<String, Any> = mapOf(
            "contracts" to contracts,
            "total"     to contracts.size
        )

        // Execution Authority: all writes go through EventLedger.
        ledger.appendEvent(projectId, EventTypes.CONTRACTS_GENERATED, payload)
        return Event(type = EventTypes.CONTRACTS_GENERATED, payload = payload)
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
