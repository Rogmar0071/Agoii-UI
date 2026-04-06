package com.agoii.mobile.bridge

import android.content.Context
import com.agoii.mobile.infrastructure.OpenAIClient
import com.agoii.mobile.contractor.*
import com.agoii.mobile.contracts.*
import com.agoii.mobile.core.*
import com.agoii.mobile.execution.*
import com.agoii.mobile.governor.Governor
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

    /**
     * Process an interaction using pre-interpreted structured intent.
     *
     * FIX-04: Interpretation MUST occur before CoreBridge (in CoreBridgeAdapter).
     * CoreBridge is transport + execution boundary ONLY — zero interpretation logic.
     *
     * @param rawInput        The original user text (written verbatim to USER_MESSAGE_SUBMITTED).
     * @param structuredIntent Pre-interpreted intent map from the interaction layer.
     */
    fun processInteraction(projectId: String, rawInput: String, structuredIntent: Map<String, Any>): String {
        if (rawInput.isBlank()) return "No input provided"
        return try {
            processInteractionInternal(projectId, rawInput, structuredIntent)
        } catch (_: Exception) {
            "Execution failed"
        }
    }

    private fun processInteractionInternal(
        projectId: String,
        rawInput: String,
        structuredIntent: Map<String, Any>
    ): String {

        // MQP-PHASE-3 FIX-02: USER_MESSAGE_SUBMITTED is ALWAYS the true ledger origin event.
        // It is written first on every turn:
        //   turn-1:  USER_MESSAGE_SUBMITTED (first event — origin truth)
        //   turn-2+: SYSTEM_MESSAGE_EMITTED → USER_MESSAGE_SUBMITTED (legal transition)
        ledger.appendEvent(
            projectId,
            EventTypes.USER_MESSAGE_SUBMITTED,
            mapOf(
                "messageId" to UUID.randomUUID().toString(),
                "text"      to rawInput,
                "timestamp" to System.currentTimeMillis()
            )
        )

        // MQP-PHASE-3 FIX-02: INTENT_SUBMITTED follows USER_MESSAGE_SUBMITTED on every turn.
        // Intent is derived FROM user input — not the other way around.
        // Transition: USER_MESSAGE_SUBMITTED → INTENT_SUBMITTED (legal — FIX-02).
        ledger.appendEvent(
            projectId,
            EventTypes.INTENT_SUBMITTED,
            mapOf("objective" to (structuredIntent["objective"] as? String ?: rawInput))
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

        while (cycles < MAX_GOVERNOR_CYCLES) {

            val lastEvent = ledger.loadEvents(projectId).lastOrNull()
            val lastType  = lastEvent?.type

            when {

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

        // MQP-LIFECYCLE-TERMINATION-CORRECTION-v1: SYSTEM_MESSAGE_EMITTED is a terminal state
        // confirmation — NOT a fallback. It is only emitted when the Governor completed
        // successfully and EXECUTION_COMPLETED is the current last event on the ledger.
        // On any failure path the exception propagates and no SYSTEM_MESSAGE_EMITTED is written,
        // leaving the ledger in a valid (non-corrupted) state.
        val finalState = ledger.loadEvents(projectId).lastOrNull()?.type
        if (finalState == EventTypes.EXECUTION_COMPLETED) {
            ledger.appendEvent(
                projectId,
                EventTypes.SYSTEM_MESSAGE_EMITTED,
                mapOf(
                    "messageId" to UUID.randomUUID().toString(),
                    "text"      to (output ?: EXECUTION_COMPLETE_MESSAGE),
                    "source"    to "execution",
                    "timestamp" to System.currentTimeMillis()
                )
            )
        }

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

        /**
         * Default text written to SYSTEM_MESSAGE_EMITTED when the execution loop
         * completes (EXECUTION_COMPLETED) but no specific output string was captured.
         * In practice `output` is always set to "SYSTEM_READY" before the loop exits,
         * so this constant serves only as a documented, distinctive fallback.
         */
        private const val EXECUTION_COMPLETE_MESSAGE = "execution_complete"
    }
}
