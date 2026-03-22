package com.agoii.mobile.irs

import com.agoii.mobile.irs.reality.RealityValidator
import com.agoii.mobile.irs.scout.ConstraintScout
import com.agoii.mobile.irs.scout.DependencyScout
import com.agoii.mobile.irs.scout.EnvironmentScout

/**
 * IrsOrchestrator — the sole coordinator of the IRS execution graph.
 *
 * Execution graph (one [step] call per arrow):
 *   step 1 → ReconstructionEngine
 *   step 2 → GapDetector
 *              → if gaps  → HALT (NeedsClarification)
 *   step 3 → ScoutOrchestrator + KnowledgeScouts (EnvironmentScout, DependencyScout, ConstraintScout)
 *   step 4 → EvidenceValidator
 *              → if invalid → HALT (Rejected / EVIDENCE_INVALID)
 *   step 5 → RealityValidator (RealityKnowledgeGateway + EvidenceScoringEngine + ContradictionEngine + RealitySimulationEngine)
 *              → if unverifiable → HALT (Rejected / REALITY_UNVERIFIABLE)
 *   step 6 → SwarmValidator
 *              → if inconsistent → HALT (Rejected / UNSTABLE)
 *   step 7 → SimulationEngine
 *              → if infeasible   → HALT (Rejected / INFEASIBLE)
 *   step 8 → PCCVValidator
 *              → if fail         → HALT (Rejected / PCCV_FAIL)
 *   step 9 → CertificationEmitter → terminal (Certified)
 *
 * Rules:
 *  - [step] executes EXACTLY one stage per call; no internal loops.
 *  - External driver controls iteration.
 *  - Only this orchestrator calls individual IRS modules.
 *  - State is managed by [IntentStateManager]; history is append-only.
 *  - IRS output is a UI-agnostic [OrchestratorResult]; the UI interprets it independently.
 */
class IrsOrchestrator(
    private val reconstructionEngine: ReconstructionEngine  = ReconstructionEngine(),
    private val gapDetector:          GapDetector           = GapDetector(),
    private val scoutOrchestrator:    ScoutOrchestrator     = ScoutOrchestrator(),
    private val environmentScout:     EnvironmentScout      = EnvironmentScout(),
    private val dependencyScout:      DependencyScout       = DependencyScout(),
    private val constraintScout:      ConstraintScout       = ConstraintScout(),
    private val evidenceValidator:    EvidenceValidator     = EvidenceValidator(),
    private val realityValidator:     RealityValidator      = RealityValidator(),
    private val swarmValidator:       SwarmValidator        = SwarmValidator(),
    private val simulationEngine:     SimulationEngine      = SimulationEngine(),
    private val pcCVValidator:        PCCVValidator         = PCCVValidator(),
    private val contractScopeLaw:     ContractScopeLaw      = ContractScopeLaw()
) {
    private val stateManager = IntentStateManager()

    // ─── Session management ───────────────────────────────────────────────────

    /**
     * Create a new IRS session from raw intent fields, evidence, and swarm config.
     *
     * @param sessionId         Unique session identifier.
     * @param rawFields         Raw field values keyed by field name.
     * @param evidence          Evidence refs keyed by field name (pre-scouting).
     * @param swarmConfig       Swarm parameters; [SwarmConfig.agentCount] must be ≥ 2.
     * @param availableEvidence Supplementary evidence pool passed to the ScoutOrchestrator.
     * @param contractScopeInput CSL-1 scope definition; defaults to minimal-scope (always VALID).
     */
    fun createSession(
        sessionId:          String,
        rawFields:          Map<String, String>,
        evidence:           Map<String, List<EvidenceRef>>,
        swarmConfig:        SwarmConfig,
        availableEvidence:  Map<String, List<EvidenceRef>> = emptyMap(),
        contractScopeInput: ContractScopeInput = ContractScopeInput.default()
    ): IrsSession {
        // Reconstruct the intent immediately so the session starts with a valid IntentData.
        val intentData = reconstructionEngine.reconstruct(rawFields, evidence)
        return stateManager.init(sessionId, intentData, swarmConfig, availableEvidence, contractScopeInput)
    }

    /**
     * Execute EXACTLY one stage for the identified session and return the result.
     *
     * The next stage is determined from the session's [IrsSession.history]:
     *  - Empty history           → RECONSTRUCTION (step 1, already done at creation; advance to GAP_DETECTION)
     *  - Last non-terminal stage → next stage in the pipeline
     *  - Terminal snapshot       → returns the same terminal result without advancing
     *
     * @throws IllegalArgumentException if no session with [sessionId] exists.
     */
    fun step(sessionId: String): StepResult {
        val session = stateManager.getSession(sessionId)
            ?: throw IllegalArgumentException("Session '$sessionId' not found")

        // If already at terminal state, return it without advancing.
        val lastSnapshot = session.history.lastOrNull()
        if (lastSnapshot?.orchestratorResult != null) {
            return StepResult(
                executedStage      = lastSnapshot.stage,
                session            = session,
                terminal           = true,
                orchestratorResult = lastSnapshot.orchestratorResult
            )
        }

        val nextStage = nextStage(session)
        return executeStage(sessionId, session, nextStage)
    }

    /**
     * Replay the full snapshot history for [sessionId].
     * Never null; returns empty list when session does not exist.
     */
    fun replayHistory(sessionId: String): List<IrsSnapshot> =
        stateManager.replayHistory(sessionId)

    /**
     * Return the [RealitySimulationResult] stored during the REALITY_VALIDATION stage,
     * or null when that stage has not yet executed (or the session does not exist).
     *
     * Provides direct traceability access so callers can compare simulation evaluations
     * across replay runs without navigating the full validation result chain.
     */
    fun realitySimulationResult(sessionId: String): RealitySimulationResult? =
        stateManager.realitySimulationResult(sessionId)

    // ─── Stage dispatch ───────────────────────────────────────────────────────

    private fun nextStage(session: IrsSession): IrsStage {
        val lastStage = session.history.lastOrNull()?.stage ?: return IrsStage.GAP_DETECTION
        return when (lastStage) {
            IrsStage.RECONSTRUCTION      -> IrsStage.GAP_DETECTION
            IrsStage.GAP_DETECTION       -> IrsStage.SCOUTING
            IrsStage.SCOUTING            -> IrsStage.EVIDENCE_VALIDATION
            IrsStage.EVIDENCE_VALIDATION -> IrsStage.REALITY_VALIDATION
            IrsStage.REALITY_VALIDATION  -> IrsStage.SWARM_VALIDATION
            IrsStage.SWARM_VALIDATION    -> IrsStage.SIMULATION
            IrsStage.SIMULATION          -> IrsStage.PCCV
            IrsStage.PCCV                -> IrsStage.CERTIFICATION
            IrsStage.CERTIFICATION       -> IrsStage.CERTIFICATION  // already terminal
        }
    }

    private fun executeStage(
        sessionId: String,
        session:   IrsSession,
        stage:     IrsStage
    ): StepResult {
        return when (stage) {

            // ── Step 2: Gap detection ────────────────────────────────────────
            IrsStage.GAP_DETECTION -> {
                val intent = stateManager.currentIntent(sessionId)!!
                val result = gapDetector.detect(intent)
                if (result.hasGaps) {
                    val terminal = OrchestratorResult.NeedsClarification(result.gaps)
                    terminal(sessionId, stage, terminal)
                } else {
                    advance(sessionId, stage)
                }
            }

            // ── Step 3: Scouting — gap filling + knowledge scouts ─────────────
            IrsStage.SCOUTING -> {
                val intent = stateManager.currentIntent(sessionId)!!
                // Gap filling: enrich intent from the supplementary evidence pool.
                val gaps = gapDetector.detect(intent).gaps
                val scoutResult = scoutOrchestrator.scout(
                    intentData        = intent,
                    gaps              = gaps,
                    availableEvidence = stateManager.availableEvidence(sessionId)
                )
                // Knowledge scouts: run all three independently on the (possibly enriched) intent.
                val enrichedIntent = scoutResult.updatedIntent
                val report = KnowledgeScoutReport(
                    environment = environmentScout.scout(enrichedIntent),
                    dependency  = dependencyScout.scout(enrichedIntent),
                    constraint  = constraintScout.scout(enrichedIntent)
                )
                val updatedSession = stateManager.append(
                    sessionId   = sessionId,
                    snapshot    = IrsSnapshot(stage = stage, orchestratorResult = null),
                    updatedIntent = enrichedIntent,
                    scoutReport = report
                )
                StepResult(
                    executedStage      = stage,
                    session            = updatedSession,
                    terminal           = false,
                    orchestratorResult = null
                )
            }

            // ── Step 4: Evidence validation ───────────────────────────────────
            IrsStage.EVIDENCE_VALIDATION -> {
                val intent = stateManager.currentIntent(sessionId)!!
                val evResult = evidenceValidator.validate(intent)
                if (!evResult.valid) {
                    val updatedSession = stateManager.append(
                        sessionId                = sessionId,
                        snapshot                 = IrsSnapshot(
                            stage = stage,
                            orchestratorResult = OrchestratorResult.Rejected(
                                reason  = FailureType.EVIDENCE_INVALID.name,
                                details = evResult.reasons
                            )
                        ),
                        evidenceValidationResult = evResult
                    )
                    StepResult(
                        executedStage      = stage,
                        session            = updatedSession,
                        terminal           = true,
                        orchestratorResult = OrchestratorResult.Rejected(
                            reason  = FailureType.EVIDENCE_INVALID.name,
                            details = evResult.reasons
                        )
                    )
                } else {
                    val updatedSession = stateManager.append(
                        sessionId                = sessionId,
                        snapshot                 = IrsSnapshot(stage = stage, orchestratorResult = null),
                        evidenceValidationResult = evResult
                    )
                    StepResult(
                        executedStage      = stage,
                        session            = updatedSession,
                        terminal           = false,
                        orchestratorResult = null
                    )
                }
            }

            // ── Step 5: Reality validation ────────────────────────────────────
            IrsStage.REALITY_VALIDATION -> {
                val intent    = stateManager.currentIntent(sessionId)!!
                val rvResult  = realityValidator.validate(intent)
                if (!rvResult.valid) {
                    val updatedSession = stateManager.append(
                        sessionId               = sessionId,
                        snapshot                = IrsSnapshot(
                            stage = stage,
                            orchestratorResult = OrchestratorResult.Rejected(
                                reason  = FailureType.REALITY_UNVERIFIABLE.name,
                                details = rvResult.reasons
                            )
                        ),
                        realityValidationResult = rvResult
                    )
                    StepResult(
                        executedStage      = stage,
                        session            = updatedSession,
                        terminal           = true,
                        orchestratorResult = OrchestratorResult.Rejected(
                            reason  = FailureType.REALITY_UNVERIFIABLE.name,
                            details = rvResult.reasons
                        )
                    )
                } else {
                    val updatedSession = stateManager.append(
                        sessionId               = sessionId,
                        snapshot                = IrsSnapshot(stage = stage, orchestratorResult = null),
                        realityValidationResult = rvResult
                    )
                    StepResult(
                        executedStage      = stage,
                        session            = updatedSession,
                        terminal           = false,
                        orchestratorResult = null
                    )
                }
            }

            // ── Step 6: Swarm validation ──────────────────────────────────────
            IrsStage.SWARM_VALIDATION -> {
                val intent      = stateManager.currentIntent(sessionId)!!
                val swarmResult = swarmValidator.validate(intent, session.swarmConfig)
                if (!swarmResult.consistent) {
                    val updatedSession = stateManager.append(
                        sessionId   = sessionId,
                        snapshot    = IrsSnapshot(
                            stage = stage,
                            orchestratorResult = OrchestratorResult.Rejected(
                                reason  = FailureType.UNSTABLE.name,
                                details = swarmResult.conflicts
                            )
                        ),
                        swarmResult = swarmResult
                    )
                    StepResult(
                        executedStage      = stage,
                        session            = updatedSession,
                        terminal           = true,
                        orchestratorResult = OrchestratorResult.Rejected(
                            reason  = FailureType.UNSTABLE.name,
                            details = swarmResult.conflicts
                        )
                    )
                } else {
                    val updatedSession = stateManager.append(
                        sessionId   = sessionId,
                        snapshot    = IrsSnapshot(stage = stage, orchestratorResult = null),
                        swarmResult = swarmResult
                    )
                    StepResult(
                        executedStage      = stage,
                        session            = updatedSession,
                        terminal           = false,
                        orchestratorResult = null
                    )
                }
            }

            // ── Step 7: Simulation ────────────────────────────────────────────
            IrsStage.SIMULATION -> {
                val intent    = stateManager.currentIntent(sessionId)!!
                val simResult = simulationEngine.simulate(intent)
                if (!simResult.feasible) {
                    val updatedSession = stateManager.append(
                        sessionId = sessionId,
                        snapshot  = IrsSnapshot(
                            stage = stage,
                            orchestratorResult = OrchestratorResult.Rejected(
                                reason  = FailureType.INFEASIBLE.name,
                                details = simResult.failurePoints
                            )
                        ),
                        simResult = simResult
                    )
                    StepResult(
                        executedStage      = stage,
                        session            = updatedSession,
                        terminal           = true,
                        orchestratorResult = OrchestratorResult.Rejected(
                            reason  = FailureType.INFEASIBLE.name,
                            details = simResult.failurePoints
                        )
                    )
                } else {
                    val updatedSession = stateManager.append(
                        sessionId = sessionId,
                        snapshot  = IrsSnapshot(stage = stage, orchestratorResult = null),
                        simResult = simResult
                    )
                    StepResult(
                        executedStage      = stage,
                        session            = updatedSession,
                        terminal           = false,
                        orchestratorResult = null
                    )
                }
            }

            // ── Step 8: PCCV ──────────────────────────────────────────────────
            IrsStage.PCCV -> {
                val intent       = stateManager.currentIntent(sessionId)!!
                val swarmResult  = stateManager.swarmResult(sessionId)
                    ?: SwarmResult(consistent = false, conflicts = listOf("no swarm result"), agentOutputs = emptyList())
                val simResult    = stateManager.simResult(sessionId)
                    ?: SimulationResult(feasible = false, failurePoints = listOf("no simulation result"))
                val pcCVResult   = pcCVValidator.validate(intent, swarmResult, simResult)
                if (!pcCVResult.passed) {
                    val updatedSession = stateManager.append(
                        sessionId = sessionId,
                        snapshot  = IrsSnapshot(
                            stage = stage,
                            orchestratorResult = OrchestratorResult.Rejected(
                                reason  = FailureType.PCCV_FAIL.name,
                                details = pcCVResult.errors
                            )
                        )
                    )
                    StepResult(
                        executedStage      = stage,
                        session            = updatedSession,
                        terminal           = true,
                        orchestratorResult = OrchestratorResult.Rejected(
                            reason  = FailureType.PCCV_FAIL.name,
                            details = pcCVResult.errors
                        )
                    )
                } else {
                    advance(sessionId, stage)
                }
            }

            // ── Step 9: Certification ─────────────────────────────────────────
            IrsStage.CERTIFICATION -> {
                val cslInput  = stateManager.contractScopeInput(sessionId) ?: ContractScopeInput.default()
                val cslResult = contractScopeLaw.evaluate(cslInput)
                if (cslResult.status == "INVALID") {
                    // CSL-1 §7: block issuance when EL > VC; require decomposition.
                    terminal(
                        sessionId, stage,
                        OrchestratorResult.Rejected(
                            reason  = FailureType.CSL_SCOPE_EXCEEDED.name,
                            details = listOf(
                                "EL=${cslResult.executionLoad} > VC=${cslResult.validationCapacity}:" +
                                " contract must be decomposed before issuance"
                            )
                        )
                    )
                } else {
                    terminal(sessionId, stage, OrchestratorResult.Certified(cslResult))
                }
            }

            // ── RECONSTRUCTION is handled at session creation; treat as GAP_DETECTION entry
            IrsStage.RECONSTRUCTION -> executeStage(sessionId, session, IrsStage.GAP_DETECTION)
        }
    }
    // ─── Helpers ──────────────────────────────────────────────────────────────

    /** Append a non-terminal snapshot and return the StepResult. */
    private fun advance(sessionId: String, stage: IrsStage): StepResult {
        val updatedSession = stateManager.append(
            sessionId = sessionId,
            snapshot  = IrsSnapshot(stage = stage, orchestratorResult = null)
        )
        return StepResult(
            executedStage      = stage,
            session            = updatedSession,
            terminal           = false,
            orchestratorResult = null
        )
    }

    /** Append a terminal snapshot and return the StepResult. */
    private fun terminal(
        sessionId: String,
        stage:     IrsStage,
        result:    OrchestratorResult
    ): StepResult {
        val updatedSession = stateManager.append(
            sessionId = sessionId,
            snapshot  = IrsSnapshot(stage = stage, orchestratorResult = result)
        )
        return StepResult(
            executedStage      = stage,
            session            = updatedSession,
            terminal           = true,
            orchestratorResult = result
        )
    }
}
