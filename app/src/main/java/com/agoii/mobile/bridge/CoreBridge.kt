package com.agoii.mobile.bridge

import android.content.Context
import com.agoii.mobile.commit.ApprovalStatus
import com.agoii.mobile.contractor.ContractorCandidate
import com.agoii.mobile.contractor.ContractorRegistry
import com.agoii.mobile.contractor.ContractorSystem
import com.agoii.mobile.contractor.ContractorSystemResult
import com.agoii.mobile.contractor.ContractorVerificationEngine
import com.agoii.mobile.contracts.ContractCapability
import com.agoii.mobile.contracts.ExecutionType
import com.agoii.mobile.contracts.TargetDomain
import com.agoii.mobile.core.*
import com.agoii.mobile.execution.BuildExecutor
import com.agoii.mobile.execution.ExecutionAuthority
import com.agoii.mobile.execution.ExecutionEntryPoint
import com.agoii.mobile.governor.Governor
import com.agoii.mobile.interaction.InteractionContract
import com.agoii.mobile.interaction.OutputType
import com.agoii.mobile.irs.*
import com.agoii.mobile.observability.ExecutionObservability
import com.agoii.mobile.observability.ExecutionTimeline
import com.agoii.mobile.observability.ExecutionTrace
import java.util.UUID

/**
 * CoreBridge — mobile runtime adapter.
 *
 * GOVERNANCE RULE (LOCKED — CR-01-FINAL):
 * INTENT_SUBMITTED is ALWAYS written on intent receipt.
 * IRS runs informational-only; its result NEVER blocks ledger entry.
 *
 * Architecture (MASTER-ALIGNED):
 *   RAW INPUT → INTENT_SUBMITTED (always) → ExecutionEntryPoint → ExecutionAuthority → CONTRACTS_GENERATED
 *
 * IRS is informational context included in the INTENT_SUBMITTED payload.
 * Ledger remains the sole execution authority.
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

    // FS-1: Build a verified ContractorRegistry so ExecutionAuthority can succeed
    private val contractorRegistry  = buildContractorRegistry()
    private val executionAuthority  = ExecutionAuthority(contractorRegistry)
    private val contractorSystem    = ContractorSystem()

    private val observability       = ExecutionObservability(ledger)

    /**
     * INTENT ENTRY POINT (CR-01-FINAL — UNCONDITIONAL)
     *
     * INTENT_SUBMITTED is ALWAYS written. IRS runs informational-only.
     * The IRS result is included as metadata in the payload; it NEVER blocks entry.
     *
     * @return true always — intent entry is unconditional per AGOII MASTER alignment.
     */
    fun submitIntent(
        projectId:         String,
        rawFields:         Map<String, String>,
        evidence:          Map<String, List<EvidenceRef>>,
        swarmConfig:       SwarmConfig,
        availableEvidence: Map<String, List<EvidenceRef>> = emptyMap(),
        objective:         String
    ): Boolean {
        val sessionId = "$projectId-${UUID.randomUUID()}"

        irsOrchestrator.createSession(
            sessionId,
            rawFields,
            evidence,
            swarmConfig,
            availableEvidence
        )

        var stepResult: StepResult
        do {
            stepResult = irsOrchestrator.step(sessionId)
        } while (!stepResult.terminal)

        // IRS result is informational context — it NEVER blocks ledger entry (CR-01-FINAL / FS-1).
        val irsStatus = when (stepResult.orchestratorResult) {
            is OrchestratorResult.Certified -> "CERTIFIED"
            else                            -> "PENDING"
        }

        // ✅ INTENT_SUBMITTED is ALWAYS written (no gate, no block)
        ledger.appendEvent(
            projectId,
            EventTypes.INTENT_SUBMITTED,
            mapOf(
                "objective"       to objective,
                "certificationId" to sessionId,
                "certifiedAt"     to System.currentTimeMillis(),
                "irsStatus"       to irsStatus
            )
        )

        return true
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

        if (result == Governor.GovernorResult.ADVANCED) {
            val latestAfterGovernor = ledger.loadEvents(projectId).lastOrNull()
            when (latestAfterGovernor?.type) {
                // After Governor writes TASK_STARTED, ExecutionAuthority owns the execution pipeline.
                EventTypes.TASK_STARTED -> {
                    executionAuthority.executeFromLedger(projectId, ledger)
                }
                // After Governor writes EXECUTION_COMPLETED, ExecutionAuthority owns the assembly
                // pipeline. If assembly completes, ICS runs immediately without an extra ledger load.
                EventTypes.EXECUTION_COMPLETED -> {
                    val assemblyResult = executionAuthority.assembleFromLedger(projectId, ledger)
                    if (assemblyResult is com.agoii.mobile.assembly.AssemblyExecutionResult.Assembled) {
                        val icsResult = executionAuthority.runIcsFromLedger(projectId, ledger)
                        // FS-3: After ICS_COMPLETED, emit COMMIT_CONTRACT (approval gate)
                        if (icsResult is com.agoii.mobile.ics.IcsExecutionResult.Processed) {
                            emitCommitContract(projectId, icsResult)
                        }
                    }
                }
            }
            return ledger.loadEvents(projectId).lastOrNull()
        }

        return null
    }

    /**
     * FS-3: Emit COMMIT_CONTRACT after ICS_COMPLETED.
     *
     * Derives proposed actions deterministically from the ICS output entries.
     * NO real-world execution occurs here — user approval is required.
     */
    private fun emitCommitContract(
        projectId: String,
        icsResult: com.agoii.mobile.ics.IcsExecutionResult.Processed
    ) {
        val icsOutput = icsResult.icsOutput
        val proposedActions = icsOutput.entries.map { entry ->
            "${entry.contractId}:${entry.artifactReference}"
        }

        // Read contractSetId and finalArtifactReference from the ledger (ASSEMBLY_COMPLETED)
        val events = ledger.loadEvents(projectId)
        val assemblyCompleted = events.lastOrNull { it.type == EventTypes.ASSEMBLY_COMPLETED }
        val contractSetId          = assemblyCompleted?.payload?.get("contractSetId")?.toString() ?: ""
        val finalArtifactReference = assemblyCompleted?.payload?.get("finalArtifactReference")?.toString() ?: ""

        ledger.appendEvent(
            projectId,
            EventTypes.COMMIT_CONTRACT,
            mapOf(
                "report_reference"       to icsOutput.reportReference,
                "contractSetId"          to contractSetId,
                "finalArtifactReference" to finalArtifactReference,
                "proposedActions"        to proposedActions,
                "approvalStatus"         to ApprovalStatus.PENDING.name
            )
        )
    }

    private fun resolveContractName(events: List<Event>, contractId: String): String {
        val contractsEvent = events.firstOrNull {
            it.type == EventTypes.CONTRACTS_GENERATED
        }

        val contracts =
            contractsEvent?.payload?.get("contracts") as? List<*>

        val match = contracts
            ?.filterIsInstance<Map<*, *>>()
            ?.firstOrNull { it["contractId"] == contractId }

        return match?.get("name")?.toString() ?: contractId
    }

    /**
     * ICS Interaction entry point (AGOII-RCF-ICS-ENFORCEMENT-LOCK-01).
     *
     * Routes every user interaction through the ContractorSystem for deterministic,
     * contract-driven execution. Never calls Governor or triggers execution.
     *
     * Lifecycle:
     *  - Empty ledger        → IRS certification + INTENT_SUBMITTED, then ContractorSystem.
     *  - Non-empty ledger    → Build [InteractionContract], route through [ContractorSystem].
     *                          Resolved → CONTRACTS_GENERATED (type="interaction") + return response.
     *                          Blocked  → BLOCK (LedgerValidationException).
     *
     * BLOCK CONDITIONS (throws [LedgerValidationException]):
     *  - No COMMUNICATION contractor in registry.
     *  - ContractorSystem matching or execution fails.
     *  - Contractor output is null, empty, blank, or non-human-readable.
     *
     * @throws LedgerValidationException on every block condition.
     * @return Validated communication text from the selected contractor.
     */
    fun processInteraction(projectId: String, input: String): String {

        // ── Enforce COMMUNICATION contractor exists BEFORE any execution ───────
        contractorRegistry.findBestMatch(
            mapOf(ContractCapability.COMMUNICATION.dimensionName to ContractCapability.COMMUNICATION.requiredLevel)
        ) ?: throw LedgerValidationException("ICS BLOCKED: No communication contractor available")

        val events = ledger.loadEvents(projectId)

        if (events.isEmpty()) {
            // Initial intent capture: IRS certification + INTENT_SUBMITTED.
            val sessionId = "$projectId-${UUID.randomUUID()}"

            irsOrchestrator.createSession(
                sessionId,
                mapOf(
                    "objective"   to input,
                    "constraints" to "",
                    "environment" to "",
                    "resources"   to ""
                ),
                mapOf(
                    "objective"   to listOf(EvidenceRef(id = "ev-obj", source = "user-input")),
                    "constraints" to listOf(EvidenceRef(id = "ev-cst", source = "user-input")),
                    "environment" to listOf(EvidenceRef(id = "ev-env", source = "user-input")),
                    "resources"   to listOf(EvidenceRef(id = "ev-res", source = "user-input"))
                ),
                SwarmConfig(agentCount = 2, consensusRule = ConsensusRule.MAJORITY),
                emptyMap()
            )

            var stepResult: StepResult
            do {
                stepResult = irsOrchestrator.step(sessionId)
            } while (!stepResult.terminal)

            val irsStatus = when (stepResult.orchestratorResult) {
                is OrchestratorResult.Certified -> "CERTIFIED"
                else                            -> "PENDING"
            }

            ledger.appendEvent(
                projectId,
                EventTypes.INTENT_SUBMITTED,
                mapOf(
                    "objective"       to input,
                    "certificationId" to sessionId,
                    "certifiedAt"     to System.currentTimeMillis(),
                    "irsStatus"       to irsStatus
                )
            )
        }

        // ── Route through ContractorSystem (COMMUNICATION only) ───────────────
        val icsTaskId = "$projectId-ics-${UUID.randomUUID()}"
        val interactionContract = InteractionContract(
            contractId = icsTaskId,
            query      = input,
            outputType = OutputType.DETAILED
        )

        val systemResult = contractorSystem.execute(
            taskId               = icsTaskId,
            contractId           = interactionContract.contractId,
            reportReference      = icsTaskId,
            position             = 1,
            constraints          = emptyList(),
            expectedOutput       = "Clarify and qualify the intent for project '$projectId'",
            taskPayload          = mapOf("userInput" to input),
            requiredCapabilities = listOf(ContractCapability.COMMUNICATION),
            executionType        = ExecutionType.COMMUNICATION,
            targetDomain         = TargetDomain.CONTRACTOR,
            registry             = contractorRegistry
        )

        // ── Extract and validate output ───────────────────────────────────────
        val communicationText: String = when (systemResult) {
            is ContractorSystemResult.Blocked  ->
                throw LedgerValidationException(
                    "ICS BLOCKED: Contractor execution failed — ${systemResult.reason}"
                )
            is ContractorSystemResult.Resolved -> {
                val communicationPayload =
                    systemResult.executionOutput.resultArtifact["communicationPayload"] as? Map<*, *>
                communicationPayload?.get("outputSchema")?.toString()?.takeIf { it.isNotBlank() }
                    ?: throw LedgerValidationException("ICS BLOCKED: Invalid communication output")
            }
        }

        // ── Ledger write: CONTRACTS_GENERATED (type="interaction") ────────────
        val currentEvents = ledger.loadEvents(projectId)
        val intentId = currentEvents.firstOrNull { it.type == EventTypes.INTENT_SUBMITTED }
            ?.payload?.get("objective")?.toString() ?: projectId

        ledger.appendEvent(
            projectId,
            EventTypes.CONTRACTS_GENERATED,
            mapOf(
                "intentId"         to intentId,
                "contractSetId"    to icsTaskId,
                "total"            to 1,
                "contracts"        to listOf(
                    mapOf(
                        "contractId" to icsTaskId,
                        "position"   to 1
                    )
                ),
                "type"             to "interaction",
                "report_reference" to icsTaskId
            )
        )

        return communicationText
    }

    /** Append a contracts_approved event directly to the ledger (explicit governance gate). */
    fun approveContracts(projectId: String) {
        ledger.appendEvent(projectId, EventTypes.CONTRACTS_APPROVED, emptyMap())
    }

    /**
     * Signal user approval of the pending COMMIT_CONTRACT.
     *
     * GOVERNANCE RULE (V1/V4): CoreBridge is a signal router only.
     * ExecutionAuthority is the sole writer of COMMIT_EXECUTED.
     */
    fun signalCommitApproval(projectId: String) {
        executionAuthority.resolveCommitDecision(projectId, ledger, approved = true)
    }

    /**
     * Signal user rejection of the pending COMMIT_CONTRACT.
     *
     * GOVERNANCE RULE (V1/V4): CoreBridge is a signal router only.
     * ExecutionAuthority is the sole writer of COMMIT_ABORTED.
     */
    fun signalCommitRejection(projectId: String) {
        executionAuthority.resolveCommitDecision(projectId, ledger, approved = false)
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

    // ─── Private helpers ──────────────────────────────────────────────────────

    /**
     * Build a verified ContractorRegistry with all required system contractors (CR-01-FINAL / FIX 5).
     *
     * Required contractors (MASTER-ALIGNED):
     *  1. Communication Contractor (LLM)
     *  2. Knowledge Scout Contractor
     *  3. Validation Contractor
     *  4. Simulation Contractor
     *
     * All contractors use all-high capability claims which score 3 on every dimension,
     * well above [ContractorVerificationEngine.MINIMUM_SCORE] = 1. Verification failure
     * is not expected; if it occurs for a candidate, that contractor is skipped and the
     * remaining ones are still registered.
     *
     * BLOCK condition: If the registry remains empty after all registration attempts,
     * execution will be blocked with NO_CONTRACTOR_REGISTRY by
     * [ExecutionAuthority.executeFromLedger].
     *
     * DETERMINISM GUARANTEE: [ContractorVerificationEngine.verify] is a pure function.
     * Same input → same output on every run; no environment dependency.
     */
    private fun buildContractorRegistry(): ContractorRegistry {
        val registry  = ContractorRegistry()
        val engine    = ContractorVerificationEngine()

        REQUIRED_CONTRACTORS.forEach { (id, source, claims) ->
            val candidate = ContractorCandidate(
                id               = id,
                source           = source,
                capabilityClaims = claims
            )
            val result = engine.verify(candidate)
            result.assignedProfile?.let { registry.registerVerified(it) }
        }

        return registry
    }

    companion object {
        /**
         * Required contractors per CR-01-FINAL / FIX 5.
         * Each triple: (contractorId, source, capabilityClaims).
         * All capability dimensions use "high"/"low" as required by [ContractorVerificationEngine].
         */
        private val REQUIRED_CONTRACTORS: List<Triple<String, String, Map<String, String>>> = listOf(
            // 1. Communication Contractor (LLM) — drives user interaction contracts.
            //    communication = "high": the sole COMMUNICATION-capable contractor.
            Triple(
                "communication-contractor-001",
                "llm",
                mapOf(
                    "constraintObedience" to "high",
                    "structuralAccuracy"  to "high",
                    "driftScore"          to "low",
                    "complexityCapacity"  to "high",
                    "reliability"         to "high",
                    "communication"       to "high"
                )
            ),
            // 2. Knowledge Scout Contractor — scouting and discovery contracts.
            //    communication = "none": does not handle ICS interaction.
            Triple(
                "knowledge-scout-001",
                "scout",
                mapOf(
                    "constraintObedience" to "high",
                    "structuralAccuracy"  to "high",
                    "driftScore"          to "low",
                    "complexityCapacity"  to "high",
                    "reliability"         to "high",
                    "communication"       to "none"
                )
            ),
            // 3. Validation Contractor — validation contracts (AERP-1).
            //    communication = "none": does not handle ICS interaction.
            Triple(
                "validation-contractor-001",
                "system",
                mapOf(
                    "constraintObedience" to "high",
                    "structuralAccuracy"  to "high",
                    "driftScore"          to "low",
                    "complexityCapacity"  to "high",
                    "reliability"         to "high",
                    "communication"       to "none"
                )
            ),
            // 4. Simulation Contractor — simulation and projection contracts.
            //    communication = "none": does not handle ICS interaction.
            Triple(
                "simulation-contractor-001",
                "simulation",
                mapOf(
                    "constraintObedience" to "high",
                    "structuralAccuracy"  to "high",
                    "driftScore"          to "low",
                    "complexityCapacity"  to "high",
                    "reliability"         to "high",
                    "communication"       to "none"
                )
            )
        )
    }
}
