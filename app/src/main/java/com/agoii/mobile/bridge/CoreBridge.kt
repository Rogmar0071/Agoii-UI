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
import com.agoii.mobile.core.LedgerValidationException
import com.agoii.mobile.core.Replay
import com.agoii.mobile.core.ReplayStructuralState
import com.agoii.mobile.core.ReplayTest
import com.agoii.mobile.core.ReplayVerification
import com.agoii.mobile.core.ValidationLayer
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
 *  - When the last ledger event is [EventTypes.INTENT_SUBMITTED], coordinate the
 *    Execution Authority pipeline: CSO derivation → [authorizeAndAppendContracts]
 *    → [EventLedger] write.
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

    private val eventStore                 = EventStore(context)
    private val ledger                     = EventLedger(eventStore)
    private val governor                   = Governor(ledger)
    private val ledgerAudit                = LedgerAudit(ledger)
    private val replay                     = Replay(ledger)
    private val replayTest                 = ReplayTest(ledger)
    private val buildExecutor              = BuildExecutor()
    private val irsOrchestrator            = IrsOrchestrator()
    private val contractSystemOrchestrator = ContractSystemOrchestrator()
    private val validationLayer            = ValidationLayer()

    companion object {
        private const val DEFAULT_CONSTRAINTS = "standard"
        private const val DEFAULT_ENVIRONMENT = "mobile"
        private const val DEFAULT_RESOURCES   = "available"

        /**
         * Default agent profile used when evaluating [ContractSystemOrchestrator].
         * Represents a maximally capable agent for deterministic contract derivation.
         *
         * All capability dimensions use a 0–3 scale (higher = more capable).
         * driftTendency uses an inverted scale (0 = no drift, 3 = high drift).
         * This profile maximises every positive dimension and minimises drift, ensuring
         * contracts derived from any valid objective reach [readyForExecution] = true.
         */
        private val DEFAULT_AGENT_PROFILE = AgentProfile(
            agentId             = "default-agent",
            constraintObedience = 3,  // maximum: always follows constraints
            structuralAccuracy  = 3,  // maximum: always follows structure
            driftTendency       = 0,  // minimum: zero deviation tendency
            complexityHandling  = 3,  // maximum: handles complex multi-step plans
            outputReliability   = 3   // maximum: fully deterministic output
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
     *  3. Maps [ExecutionStep] list to contract descriptors.
     *  4. Passes the payload through [authorizeAndAppendContracts] (Execution Authority).
     *  5. On success: persists [EventTypes.CONTRACTS_GENERATED] via [EventLedger].
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
     * builds the [EventTypes.CONTRACTS_GENERATED] payload, and delegates persistence to
     * [authorizeAndAppendContracts] (the Execution Authority boundary).
     *
     * Returns the appended [Event], or null if derivation produced no valid steps.
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

        // Prefer the adapted plan when the agent matcher returned ADAPT (the
        // ContractSystemOrchestrator applied structural hardening — see ContractAdapter);
        // fall back to the original derivation plan for a direct ACCEPT
        // (adaptedContract is null, scoredContract holds the final plan).
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

        return authorizeAndAppendContracts(projectId, payload)
    }

    /**
     * Execution Authority boundary — the ONLY path through which
     * [EventTypes.CONTRACTS_GENERATED] may be written to the ledger.
     *
     * Three sequential phases must ALL succeed before any write occurs:
     *
     * **Phase 1 — Validation**
     * Delegates to [ValidationLayer] to enforce structural correctness,
     * legal transition, and payload schema rules. Any violation blocks the write.
     *
     * **Phase 2 — Authorization**
     * Enforces business-level rules independently of structural validation:
     *  - contracts list must be non-empty
     *  - total must be > 0
     *  - every contract entry must have non-blank `id`, `name`, and a non-null `position`
     *
     * **Phase 3 — Write**
     * Only reached when both Phase 1 and Phase 2 pass.
     * Writes the event exclusively through [EventLedger] — the sole write authority.
     *
     * Returns the appended [Event], or null if either phase fails.
     */
    private fun authorizeAndAppendContracts(
        projectId: String,
        payload:   Map<String, Any>
    ): Event? {
        // ── Phase 1: Validation ──────────────────────────────────────────────────
        // Structural correctness, legal transition, and payload schema enforcement.
        val currentEvents = ledger.loadEvents(projectId)
        try {
            validationLayer.validate(
                projectId     = projectId,
                type          = EventTypes.CONTRACTS_GENERATED,
                payload       = payload,
                currentEvents = currentEvents
            )
        } catch (e: LedgerValidationException) {
            return null
        }

        // ── Phase 2: Authorization ───────────────────────────────────────────────
        // Business-rule enforcement: contracts must be non-empty with valid fields.
        @Suppress("UNCHECKED_CAST")
        val contracts = payload["contracts"] as? List<Map<String, Any>>
        if (contracts.isNullOrEmpty()) return null

        val total: Int = resolvePayloadInt(payload["total"]) ?: return null
        if (total <= 0) return null

        val allFieldsValid = contracts.all { contract ->
            !contract["id"]?.toString().isNullOrBlank() &&
            !contract["name"]?.toString().isNullOrBlank() &&
            contract["position"] != null
        }
        if (!allFieldsValid) return null

        // ── Phase 3: Write ───────────────────────────────────────────────────────
        // EventLedger is the sole write authority; all validation has passed.
        ledger.appendEvent(projectId, EventTypes.CONTRACTS_GENERATED, payload)
        return Event(type = EventTypes.CONTRACTS_GENERATED, payload = payload)
    }

    /**
     * Normalises a numeric payload value to [Int]. Accepts [Int], [Long], and [Double]
     * (Gson serialises all JSON numbers as [Double]). Returns null for any other type.
     */
    private fun resolvePayloadInt(value: Any?): Int? = when (value) {
        is Int    -> value
        is Long   -> value.toInt()
        is Double -> value.toInt()
        else      -> null
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
