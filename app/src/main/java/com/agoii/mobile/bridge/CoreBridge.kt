package com.agoii.mobile.bridge

import android.content.Context
import com.agoii.mobile.infrastructure.OpenAIClient
import com.agoii.mobile.contractor.*
import com.agoii.mobile.contracts.*
import com.agoii.mobile.core.*
import com.agoii.mobile.execution.*
import com.agoii.mobile.governor.Governor
import com.agoii.mobile.interaction.*
import com.agoii.mobile.observability.*

class CoreBridge(context: Context) {

    private val eventStore      = EventStore(context)
    private val ledger          = EventLedger(eventStore)
    private val governor        = Governor(ledger)
    private val ledgerAudit     = LedgerAudit(ledger)
    private val replay          = Replay(ledger)
    private val replayTest      = ReplayTest(ledger)

    private val driverRegistry      = DriverRegistry()
    private val contractorRegistry  = ContractorRegistry()

    init {
        driverRegistry.register(
            "llm",
            LLMContractor(OpenAIClient())
        )
    }

    private val executionAuthority = ExecutionAuthority(contractorRegistry, driverRegistry)
    private val executionEntryPoint = ExecutionEntryPoint(ledger, executionAuthority)
    private val observability = ExecutionObservability(ledger)

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

        val authResult = executionEntryPoint.executeIntent(
            projectId,
            mapOf("objective" to input)
        )

        if (!authResult.authorized) {
            throw LedgerValidationException("ICS BLOCKED: ${authResult.reason}")
        }

        var cycles = 0
        var output: String? = null

        while (cycles < MAX_GOVERNOR_CYCLES) {

            val lastEvent = ledger.loadEvents(projectId).lastOrNull()
            val lastType  = lastEvent?.type

            when {

                lastType == EventTypes.ICS_COMPLETED -> break

                lastType == EventTypes.TASK_STARTED -> {

                    val execResult = executionAuthority.executeFromLedger(projectId, ledger)

                    when (execResult) {

                        is ExecutionAuthorityExecutionResult.Executed -> {
                            if (execResult.executionStatus != ExecutionStatus.SUCCESS) {
                                throw LedgerValidationException(
                                    "ICS BLOCKED: Execution failed — status=${execResult.executionStatus}"
                                )
                            }

                            // TEMP: Until report wiring is restored
                            output = "SYSTEM_READY"
                        }

                        is ExecutionAuthorityExecutionResult.Blocked -> {
                            throw LedgerValidationException("ICS BLOCKED: ${execResult.reason}")
                        }

                        else -> {
                            throw LedgerValidationException(
                                "ICS BLOCKED: Unexpected execution result: ${execResult::class.simpleName}"
                            )
                        }
                    }
                }

                lastType == EventTypes.TASK_EXECUTED &&
                lastEvent?.payload?.get("executionStatus")?.toString() == "FAILURE" -> {

                    executionAuthority.executeFromLedger(projectId, ledger)
                }

                else -> {
                    val govResult = governor.runGovernor(projectId)

                    when (govResult) {
                        Governor.GovernorResult.COMPLETED -> break
                        Governor.GovernorResult.DRIFT ->
                            throw LedgerValidationException(
                                "ICS BLOCKED: Governor drift at state '$lastType' (cycle $cycles)"
                            )
                        else -> { }
                    }
                }
            }

            cycles++
        }

        val finalState = ledger.loadEvents(projectId).lastOrNull()?.type

        return output ?: throw LedgerValidationException(
            "ICS BLOCKED: No output produced — final state='$finalState' cycles=$cycles"
        )
    }

    fun loadEvents(projectId: String): List<Event> =
        ledger.loadEvents(projectId)

    fun replayState(projectId: String): ReplayStructuralState =
        replay.replayStructuralState(projectId)

    fun auditLedger(projectId: String): AuditResult =
        ledgerAudit.auditLedger(projectId)

    fun verifyReplay(projectId: String): ReplayVerification =
        replayTest.verifyReplay(projectId)

    fun ingestContract(
        projectId: String,
        contract: UniversalContract
    ): UniversalIngestionResult {

        val result = executionAuthority.ingestUniversalContract(contract, projectId, ledger)

        if (result is UniversalIngestionResult.ValidationFailed ||
            result is UniversalIngestionResult.EnforcementFailed) {

            executionAuthority.executeFromLedger(projectId, ledger)
        }

        return result
    }

    fun approveContracts(projectId: String) {
        ledger.appendEvent(projectId, EventTypes.CONTRACTS_APPROVED, emptyMap())
    }

    // ✅ RESTORED UI INTERFACE (STRUCTURAL ONLY — NO LOGIC LEAK)
    fun signalCommitApproval(projectId: String) {
        ledger.appendEvent(
            projectId,
            EventTypes.COMMIT_APPROVED,
            emptyMap()
        )
    }

    fun signalCommitRejection(projectId: String) {
        ledger.appendEvent(
            projectId,
            EventTypes.COMMIT_REJECTED,
            emptyMap()
        )
    }

    companion object {
        private const val MAX_GOVERNOR_CYCLES = 30
    }
}
