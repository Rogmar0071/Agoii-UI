package com.agoii.mobile.bridge

import android.content.Context
import com.agoii.mobile.commit.ApprovalStatus
import com.agoii.mobile.infrastructure.ConfigProvider
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
        val config = ConfigProvider.openAI()

        if (config.apiKey.isNotBlank()) {
            driverRegistry.register(
                "llm",
                LLMContractor(OpenAIClient(), config)
            )
        }
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

        val events = ledger.loadEvents(projectId)

        if (events.isEmpty()) {
            ledger.appendEvent(
                projectId,
                EventTypes.INTENT_SUBMITTED,
                mapOf("objective" to input)
            )
        }

        val icsTaskId = "$projectId-ics-${UUID.randomUUID()}"

        val result = contractorSystem.execute(
            taskId = icsTaskId,
            contractId = icsTaskId,
            reportReference = icsTaskId,
            position = 1,
            constraints = emptyList(),
            expectedOutput = "Clarify intent",
            taskPayload = mapOf("userInput" to input),
            requiredCapabilities = listOf(
                ContractCapability.RELIABILITY,
                ContractCapability.CONSTRAINT_OBEDIENCE
            ),
            executionType = ExecutionType.COMMUNICATION,
            targetDomain = TargetDomain.CONTRACTOR,
            registry = contractorRegistry
        )

        val output = when (result) {

            is ContractorSystemResult.Blocked ->
                throw LedgerValidationException("ICS BLOCKED: ${result.reason}")

            is ContractorSystemResult.Resolved -> {
                val artifact = result.executionOutput.resultArtifact

                val text = artifact.entries
                    .filterNot { entry ->
                        entry.key in ARTIFACT_METADATA_KEYS
                    }
                    .mapNotNull { entry ->
                        entry.value?.toString()?.takeIf { it.isNotBlank() }
                    }
                    .firstOrNull()

                text ?: throw LedgerValidationException("ICS BLOCKED: Empty output")
            }
        }

        ledger.appendEvent(
            projectId,
            EventTypes.CONTRACTS_GENERATED,
            mapOf("contractSetId" to icsTaskId)
        )

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
        private val ARTIFACT_METADATA_KEYS = setOf(
            "taskId", "constraintsMet", "executionType", "targetDomain"
        )

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
