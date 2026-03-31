package com.agoii.mobile.bridge

import android.content.Context
import com.agoii.mobile.commit.ApprovalStatus
import com.agoii.mobile.infrastructure.OpenAIClient
import com.agoii.mobile.contractor.*
import com.agoii.mobile.contracts.*
import com.agoii.mobile.core.*
import com.agoii.mobile.execution.*
import com.agoii.mobile.governor.Governor
import com.agoii.mobile.interaction.*
import com.agoii.mobile.irs.*
import com.agoii.mobile.observability.*
import java.util.UUID

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

    private val contractorSystem = ContractorSystem(
        driverRegistry = driverRegistry
    )

    private val observability = ExecutionObservability(ledger)

    // ─────────────────────────────────────────────────────────────
    // ICS ENTRY
    // ─────────────────────────────────────────────────────────────
    fun processInteraction(projectId: String, input: String): String {

        contractorRegistry.allVerified()
            .firstOrNull { it.source == "llm" }
            ?: throw LedgerValidationException("ICS BLOCKED: No real communication contractor available")

        // Step 1: Write INTENT_SUBMITTED if ledger is empty
        var events = ledger.loadEvents(projectId)
        if (events.isEmpty()) {
            ledger.appendEvent(
                projectId,
                EventTypes.INTENT_SUBMITTED,
                mapOf("objective" to input)
            )
            events = ledger.loadEvents(projectId)
        }

        // Step 2: ExecutionEntryPoint generates contracts → CONTRACTS_GENERATED
        val alreadyHasContracts = events.any { it.type == EventTypes.CONTRACTS_GENERATED }
        if (!alreadyHasContracts) {
            val intentEvent = events.firstOrNull { it.type == EventTypes.INTENT_SUBMITTED }
                ?: throw LedgerValidationException("ICS BLOCKED: No INTENT_SUBMITTED event")

            val authResult = executionEntryPoint.executeIntent(
                projectId     = projectId,
                intentPayload = intentEvent.payload
            )
            if (!authResult.authorized) {
                throw LedgerValidationException("ICS BLOCKED: ${authResult.reason}")
            }
        }

        // Step 3–5: Governor drives CONTRACTS_GENERATED → TASK_STARTED,
        // ExecutionAuthority executes the contractor, Governor closes the lifecycle.
        var responseText: String? = null
        var iterations = 0

        while (iterations++ < MAX_EXECUTION_CHAIN_ITERATIONS) {
            val lastEventType = ledger.loadEvents(projectId).lastOrNull()?.type
                ?: throw LedgerValidationException("ICS BLOCKED: Empty ledger after init")

            when (lastEventType) {
                EventTypes.TASK_STARTED -> {
                    val execResult = executionAuthority.executeFromLedger(projectId, ledger)
                    if (execResult is ExecutionAuthorityExecutionResult.Executed &&
                        execResult.executionStatus == ExecutionStatus.SUCCESS) {
                        val artifact = execResult.report.artifactStructure
                        val text = artifact.entries
                            .filterNot { entry -> entry.key in ARTIFACT_METADATA_KEYS }
                            .mapNotNull { entry ->
                                entry.value?.toString()?.takeIf { it.isNotBlank() }
                            }
                            .firstOrNull()
                        if (text != null && responseText == null) responseText = text
                    }
                }
                EventTypes.EXECUTION_COMPLETED -> break
                EventTypes.TASK_FAILED, EventTypes.CONTRACT_FAILED -> break
                else -> {
                    val govResult = governor.runGovernor(projectId)
                    if (govResult == Governor.GovernorResult.NO_EVENT ||
                        govResult == Governor.GovernorResult.DRIFT ||
                        govResult == Governor.GovernorResult.COMPLETED) break
                }
            }
        }

        return responseText
            ?: run {
                val lastEvent = ledger.loadEvents(projectId).lastOrNull()?.type ?: "EMPTY"
                throw LedgerValidationException(
                    "ICS BLOCKED: No response produced from execution chain (last event: $lastEvent)"
                )
            }
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
        private val ARTIFACT_METADATA_KEYS = setOf(
            "taskId", "constraintsMet", "executionType", "targetDomain"
        )

        /**
         * Upper bound on governor + executeFromLedger iterations per interaction.
         *
         * A single-contract ICS cycle requires ~9 steps (CONTRACTS_GENERATED → EXECUTION_COMPLETED).
         * 30 accommodates up to ~3 contracts with headroom for retries, preventing infinite loops
         * in the event of an unexpected ledger state.
         */
        private const val MAX_EXECUTION_CHAIN_ITERATIONS = 30

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
