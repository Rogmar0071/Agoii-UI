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

    private val contractorRegistry = ContractorRegistry()

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
        return try {
            processInteractionInternal(projectId, input)
        } catch (_: Exception) {
            "Execution failed"
        }
    }

    private fun processInteractionInternal(projectId: String, input: String): String {

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

        // ── Unified loop: Governor advances state; ExecutionAuthority executes on TASK_STARTED ──
        var cycles = 0
        var output: String? = null
        while (cycles < MAX_GOVERNOR_CYCLES) {
            val lastType = ledger.loadEvents(projectId).lastOrNull()?.type
            if (lastType == EventTypes.EXECUTION_COMPLETED) break
            if (lastType == EventTypes.TASK_STARTED) {
                // Mandatory trigger: IF last event == TASK_STARTED → ExecutionAuthority executes
                val execResult = executionAuthority.executeFromLedger(projectId, ledger)
                when (execResult) {
                    is ExecutionAuthorityExecutionResult.Executed -> {
                        if (execResult.executionStatus != ExecutionStatus.SUCCESS) {
                            throw LedgerValidationException(
                                "ICS BLOCKED: Execution failed — status=${execResult.executionStatus}"
                            )
                        }
                        output = execResult.report.artifactStructure["response"]?.toString()
                            ?: throw LedgerValidationException("ICS BLOCKED: Empty output in artifact")
                    }
                    is ExecutionAuthorityExecutionResult.BlockedWithRecovery ->
                        throw LedgerValidationException("ICS BLOCKED: ${execResult.reason}")
                    else ->
                        throw LedgerValidationException(
                            "ICS BLOCKED: Unexpected execution result: ${execResult::class.simpleName}"
                        )
                }
            } else {
                val govResult = governor.runGovernor(projectId)
                when (govResult) {
                    Governor.GovernorResult.COMPLETED -> break
                    Governor.GovernorResult.DRIFT ->
                        throw LedgerValidationException(
                            "ICS BLOCKED: Governor drift at state '$lastType' (cycle $cycles)"
                        )
                    else -> { /* ADVANCED or NO_EVENT — continue */ }
                }
            }
            cycles++
        }

        val finalState = ledger.loadEvents(projectId).lastOrNull()?.type
        return output ?: throw LedgerValidationException(
            "ICS BLOCKED: No output produced — final state='$finalState' cycles=$cycles"
        )
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

    companion object {
        private const val MAX_GOVERNOR_CYCLES = 30
    }
}
