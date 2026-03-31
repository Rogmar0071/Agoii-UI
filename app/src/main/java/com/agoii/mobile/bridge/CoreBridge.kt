package com.agoii.mobile.bridge

import android.content.Context
import com.agoii.mobile.infrastructure.OpenAIClient
import com.agoii.mobile.contractor.*
import com.agoii.mobile.contracts.*
import com.agoii.mobile.core.*
import com.agoii.mobile.execution.*
import com.agoii.mobile.governor.Governor
import com.agoii.mobile.interaction.*
import com.agoii.mobile.irs.*
import com.agoii.mobile.observability.*

class CoreBridge(context: Context) {

    private val eventStore          = EventStore(context)
    private val ledger              = EventLedger(eventStore)
    private val governor            = Governor(ledger)
    private val ledgerAudit         = LedgerAudit(ledger)
    private val replay              = Replay(ledger)
    private val replayTest          = ReplayTest(ledger)
    private val buildExecutor       = BuildExecutor()
    private val irsOrchestrator     = IrsOrchestrator()

    // ─────────────────────────────────────────────────────────────
    // DRIVER REGISTRY (REAL EXECUTION LAYER)
    // ─────────────────────────────────────────────────────────────
    private val driverRegistry = DriverRegistry()

    private val contractorRegistry = buildContractorRegistry()

    init {
        driverRegistry.register(
            "llm",
            LLMContractor(OpenAIClient())
        )
    }

    // ─────────────────────────────────────────────────────────────
    // SYSTEM WIRING
    // ─────────────────────────────────────────────────────────────
    private val executionAuthority = ExecutionAuthority(contractorRegistry, driverRegistry)
    private val executionEntryPoint = ExecutionEntryPoint(ledger, executionAuthority)

    private val observability = ExecutionObservability(ledger)

    // ─────────────────────────────────────────────────────────────
    // ICS ENTRY
    // ─────────────────────────────────────────────────────────────
    fun processInteraction(projectId: String, input: String): String {

        contractorRegistry.allVerified()
            .firstOrNull { it.source == "llm" }
            ?: throw LedgerValidationException("ICS BLOCKED: No real communication contractor available")

        val events = ledger.loadEvents(projectId)

        if (events.isEmpty()) {
            ledger.appendEvent(
                projectId,
                EventTypes.INTENT_SUBMITTED,
                mapOf("objective" to input)
            )
        }

        // ── Step 1: Execute intent → generate contracts ────────────────────
        val authResult = executionEntryPoint.executeIntent(
            projectId,
            mapOf("objective" to input)
        )
        if (!authResult.authorized) {
            throw LedgerValidationException("ICS BLOCKED: ${authResult.reason}")
        }

        // ── Step 2: Governor loop until TASK_STARTED (max 30 cycles) ──────
        var cycles = 0
        while (cycles < MAX_GOVERNOR_CYCLES) {
            if (ledger.loadEvents(projectId).lastOrNull()?.type == EventTypes.TASK_STARTED) break
            val govResult = governor.runGovernor(projectId)
            if (govResult == Governor.GovernorResult.DRIFT) {
                throw LedgerValidationException("ICS BLOCKED: Governor drift before TASK_STARTED (cycle $cycles)")
            }
            cycles++
        }
        if (ledger.loadEvents(projectId).lastOrNull()?.type != EventTypes.TASK_STARTED) {
            throw LedgerValidationException(
                "ICS BLOCKED: TASK_STARTED not reached within $MAX_GOVERNOR_CYCLES cycles"
            )
        }

        // ── Step 3: Execute via ExecutionAuthority ─────────────────────────
        val execResult = executionAuthority.executeFromLedger(projectId, ledger)
        val output: String = when (execResult) {
            is ExecutionAuthorityExecutionResult.Executed -> {
                if (execResult.executionStatus != ExecutionStatus.SUCCESS) {
                    throw LedgerValidationException(
                        "ICS BLOCKED: Execution failed — status=${execResult.executionStatus}"
                    )
                }
                execResult.report.artifactStructure["response"]?.toString()
                    ?: throw LedgerValidationException("ICS BLOCKED: Empty output in artifact")
            }
            is ExecutionAuthorityExecutionResult.BlockedWithRecovery ->
                throw LedgerValidationException("ICS BLOCKED: ${execResult.reason}")
            else ->
                throw LedgerValidationException(
                    "ICS BLOCKED: Unexpected execution result: ${execResult::class.simpleName}"
                )
        }

        // ── Step 4: Governor loop until EXECUTION_COMPLETED (max 30 cycles) ─
        cycles = 0
        while (cycles < MAX_GOVERNOR_CYCLES) {
            val govResult = governor.runGovernor(projectId)
            when (govResult) {
                Governor.GovernorResult.COMPLETED -> break
                Governor.GovernorResult.DRIFT ->
                    throw LedgerValidationException("ICS BLOCKED: Governor drift after execution")
                else -> { /* ADVANCED or NO_EVENT — continue */ }
            }
            cycles++
        }

        return output
    }

    // ─────────────────────────────────────────────────────────────
    // UI + GOVERNANCE SURFACE
    // ─────────────────────────────────────────────────────────────
    fun loadEvents(projectId: String): List<Event> =
        ledger.loadEvents(projectId)

    fun replayState(projectId: String): ReplayStructuralState =
        replay.replayStructuralState(projectId)

    fun auditLedger(projectId: String): AuditResult =
        ledgerAudit.auditLedger(projectId)

    fun verifyReplay(projectId: String): ReplayVerification =
        replayTest.verifyReplay(projectId)

    fun approveContracts(projectId: String) {
        ledger.appendEvent(projectId, EventTypes.CONTRACTS_APPROVED, emptyMap())
    }

    fun signalCommitApproval(projectId: String) {
        executionAuthority.resolveCommitDecision(projectId, ledger, true)
    }

    fun signalCommitRejection(projectId: String) {
        executionAuthority.resolveCommitDecision(projectId, ledger, false)
    }

    // ─────────────────────────────────────────────────────────────
    // CONTRACTOR REGISTRY
    // ─────────────────────────────────────────────────────────────
    private fun buildContractorRegistry(): ContractorRegistry {
        val registry = ContractorRegistry()
        val engine = ContractorVerificationEngine()

        REQUIRED_CONTRACTORS.forEach { (id, source, claims) ->
            val candidate = ContractorCandidate(id, source, claims)
            val result = engine.verify(candidate)
            result.assignedProfile?.let { registry.registerVerified(it) }
        }

        return registry
    }

    companion object {
        private const val MAX_GOVERNOR_CYCLES = 30

        private val REQUIRED_CONTRACTORS = listOf(
            Triple(
                "communication-contractor-001",
                "llm",
                mapOf(
                    "constraintObedience" to "high",
                    "structuralAccuracy" to "high",
                    "driftScore" to "low",
                    "complexityCapacity" to "high",
                    "reliability" to "high"
                )
            )
        )
    }
}
