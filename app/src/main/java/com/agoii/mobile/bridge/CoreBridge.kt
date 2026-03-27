package com.agoii.mobile.bridge

import android.content.Context
import com.agoii.mobile.contractor.ContractorRegistry
import com.agoii.mobile.core.*
import com.agoii.mobile.execution.BuildExecutor
import com.agoii.mobile.execution.ContractorExecutor
import com.agoii.mobile.execution.ContractorInvocationLayer
import com.agoii.mobile.execution.ExecutionAuthority
import com.agoii.mobile.execution.ExecutionEntryPoint
import com.agoii.mobile.execution.ResultValidator
import com.agoii.mobile.governor.Governor
import com.agoii.mobile.irs.*
import com.agoii.mobile.observability.ExecutionObservability
import com.agoii.mobile.observability.ExecutionTimeline
import com.agoii.mobile.observability.ExecutionTrace

/**
 * CoreBridge — mobile runtime adapter.
 *
 * GOVERNANCE RULE (LOCKED):
 * Intent MUST be certified by IRS BEFORE entering the ledger.
 *
 * Architecture:
 *   RAW INPUT → IRS (off-ledger) → CERTIFIED → INTENT_SUBMITTED → EXECUTION
 *
 * IRS is NOT part of the ledger lifecycle.
 * Ledger remains the execution authority only.
 */
class CoreBridge(context: Context) {

    private val eventStore          = EventStore(context)
    private val ledger              = EventLedger(eventStore)
    private val contractorRegistry  = ContractorRegistry()
    private val governor            = Governor(ledger, contractorRegistry)
    private val ledgerAudit         = LedgerAudit(ledger)
    private val replay              = Replay(ledger)
    private val replayTest          = ReplayTest(ledger)
    private val buildExecutor       = BuildExecutor()
    private val irsOrchestrator     = IrsOrchestrator()
    private val executionEntryPoint = ExecutionEntryPoint(ledger)
    
    // Execution Authority components (contract integration)
    private val contractorExecutor     = ContractorExecutor()
    private val resultValidator        = ResultValidator()
    private val executionAuthority     = ExecutionAuthority(contractorExecutor, resultValidator)
    private val contractorInvocation   = ContractorInvocationLayer(ledger, executionAuthority, contractorRegistry)

    private val observability       = ExecutionObservability(ledger)

    /**
     * 🔴 INTENT GATE
     *
     * Runs IRS lifecycle OFF-LEDGER.
     * Only writes INTENT_SUBMITTED if [OrchestratorResult.Certified].
     *
     * @return true if intent entered ledger, false if IRS blocked it
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

        // ❌ Block if not certified
        if (stepResult.orchestratorResult !is OrchestratorResult.Certified) {
            return false
        }

        // ✅ Minimal certification trace (non-invasive)
        ledger.appendEvent(
            projectId,
            EventTypes.INTENT_SUBMITTED,
            mapOf(
                "objective"       to objective,
                "certificationId" to sessionId,
                "certifiedAt"     to System.currentTimeMillis()
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

        // ── Contractor Invocation (TASK_ASSIGNED re-entry) ─────────────────────
        if (lastEvent?.type == EventTypes.TASK_ASSIGNED) {
            // Process through Execution Authority → Contractor Module
            contractorInvocation.processTaskAssignment(projectId, lastEvent)
            
            // Result event already appended by ContractorInvocationLayer
            // Continue Governor progression
            val result = governor.runGovernor(projectId)
            
            return if (result == Governor.GovernorResult.ADVANCED) {
                ledger.loadEvents(projectId).lastOrNull()
            } else {
                null
            }
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
            ?.firstOrNull { it["contractId"] == contractId }

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
