package com.agoii.mobile.bridge

import android.content.Context
import com.agoii.mobile.commit.ApprovalStatus
import com.agoii.mobile.contractor.ContractorCandidate
import com.agoii.mobile.contractor.ContractorRegistry
import com.agoii.mobile.contractor.ContractorVerificationEngine
import com.agoii.mobile.core.*
import com.agoii.mobile.execution.BuildExecutor
import com.agoii.mobile.execution.ExecutionAuthority
import com.agoii.mobile.execution.ExecutionEntryPoint
import com.agoii.mobile.governor.Governor
import com.agoii.mobile.irs.*
import com.agoii.mobile.observability.ExecutionObservability
import com.agoii.mobile.observability.ExecutionTimeline
import com.agoii.mobile.observability.ExecutionTrace

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

    private val observability       = ExecutionObservability(ledger)

    /**
     * INTENT ENTRY POINT (CR-01-FINAL — UNCONDITIONAL)
     *
     * INTENT_SUBMITTED is ALWAYS written. IRS runs informational-only.
     * The IRS result is included as metadata in the payload; it NEVER blocks entry.
     *
     * After this call the project enters the intent evolution phase.
     * The caller may then invoke [updateIntent] (0..N times) before calling
     * [finalizeIntent] to commit the intent state and unblock execution.
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
        val sessionId = "$projectId-${java.util.UUID.randomUUID()}"

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
     * Evolve the intent by appending an INTENT_UPDATED event.
     *
     * Legal only before INTENT_FINALIZED (I2) or after RETURN_TO_INTENT_STATE (I6).
     * The transition table in [LedgerAudit] enforces this constraint at ledger level.
     *
     * @param projectId The project ledger to write to.
     * @param objective The updated objective value for this iteration.
     */
    fun updateIntent(projectId: String, objective: String) {
        ledger.appendEvent(
            projectId,
            EventTypes.INTENT_UPDATED,
            mapOf("objective" to objective)
        )
    }

    /**
     * Freeze the intent state by appending INTENT_FINALIZED.
     *
     * After this call:
     *  - Intent is immutable (I3).
     *  - [runGovernorStep] will start the contract generation pipeline (I4).
     *
     * @param projectId The project ledger to write to.
     */
    fun finalizeIntent(projectId: String) {
        ledger.appendEvent(
            projectId,
            EventTypes.INTENT_FINALIZED,
            mapOf("finalizedAt" to System.currentTimeMillis())
        )
    }

    /**
     * Emit EXECUTION_AUTHORIZED — the canonical user approval gate.
     *
     * Replaces [approveContracts] for new projects following the MQP lifecycle.
     * The Governor will advance from EXECUTION_AUTHORIZED to EXECUTION_IN_PROGRESS
     * on the next [runGovernorStep] call.
     *
     * @param projectId The project ledger to write to.
     */
    fun authorizeExecution(projectId: String) {
        ledger.appendEvent(
            projectId,
            EventTypes.EXECUTION_AUTHORIZED,
            mapOf("authorizedAt" to System.currentTimeMillis())
        )
    }

    /**
     * Interrupt a running execution by appending EXECUTION_ABORTED.
     *
     * After this call the caller must invoke [returnToIntentState] and then
     * [updateIntent] + [finalizeIntent] to re-enter the execution pipeline (I6).
     *
     * @param projectId The project ledger to write to.
     * @param reason    Human-readable description of why the execution was aborted.
     */
    fun abortExecution(projectId: String, reason: String) {
        ledger.appendEvent(
            projectId,
            EventTypes.EXECUTION_ABORTED,
            mapOf(
                "reason"    to reason,
                "abortedAt" to System.currentTimeMillis()
            )
        )
    }

    /**
     * Open the intent phase for re-entry after [abortExecution].
     *
     * Appends RETURN_TO_INTENT_STATE which enables [updateIntent] again (I6).
     *
     * @param projectId The project ledger to write to.
     */
    fun returnToIntentState(projectId: String) {
        ledger.appendEvent(
            projectId,
            EventTypes.RETURN_TO_INTENT_STATE,
            mapOf("returnedAt" to System.currentTimeMillis())
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

        // ── Intent phase → Contracts (ExecutionEntryPoint ONLY) ─────────────────
        // MQP path: INTENT_FINALIZED unblocks contract generation (I4 enforcement).
        // Backward-compat path: INTENT_SUBMITTED still valid for pre-MQP ledgers.
        val intentPayloadToProcess: Map<String, Any>? = when (lastEvent?.type) {
            EventTypes.INTENT_FINALIZED -> {
                // Resolve the effective objective from the ledger: latest INTENT_UPDATED
                // takes precedence; fall back to INTENT_SUBMITTED.
                val updatedObjective = events.lastOrNull { it.type == EventTypes.INTENT_UPDATED }
                    ?.payload?.get("objective")?.toString()
                val submittedObjective = events.firstOrNull { it.type == EventTypes.INTENT_SUBMITTED }
                    ?.payload?.get("objective")?.toString()
                val objective = updatedObjective ?: submittedObjective
                if (objective != null) mapOf("objective" to objective) else null
            }
            EventTypes.INTENT_SUBMITTED -> lastEvent.payload  // backward-compat
            else -> null
        }

        if (intentPayloadToProcess != null) {
            val result = executionEntryPoint.executeIntent(projectId, intentPayloadToProcess)

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
            // 1. Communication Contractor (LLM) — drives user interaction contracts
            Triple(
                "communication-contractor-001",
                "llm",
                mapOf(
                    "constraintObedience" to "high",
                    "structuralAccuracy"  to "high",
                    "driftScore"          to "low",
                    "complexityCapacity"  to "high",
                    "reliability"         to "high"
                )
            ),
            // 2. Knowledge Scout Contractor — scouting and discovery contracts
            Triple(
                "knowledge-scout-001",
                "scout",
                mapOf(
                    "constraintObedience" to "high",
                    "structuralAccuracy"  to "high",
                    "driftScore"          to "low",
                    "complexityCapacity"  to "high",
                    "reliability"         to "high"
                )
            ),
            // 3. Validation Contractor — validation contracts (AERP-1)
            Triple(
                "validation-contractor-001",
                "system",
                mapOf(
                    "constraintObedience" to "high",
                    "structuralAccuracy"  to "high",
                    "driftScore"          to "low",
                    "complexityCapacity"  to "high",
                    "reliability"         to "high"
                )
            ),
            // 4. Simulation Contractor — simulation and projection contracts
            Triple(
                "simulation-contractor-001",
                "simulation",
                mapOf(
                    "constraintObedience" to "high",
                    "structuralAccuracy"  to "high",
                    "driftScore"          to "low",
                    "complexityCapacity"  to "high",
                    "reliability"         to "high"
                )
            )
        )
    }
}
