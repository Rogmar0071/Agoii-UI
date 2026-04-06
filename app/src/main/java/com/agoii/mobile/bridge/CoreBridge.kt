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
import java.util.UUID

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
    private val interactionEngine = InteractionEngine()

    fun processInteraction(projectId: String, input: String): String {
        if (input.isBlank()) return "No input provided"
        return try {
            processInteractionInternal(projectId, input)
        } catch (_: Exception) {
            "Execution failed"
        }
    }

    private fun processInteractionInternal(projectId: String, input: String): String {

        val structuredIntent = interactionEngine.processInput(input)

        // MQP-PHASE-3 FIX-02: On the first turn (empty ledger), INTENT_SUBMITTED must be written
        // first — required by ValidationLayer's structural invariant (first event = INTENT_SUBMITTED).
        // USER_MESSAGE_SUBMITTED immediately follows as the causal origin of the system flow.
        // On subsequent turns (ledger not empty, last event = SYSTEM_MESSAGE_EMITTED),
        // USER_MESSAGE_SUBMITTED is written directly — no new INTENT_SUBMITTED is needed.
        if (ledger.loadEvents(projectId).isEmpty()) {
            ledger.appendEvent(
                projectId,
                EventTypes.INTENT_SUBMITTED,
                mapOf("objective" to (structuredIntent["objective"] as? String ?: input))
            )
        }

        // USER_MESSAGE_SUBMITTED is written for every interaction:
        //   turn-1:  INTENT_SUBMITTED → USER_MESSAGE_SUBMITTED (legal transition)
        //   turn-2+: SYSTEM_MESSAGE_EMITTED → USER_MESSAGE_SUBMITTED (legal transition)
        ledger.appendEvent(
            projectId,
            EventTypes.USER_MESSAGE_SUBMITTED,
            mapOf(
                "messageId" to UUID.randomUUID().toString(),
                "text"      to input,
                "timestamp" to System.currentTimeMillis()
            )
        )

        val authResult = executionEntryPoint.executeIntent(
            projectId,
            structuredIntent
        )

        if (!authResult.authorized) {
            throw LedgerValidationException("ICS BLOCKED: ${authResult.reason}")
        }

        var cycles = 0
        var output: String? = null

        // MQP-PHASE-3 FIX-03: SYSTEM_MESSAGE_EMITTED MUST ALWAYS be written —
        // regardless of success, failure, recovery, or partial execution.
        try {
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
        } finally {
            // MQP-PHASE-3 FIX-03: Guaranteed SYSTEM_MESSAGE_EMITTED emission.
            // The Governor exits the loop at EXECUTION_COMPLETED (GovernorResult.COMPLETED),
            // so EXECUTION_COMPLETED → SYSTEM_MESSAGE_EMITTED is the valid transition.
            // InteractionEngine formats the state; CoreBridge only forwards the text.
            try {
                val interactionResult = interactionEngine.execute(
                    InteractionContract(
                        contractId = projectId,
                        query      = EXECUTION_SUMMARY_QUERY,
                        outputType = OutputType.EXPLANATION
                    ),
                    InteractionInput(state = replay.replayStructuralState(projectId))
                )
                ledger.appendEvent(
                    projectId,
                    EventTypes.SYSTEM_MESSAGE_EMITTED,
                    mapOf(
                        "messageId" to UUID.randomUUID().toString(),
                        "text"      to interactionResult.content,
                        "source"    to "execution",
                        "timestamp" to System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                // Emission failure is swallowed to preserve the original exception path.
                println("[PHASE-3] SYSTEM_MESSAGE_EMITTED emission failed: ${e.message}")
            }
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

    companion object {
        private const val MAX_GOVERNOR_CYCLES = 30
        /** Query label passed to InteractionEngine when generating SYSTEM_MESSAGE_EMITTED text. */
        private const val EXECUTION_SUMMARY_QUERY = "execution_summary"
    }
}
