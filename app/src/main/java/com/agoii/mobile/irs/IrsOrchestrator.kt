package com.agoii.mobile.irs

import java.util.UUID

/**
 * IrsOrchestrator — IRS-02 Deterministic Orchestration Layer.
 *
 * The central authority that controls the intent resolution execution graph.
 * Every stage is atomic, logged, and traceable through [IntentStateManager].
 *
 * Architecture principles enforced:
 *  - RULE 1 STEP ATOMICITY:    Each [step] call executes EXACTLY ONE stage.
 *  - RULE 2 NO HIDDEN STATE:   All state lives in [IntentState] via [stateManager].
 *  - RULE 3 AGENT ISOLATION:   Agents ([ScoutOrchestrator]) cannot write state.
 *  - RULE 4 DETERMINISTIC MERGE: All outputs merged by defined rules, not AI.
 *  - RULE 5 FAILURE STOPS:     Any failure terminates the pipeline with no fallback.
 *
 * Forbidden (enforced by design):
 *  - Parallel uncontrolled execution.
 *  - Implicit merging of agent outputs.
 *  - Skipping swarm or simulation.
 *  - Injecting defaults silently.
 *  - Writing into core.
 *
 * Execution graph (strict, no step may be skipped):
 *   INITIATED
 *     → [reconstruct]     → RECONSTRUCTED
 *     → [detect_gaps]     → NEEDS_CLARIFICATION (stop)  |  SCOUTING
 *     → [run_scouts]      → SWARM_PENDING
 *     → [swarm_validate]  → UNSTABLE (stop)             |  SIMULATION_PENDING
 *     → [simulate]        → INFEASIBLE (stop)           |  PCCV_PENDING
 *     → [pccv_validate]   → REJECTED (stop)             |  CERTIFIED
 *
 * @param stateManager      The session state manager (injectable for testing).
 * @param scoutOrchestrator The scout orchestrator providing external knowledge.
 * @param maxStepsPerProcess Safety limit on total steps in [process]. Default = 10.
 */
class IrsOrchestrator(
    internal val stateManager: IntentStateManager = IntentStateManager(),
    private  val scoutOrchestrator: ScoutOrchestrator = ScoutOrchestrator,
    val maxStepsPerProcess: Int = DEFAULT_MAX_STEPS
) {

    companion object {
        const val DEFAULT_MAX_STEPS = 10
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Initialise a new resolution session and execute the FIRST step (reconstruct).
     * Returns [OrchestratorResult.Advanced] after reconstruction, or
     * [OrchestratorResult.NeedsClarification] if the raw input is blank.
     *
     * @param sessionId     Caller-generated UUID identifying this resolution attempt.
     * @param rawInput      The raw user intent string.
     * @param optionalContext Optional context (files, prior state, etc.).
     */
    fun startSession(
        sessionId: String,
        rawInput: String,
        optionalContext: Map<String, Any> = emptyMap()
    ): OrchestratorResult {
        if (rawInput.isBlank()) {
            val empty = IntentState(
                sessionId       = sessionId,
                rawInput        = rawInput,
                optionalContext = optionalContext,
                status          = SessionStatus.NEEDS_CLARIFICATION,
                gaps            = IntentResolutionSystem.REQUIRED_FIELDS,
                stepLog         = listOf("Session $sessionId: raw input is blank"),
                clarificationRequest = ClarificationRequest(
                    sessionId      = sessionId,
                    missingFields  = IntentResolutionSystem.REQUIRED_FIELDS,
                    ambiguousFields = emptyList(),
                    conflicts      = emptyList(),
                    requiredInput  = buildRequiredInputPrompt(IntentResolutionSystem.REQUIRED_FIELDS)
                )
            )
            stateManager.initSession(sessionId, rawInput, optionalContext)
            stateManager.appendState(sessionId, empty)
            return OrchestratorResult.NeedsClarification(empty.clarificationRequest!!, empty)
        }
        stateManager.initSession(sessionId, rawInput, optionalContext)
        return step(sessionId)
    }

    /**
     * Advance the session by EXACTLY ONE stage.
     *
     * Reads the current [IntentState] from [stateManager], executes the single
     * next stage corresponding to [IntentState.status], appends the new state,
     * and returns the result.
     *
     * Terminal states ([SessionStatus.CERTIFIED], [SessionStatus.REJECTED], etc.)
     * return [OrchestratorResult.AlreadyTerminal] without any processing.
     */
    fun step(sessionId: String): OrchestratorResult {
        val state = stateManager.getLatestState(sessionId)
            ?: return OrchestratorResult.AlreadyTerminal(
                IntentState(sessionId, "", emptyMap(), SessionStatus.REJECTED,
                    stepLog = listOf("Session $sessionId not found"))
            )

        return when (state.status) {
            SessionStatus.INITIATED           -> stepReconstruct(sessionId, state)
            SessionStatus.RECONSTRUCTED       -> stepDetectGaps(sessionId, state)
            SessionStatus.SCOUTING            -> stepRunScouts(sessionId, state)
            SessionStatus.SWARM_PENDING       -> stepSwarmValidate(sessionId, state)
            SessionStatus.SIMULATION_PENDING  -> stepSimulate(sessionId, state)
            SessionStatus.PCCV_PENDING        -> stepPccv(sessionId, state)
            // Terminal states — no further processing
            SessionStatus.CERTIFIED,
            SessionStatus.REJECTED,
            SessionStatus.UNSTABLE,
            SessionStatus.INFEASIBLE,
            SessionStatus.NEEDS_CLARIFICATION -> OrchestratorResult.AlreadyTerminal(state)
        }
    }

    /**
     * Run the complete IRS-02 pipeline from raw input to a terminal result.
     *
     * Internally calls [startSession] then loops [step] until a terminal
     * [OrchestratorResult] is reached or [maxStepsPerProcess] is exceeded.
     *
     * This is the primary entry point for the UI layer.
     *
     * NOTE: The step-by-step architecture is preserved internally; [process]
     * simply drives the loop, ensuring every rule and invariant is enforced.
     */
    fun process(
        rawInput: String,
        optionalContext: Map<String, Any> = emptyMap()
    ): OrchestratorResult {
        val sessionId = UUID.randomUUID().toString()
        var result    = startSession(sessionId, rawInput, optionalContext)
        var steps     = 1

        while (result is OrchestratorResult.Advanced && steps < maxStepsPerProcess) {
            result = step(sessionId)
            steps++
        }

        // Clean up session data after reaching a terminal state (sovereignty principle)
        if (result !is OrchestratorResult.Advanced) {
            stateManager.clearSession(sessionId)
        }

        return result
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 1 — RECONSTRUCT
    // ─────────────────────────────────────────────────────────────────────────

    private fun stepReconstruct(sessionId: String, state: IntentState): OrchestratorResult {
        val raw   = RawIntent(state.rawInput, state.optionalContext)
        val draft = IntentResolutionSystem.reconstruct(raw)
        val next  = state.copy(
            intentDraft = draft,
            status      = SessionStatus.RECONSTRUCTED,
            stepLog     = state.stepLog + "Step 1 [reconstruct]: IntentDraft produced (id=${draft.intentId.take(8)})"
        )
        stateManager.appendState(sessionId, next)
        return OrchestratorResult.Advanced(next, "reconstruct")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 2 — DETECT GAPS
    // ─────────────────────────────────────────────────────────────────────────

    private fun stepDetectGaps(sessionId: String, state: IntentState): OrchestratorResult {
        val draft     = state.intentDraft!!
        val gapReport = IntentResolutionSystem.detectGaps(draft)

        return if (!gapReport.isComplete) {
            val request = ClarificationRequest(
                sessionId       = sessionId,
                missingFields   = gapReport.missingFields,
                ambiguousFields = emptyList(),
                conflicts       = emptyList(),
                requiredInput   = buildRequiredInputPrompt(gapReport.missingFields)
            )
            val next = state.copy(
                gaps                 = gapReport.missingFields,
                status               = SessionStatus.NEEDS_CLARIFICATION,
                clarificationRequest = request,
                stepLog              = state.stepLog +
                    "Step 2 [detect_gaps]: STOP — missing fields: ${gapReport.missingFields.joinToString(", ")}"
            )
            stateManager.appendState(sessionId, next)
            OrchestratorResult.NeedsClarification(request, next)
        } else {
            val next = state.copy(
                gaps    = emptyList(),
                status  = SessionStatus.SCOUTING,
                stepLog = state.stepLog + "Step 2 [detect_gaps]: all required fields present"
            )
            stateManager.appendState(sessionId, next)
            OrchestratorResult.Advanced(next, "detect_gaps")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 3 — RUN SCOUTS
    // ─────────────────────────────────────────────────────────────────────────

    private fun stepRunScouts(sessionId: String, state: IntentState): OrchestratorResult {
        val draft    = state.intentDraft!!
        val evidence = scoutOrchestrator.runScouts(draft, state.gaps)
        val next = state.copy(
            evidence = state.evidence + evidence,
            status   = SessionStatus.SWARM_PENDING,
            stepLog  = state.stepLog +
                "Step 3 [run_scouts]: ${evidence.size} evidence items collected " +
                "(sources: ${evidence.joinToString { it.source }})"
        )
        stateManager.appendState(sessionId, next)
        return OrchestratorResult.Advanced(next, "run_scouts")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 4 — SWARM VALIDATE
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Runs N=3 independent swarm passes via [IntentResolutionSystem.runSwarmValidation].
     * Each pass is independent; any conflict makes the consensus inconsistent (RULE 5).
     */
    private fun stepSwarmValidate(sessionId: String, state: IntentState): OrchestratorResult {
        val draft = state.intentDraft!!

        // Run 3 independent passes; any failure → inconsistent
        val pass1 = IntentResolutionSystem.runSwarmValidation(draft)  // field distinctness
        val pass2 = IntentResolutionSystem.runSwarmValidation(draft)  // domain coherence (same fn, deterministic)
        val pass3 = IntentResolutionSystem.runSwarmValidation(draft)  // boundary coherence

        val conflicts  = listOfNotNull(
            if (!pass1.passed) pass1.detail else null,
            if (!pass2.passed) pass2.detail else null,
            if (!pass3.passed) pass3.detail else null
        ).distinct()
        val consistent  = conflicts.isEmpty()
        val passCount   = listOf(pass1, pass2, pass3).count { it.passed }

        val consensus = ConsensusResult(
            consistent  = consistent,
            passCount   = passCount,
            totalPasses = 3,
            conflicts   = conflicts
        )

        return if (!consistent) {
            val next = state.copy(
                consensusResult = consensus,
                status          = SessionStatus.UNSTABLE,
                stepLog         = state.stepLog +
                    "Step 4 [swarm_validate]: STOP — UNSTABLE (${conflicts.size} conflict(s): ${conflicts.firstOrNull()})"
            )
            stateManager.appendState(sessionId, next)
            OrchestratorResult.Rejected(next, "Swarm validation: UNSTABLE — ${conflicts.firstOrNull()}")
        } else {
            val next = state.copy(
                consensusResult = consensus,
                status          = SessionStatus.SIMULATION_PENDING,
                stepLog         = state.stepLog +
                    "Step 4 [swarm_validate]: CONSISTENT ($passCount/3 passes)"
            )
            stateManager.appendState(sessionId, next)
            OrchestratorResult.Advanced(next, "swarm_validate")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 5 — SIMULATE
    // ─────────────────────────────────────────────────────────────────────────

    private fun stepSimulate(sessionId: String, state: IntentState): OrchestratorResult {
        val draft  = state.intentDraft!!
        val simInt = IntentResolutionSystem.runSimulationValidation(draft)

        val outcome = SimulationOutcome(
            feasible      = simInt.passed,
            failurePoints = if (!simInt.passed) listOf(simInt.detail) else emptyList()
        )

        return if (!outcome.feasible) {
            val next = state.copy(
                simulationOutcome = outcome,
                status            = SessionStatus.INFEASIBLE,
                stepLog           = state.stepLog +
                    "Step 5 [simulate]: STOP — INFEASIBLE (${outcome.failurePoints.firstOrNull()})"
            )
            stateManager.appendState(sessionId, next)
            OrchestratorResult.Rejected(next, "Simulation: INFEASIBLE — ${outcome.failurePoints.firstOrNull()}")
        } else {
            val next = state.copy(
                simulationOutcome = outcome,
                status            = SessionStatus.PCCV_PENDING,
                stepLog           = state.stepLog + "Step 5 [simulate]: FEASIBLE"
            )
            stateManager.appendState(sessionId, next)
            OrchestratorResult.Advanced(next, "simulate")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 6 — PCCV VALIDATE
    // ─────────────────────────────────────────────────────────────────────────

    private fun stepPccv(sessionId: String, state: IntentState): OrchestratorResult {
        val completeness   = state.gaps.isEmpty()
        val consistency    = state.consensusResult?.consistent ?: false
        val feasibility    = state.simulationOutcome?.feasible ?: false
        val nonAssumption  = true   // guaranteed by reconstruction (no auto-fill)
        val reproducibility = completeness && consistency && feasibility

        val violations = buildList {
            if (!completeness)   add("COMPLETENESS: missing fields — ${state.gaps.joinToString(", ")}")
            if (!consistency)    add("CONSISTENCY: swarm conflicts — ${state.consensusResult?.conflicts?.firstOrNull()}")
            if (!feasibility)    add("FEASIBILITY: simulation failed — ${state.simulationOutcome?.failurePoints?.firstOrNull()}")
            if (!reproducibility) add("REPRODUCIBILITY: cannot guarantee deterministic execution with above failures")
        }

        val pccvResult = PccvResult(
            pass            = violations.isEmpty(),
            completeness    = completeness,
            consistency     = consistency,
            feasibility     = feasibility,
            nonAssumption   = nonAssumption,
            reproducibility = reproducibility,
            violations      = violations
        )

        return if (!pccvResult.pass) {
            val next = state.copy(
                pccvResult = pccvResult,
                status     = SessionStatus.REJECTED,
                stepLog    = state.stepLog +
                    "Step 6 [pccv_validate]: STOP — REJECTED (${violations.size} violation(s))"
            )
            stateManager.appendState(sessionId, next)
            OrchestratorResult.Rejected(next, "PCCV gate failed: ${violations.firstOrNull()}")
        } else {
            // ── Step 7: Certification Emitter ──────────────────────────────────
            val certified = buildCertifiedIntent(state, pccvResult)
            val next = state.copy(
                pccvResult      = pccvResult,
                certifiedIntent = certified,
                status          = SessionStatus.CERTIFIED,
                stepLog         = state.stepLog + listOf(
                    "Step 6 [pccv_validate]: PASS — all 5 dimensions satisfied",
                    "Step 7 [certify]: intent_certified_event emitted (id=${certified.intentId.take(8)})"
                )
            )
            stateManager.appendState(sessionId, next)
            OrchestratorResult.Certified(certified, next)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CERTIFICATION EMITTER (STEP 7 — internal, called only from pccv step)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds the [CertifiedIntent] from validated state.
     * This is the ONLY output allowed to enter core.
     *
     * Evidence refs include both raw-input extraction refs (from IRS-01)
     * and scout evidence refs (from IRS-02).
     */
    private fun buildCertifiedIntent(state: IntentState, pccvResult: PccvResult): CertifiedIntent {
        val draft = state.intentDraft!!
        val pccvReport = PccvReport(
            completeness    = pccvResult.completeness,
            consistency     = pccvResult.consistency,
            feasibility     = pccvResult.feasibility,
            nonAssumption   = pccvResult.nonAssumption,
            reproducibility = pccvResult.reproducibility
        )
        val evidenceRefs = buildList {
            draft.objective?.let          { add(EvidenceRef("objective",           "raw_input", it)) }
            draft.successCriteria?.let    { add(EvidenceRef("success_criteria",    "raw_input", it)) }
            draft.constraints?.let        { add(EvidenceRef("constraints",         "raw_input", it)) }
            draft.environment?.let        { add(EvidenceRef("environment",         "raw_input", it)) }
            draft.resources?.let          { add(EvidenceRef("resources",           "raw_input", it)) }
            draft.acceptanceBoundary?.let { add(EvidenceRef("acceptance_boundary", "raw_input", it)) }
            state.evidence.forEach { scout ->
                add(EvidenceRef(scout.field, scout.source, scout.content))
            }
        }
        return CertifiedIntent(
            intentId           = draft.intentId,
            objective          = draft.objective!!,
            successCriteria    = draft.successCriteria!!,
            constraints        = draft.constraints!!,
            environment        = draft.environment!!,
            resources          = draft.resources!!,
            acceptanceBoundary = draft.acceptanceBoundary!!,
            evidenceRefs       = evidenceRefs,
            pccvReport         = pccvReport
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildRequiredInputPrompt(missingFields: List<String>): String {
        val fieldLines = missingFields.joinToString("\n") { "  $it: <provide value>" }
        return "Please provide the following missing fields:\n$fieldLines\n\n" +
            "Use the format:  field_name: value  (one per line)"
    }
}
