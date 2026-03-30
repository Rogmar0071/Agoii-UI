package com.agoii.mobile.bridge

import android.content.Context
import com.agoii.mobile.commit.ApprovalStatus
import com.agoii.mobile.contractor.ContractorCandidate
import com.agoii.mobile.contractor.ContractorRegistry
import com.agoii.mobile.contractor.ContractorSystem
import com.agoii.mobile.contractor.ContractorSystemResult
import com.agoii.mobile.contractor.ContractorVerificationEngine
import com.agoii.mobile.contracts.ContractCapability
import com.agoii.mobile.contracts.ExecutionType
import com.agoii.mobile.contracts.TargetDomain
import com.agoii.mobile.core.*
import com.agoii.mobile.execution.BuildExecutor
import com.agoii.mobile.execution.ExecutionAuthority
import com.agoii.mobile.execution.ExecutionEntryPoint
import com.agoii.mobile.governor.Governor
import com.agoii.mobile.interaction.InteractionContract
import com.agoii.mobile.interaction.OutputType
import com.agoii.mobile.irs.*
import com.agoii.mobile.observability.ExecutionObservability
import com.agoii.mobile.observability.ExecutionTimeline
import com.agoii.mobile.observability.ExecutionTrace
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
    private val executionEntryPoint = ExecutionEntryPoint(ledger)

    private val contractorRegistry  = buildContractorRegistry()
    private val executionAuthority  = ExecutionAuthority(contractorRegistry)
    private val contractorSystem    = ContractorSystem()

    private val observability       = ExecutionObservability(ledger)

    fun submitIntent(
        projectId:         String,
        rawFields:         Map<String, String>,
        evidence:          Map<String, List<EvidenceRef>>,
        swarmConfig:       SwarmConfig,
        availableEvidence: Map<String, List<EvidenceRef>> = emptyMap(),
        objective:         String
    ): Boolean {
        val sessionId = "$projectId-${UUID.randomUUID()}"

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

        val irsStatus = when (stepResult.orchestratorResult) {
            is OrchestratorResult.Certified -> "CERTIFIED"
            else                            -> "PENDING"
        }

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

    fun processInteraction(projectId: String, input: String): String {

        contractorRegistry.allVerified()
            .firstOrNull { it.source == "llm" }
            ?: throw LedgerValidationException("ICS BLOCKED: No real communication contractor available")

        val events = ledger.loadEvents(projectId)

        if (events.isEmpty()) {
            val sessionId = "$projectId-${UUID.randomUUID()}"

            irsOrchestrator.createSession(
                sessionId,
                mapOf("objective" to input),
                emptyMap(),
                SwarmConfig(agentCount = 2, consensusRule = ConsensusRule.MAJORITY),
                emptyMap()
            )

            var stepResult: StepResult
            do {
                stepResult = irsOrchestrator.step(sessionId)
            } while (!stepResult.terminal)

            val irsStatus = when (stepResult.orchestratorResult) {
                is OrchestratorResult.Certified -> "CERTIFIED"
                else                            -> "PENDING"
            }

            ledger.appendEvent(
                projectId,
                EventTypes.INTENT_SUBMITTED,
                mapOf(
                    "objective"       to input,
                    "certificationId" to sessionId,
                    "certifiedAt"     to System.currentTimeMillis(),
                    "irsStatus"       to irsStatus
                )
            )
        }

        val icsTaskId = "$projectId-ics-${UUID.randomUUID()}"

        val systemResult = contractorSystem.execute(
            taskId               = icsTaskId,
            contractId           = icsTaskId,
            reportReference      = icsTaskId,
            position             = 1,
            constraints          = emptyList(),
            expectedOutput       = "Clarify intent",
            taskPayload          = mapOf("userInput" to input),
            requiredCapabilities = listOf(
                ContractCapability.RELIABILITY,
                ContractCapability.CONSTRAINT_OBEDIENCE
            ),
            executionType        = ExecutionType.COMMUNICATION,
            targetDomain         = TargetDomain.CONTRACTOR,
            registry             = contractorRegistry
        )

        val rawOutput: String = when (systemResult) {

            is ContractorSystemResult.Blocked ->
                throw LedgerValidationException(
                    "ICS BLOCKED: Contractor execution failed — ${systemResult.reason}"
                )

            is ContractorSystemResult.Resolved -> {
                val artifact = systemResult.executionOutput.resultArtifact

                val text = artifact.entries
                    .filterNot { entry: Map.Entry<String, Any> ->
                        entry.key in ARTIFACT_METADATA_KEYS
                    }
                    .mapNotNull { entry: Map.Entry<String, Any> ->
                        entry.value?.toString()?.takeIf { value -> value.isNotBlank() }
                    }
                    .firstOrNull()

                if (text.isNullOrBlank()) {
                    throw LedgerValidationException(
                        "ICS BLOCKED: Contractor returned no human-readable output"
                    )
                }

                text
            }
        }

        ledger.appendEvent(
            projectId,
            EventTypes.CONTRACTS_GENERATED,
            mapOf(
                "contractSetId" to icsTaskId,
                "total" to 1
            )
        )

        return rawOutput
    }

    private fun buildContractorRegistry(): ContractorRegistry {
        val registry  = ContractorRegistry()
        val engine    = ContractorVerificationEngine()

        REQUIRED_CONTRACTORS.forEach { (id, source, claims) ->
            val candidate = ContractorCandidate(id, source, claims)
            val result = engine.verify(candidate)
            result.assignedProfile?.let { registry.registerVerified(it) }
        }

        return registry
    }

    companion object {

        private val ARTIFACT_METADATA_KEYS: Set<String> = setOf(
            "taskId", "constraintsMet", "executionType", "targetDomain"
        )

        private val REQUIRED_CONTRACTORS: List<Triple<String, String, Map<String, String>>> = listOf(
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
            )
        )
    }
}
