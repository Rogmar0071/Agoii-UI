package com.agoii.mobile.bridge

import android.content.Context
import android.util.Log
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

    /**
     * Guard that prevents the LedgerActivator from running a second, concurrent execution
     * spine while one is already in progress (either via the observer or the legacy
     * [processInteractionInternal] path).
     *
     * CONTRACT MQP-LEDGER-ACTIVATION-v1: compareAndSet semantics ensure exactly-once
     * activation per submitted intent.
     */
    private val spineRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        driverRegistry.register(
            "llm",
            LLMContractor(OpenAIClient())
        )

        // CONTRACT MQP-LEDGER-ACTIVATION-v1 — register the execution spine as a ledger
        // observer so that every event append can trigger system progression.
        // The guard (spineRunning) prevents re-entrant or concurrent spine execution.
        ledger.registerObserver(object : LedgerObserver {
            override fun onLedgerUpdated(projectId: String) {
                // Only activate on INTENT_SUBMITTED — the sole UI-origin trigger.
                // All other events (emitted by the spine itself) fire this observer but
                // the guard ensures they are ignored while the spine is running.
                val lastType = ledger.loadEvents(projectId).lastOrNull()?.type ?: return
                if (lastType != EventTypes.INTENT_SUBMITTED) return

                Log.e("AGOII_TRACE", "ACTIVATOR_TRIGGERED")

                if (!spineRunning.compareAndSet(false, true)) {
                    Log.e("AGOII_TRACE", "SPINE_SKIPPED_ALREADY_RUNNING")
                    return
                }
                try {
                    activateSpine(projectId)
                } finally {
                    spineRunning.set(false)
                }
            }
        })
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
        Log.e("AGOII_TRACE", "CORE_START")
        return try {
            val result = processInteractionInternal(projectId, rawInput, structuredIntent)
            Log.e("AGOII_TRACE", "CORE_END")
            result
        } catch (t: Throwable) {
            Log.e("AGOII_TRACE", "CORE_CRASH", t)
            throw t
        }
    }

    private fun processInteractionInternal(
        projectId: String,
        rawInput: String,
        structuredIntent: Map<String, Any>
    ): String {
        // No spine guard here — execution is driven exclusively by the LedgerObserver
        // registered in init (CONTRACT MQP-LEDGER-ACTIVATION-v1). Holding spineRunning
        // during the ledger appends would block the observer from activating the spine.
        return processInteractionCore(projectId, rawInput, structuredIntent)
    }

    /**
     * Ledger ingress only — delegates to [appendUserMessage] then returns "INGRESS_ACCEPTED".
     * Execution is driven by the [LedgerObserver] registered in [init].
     */
    private fun processInteractionCore(
        projectId: String,
        rawInput: String,
        structuredIntent: Map<String, Any>
    ): String {
        appendUserMessage(projectId, rawInput, structuredIntent)
        return "INGRESS_ACCEPTED"
    }

    /**
     * Run the full execution spine from INTENT_SUBMITTED onward.
     * Called exclusively from [activateSpine], which holds [spineRunning].
     */
    private fun runSpine(projectId: String, structuredIntent: Map<String, Any>): String {
        Log.e("AGOII_TRACE", "SPINE_START")
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
                    Log.e("AGOII_TRACE", "EXECUTION_START")
                    val execResult = executionAuthority.executeFromLedger(projectId, ledger)
                    Log.e("AGOII_TRACE", "EXECUTION_DONE: ${execResult::class.simpleName}")

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
                    Log.e("AGOII_TRACE", "EXECUTION_START")
                    executionAuthority.executeFromLedger(projectId, ledger)
                    Log.e("AGOII_TRACE", "EXECUTION_DONE: retry")
                }

                else -> {
                    Log.e("AGOII_TRACE", "GOVERNOR_STEP: state=$lastType cycle=$cycles")
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
            Log.e("AGOII_TRACE", "LEDGER_EVENT_APPENDED: ${EventTypes.SYSTEM_MESSAGE_EMITTED}")
        }

        return output ?: throw LedgerValidationException(
            "ICS BLOCKED: No output produced — final state='$finalState' cycles=$cycles"
        )
    }

    /**
     * CONTRACT MQP-LEDGER-ACTIVATION-v1 — execution spine driven from ledger observer.
     *
     * Invoked by the [LedgerObserver] registered in [init] when [EventTypes.INTENT_SUBMITTED]
     * is the last event on the ledger for [projectId].  Caller holds [spineRunning].
     *
     * Reads the structured intent from the INTENT_SUBMITTED event payload, then runs the
     * full execution spine to completion.  Any error is logged and swallowed so that the
     * calling observer callback never propagates an exception to [EventLedger.appendEvent].
     */
    private fun activateSpine(projectId: String) {
        Log.e("AGOII_TRACE", "SPINE_ACTIVATE: $projectId")
        try {
            val events = ledger.loadEvents(projectId)
            val intentPayload = events.lastOrNull()
                ?.takeIf { it.type == EventTypes.INTENT_SUBMITTED }?.payload
                ?: run {
                    Log.e("AGOII_TRACE", "SPINE_NO_INTENT_EVENT")
                    return
                }
            runSpine(projectId, intentPayload)
            Log.e("AGOII_TRACE", "SPINE_COMPLETE: $projectId")
        } catch (t: Throwable) {
            Log.e("AGOII_TRACE", "SPINE_ERROR: ${t.stackTraceToString()}")
        }
    }

    fun loadEvents(projectId: String): List<Event> =
        ledger.loadEvents(projectId)

    /**
     * CONTRACT MQP-UI-INGRESS-ONLY-v1 — pure ledger ingress.
     *
     * Appends [EventTypes.USER_MESSAGE_SUBMITTED] and [EventTypes.INTENT_SUBMITTED] to the
     * ledger and returns immediately.  MUST NOT trigger execution, the Governor, or any
     * processing loop directly.
     *
     * System progression is driven exclusively by the [LedgerObserver] registered in [init],
     * which reacts to [EventTypes.INTENT_SUBMITTED] and activates the execution spine
     * (CONTRACT MQP-LEDGER-ACTIVATION-v1).
     *
     * BLOCK CONDITIONS:
     *  - ANY call chain reachable from this method into execution → BLOCKED
     *  - ANY call to [processInteractionInternal] or [executionEntryPoint] → BLOCKED
     *
     * @param projectId       Active project scope.
     * @param rawInput        Original user text (written verbatim to ledger).
     * @param structuredIntent Pre-interpreted intent from the interaction layer (ARCH-09).
     */
    fun appendUserMessage(projectId: String, rawInput: String, structuredIntent: Map<String, Any>) {
        if (rawInput.isBlank()) return
        Log.e("AGOII_TRACE", "CORE_APPEND_USER_MESSAGE")

        ledger.appendEvent(
            projectId,
            EventTypes.USER_MESSAGE_SUBMITTED,
            mapOf(
                "messageId" to UUID.randomUUID().toString(),
                "text"      to rawInput,
                "timestamp" to System.currentTimeMillis()
            )
        )
        Log.e("AGOII_TRACE", "LEDGER_APPEND_USER")

        val intentObjective = resolveObjective(structuredIntent, rawInput)
        ledger.appendEvent(
            projectId,
            EventTypes.INTENT_SUBMITTED,
            mapOf("objective" to intentObjective)
        )
        Log.e("AGOII_TRACE", "LEDGER_APPEND_INTENT")

        // HARD STOP — execution is driven by LedgerObserver (CONTRACT MQP-LEDGER-ACTIVATION-v1)
        Log.e("AGOII_TRACE", "CORE_APPEND_USER_MESSAGE_COMPLETE")
    }

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
        Log.e("AGOII_TRACE", "LEDGER_EVENT_APPENDED: ${EventTypes.CONTRACTS_APPROVED}")
    }

    /**
     * Extract the objective string from [structuredIntent], falling back to [rawInput]
     * if the key is absent or not a String.  Logs a warning when the fallback is used.
     */
    private fun resolveObjective(structuredIntent: Map<String, Any>, rawInput: String): String {
        val objective = structuredIntent["objective"] as? String
        if (objective == null) {
            Log.e("AGOII_TRACE", "INTENT_OBJECTIVE_MISSING: using rawInput as fallback")
        }
        return objective ?: rawInput
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
