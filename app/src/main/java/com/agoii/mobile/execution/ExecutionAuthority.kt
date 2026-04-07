package com.agoii.mobile.execution

import android.util.Log
import com.agoii.mobile.assembly.AssemblyExecutionResult
import com.agoii.mobile.assembly.AssemblyModule
import com.agoii.mobile.contractor.ContractorRegistry
import com.agoii.mobile.contracts.ContractReport
import com.agoii.mobile.contracts.UniversalContract
import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventLedger
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.execution.adapter.NemoClawAdapter

// ── Capability Derivation Models (MQP-CAPABILITY-DERIVATION-v1) ──────────────

/**
 * A single deterministic capability derived from a contract step.
 *
 * Implements Universal Contract Law and Deterministic Matching Law:
 * each capability is a 1:1 deterministic projection of a contract.
 *
 * @property id              Stable capability identifier (deterministic: "cap_<contractId>").
 * @property objectiveLink   Report reference (RRID) linking this capability to its originating intent.
 * @property requiredOutcome Human-readable outcome requirement mapped from the contract name.
 * @property constraints     Immutable constraint map derived from the contract step metadata.
 */
data class Capability(
    val id: String,
    val objectiveLink: String,
    val requiredOutcome: String,
    val constraints: Map<String, String>
)

/**
 * A complete, deterministic set of capabilities derived from the approved contract list.
 *
 * Convergence Law: all capabilities in the set must be fulfilled for intent completion.
 *
 * @property capabilities    Ordered list of derived capabilities (1:1 with approved contracts).
 * @property derivedFromIntent Report reference (RRID) identifying the originating intent.
 */
data class CapabilitySet(
    val capabilities: List<Capability>,
    val derivedFromIntent: String
)

/**
 * Result of the deterministic capability derivation phase inside [ExecutionAuthority.evaluate].
 */
sealed class CapabilityDerivationResult {
    /**
     * Capabilities successfully derived from the approved contract list.
     *
     * @property capabilitySet The full derived capability set (AERP-1 binding).
     */
    data class Derived(val capabilitySet: CapabilitySet) : CapabilityDerivationResult()

    /**
     * Capability derivation blocked — contracts produced no derivable capabilities.
     *
     * @property reason Human-readable block reason.
     */
    data class Blocked(val reason: String) : CapabilityDerivationResult()
}

// ─────────────────────────────────────────────────────────────────────────────

class ExecutionAuthority(
    private val contractorRegistry: ContractorRegistry,
    private val driverRegistry: DriverRegistry
) {

    private val nemoClawAdapter = NemoClawAdapter()
    private val resolutionLayer = ExecutionResolutionLayer()

    companion object {
        const val MAX_DELTA: Int = 3
        private const val MAX_DIFF_RATIO = 0.4
    }

    private val assemblyModule = AssemblyModule()

    fun evaluate(input: ExecutionContractInput): ExecutionAuthorityResult {
        if (input.reportReference.isBlank()) {
            return ExecutionAuthorityResult.Blocked("Report reference is blank")
        }

        val contracts = input.contracts
        if (contracts.isEmpty()) {
            return ExecutionAuthorityResult.Blocked("Contract list is empty")
        }

        val mismatch = contracts.firstOrNull { it.reportReference != input.reportReference }
        if (mismatch != null) {
            return ExecutionAuthorityResult.Blocked("Report reference mismatch")
        }

        val sorted = contracts.sortedBy { it.position }
        sorted.forEachIndexed { index, contract ->
            if (contract.position != index + 1) {
                return ExecutionAuthorityResult.Blocked("Invalid contract position sequence")
            }
        }

        val capabilityDerivation = deriveCapabilities(sorted, input.reportReference)
        if (capabilityDerivation is CapabilityDerivationResult.Blocked) {
            return ExecutionAuthorityResult.Blocked(capabilityDerivation.reason)
        }

        val capabilitySet = (capabilityDerivation as CapabilityDerivationResult.Derived).capabilitySet
        return ExecutionAuthorityResult.Approved(sorted, capabilitySet)
    }

    /**
     * Derives a deterministic [CapabilitySet] from the approved, ordered contract list.
     *
     * Deterministic Matching Law: each contract position maps to exactly one capability.
     * Convergence Law: the capability set is closed — every contract must yield a capability.
     *
     * @param contracts     Ordered, approved contracts from [evaluate].
     * @param reportReference Report reference (RRID) linking capabilities to the originating intent.
     * @return [CapabilityDerivationResult.Derived] on success;
     *         [CapabilityDerivationResult.Blocked] when derivation is structurally impossible.
     */
    private fun deriveCapabilities(
        contracts: List<ExecutionContract>,
        reportReference: String
    ): CapabilityDerivationResult {
        if (contracts.isEmpty()) {
            return CapabilityDerivationResult.Blocked("Cannot derive capabilities from empty contract list")
        }

        val capabilities = contracts.map { contract ->
            Capability(
                id              = "cap_${contract.contractId}",
                objectiveLink   = reportReference,
                requiredOutcome = contract.name,
                constraints     = mapOf(
                    "position"        to contract.position.toString(),
                    "reportReference" to reportReference
                )
            )
        }

        return CapabilityDerivationResult.Derived(
            CapabilitySet(
                capabilities       = capabilities,
                derivedFromIntent  = reportReference
            )
        )
    }

    fun executeFromLedger(
        projectId: String,
        ledger: EventLedger
    ): ExecutionAuthorityExecutionResult {

        val events = ledger.loadEvents(projectId)
        val lastEvent = events.lastOrNull()
            ?: return ExecutionAuthorityExecutionResult.NotTriggered

        if (events.none { it.type == EventTypes.CONTRACTS_GENERATED }) {
            Log.e("AGOII_TRACE", "EXECUTION_BLOCKED_NO_CONTRACTS")
            return ExecutionAuthorityExecutionResult.NotTriggered
        }

        val result = when (lastEvent.type) {
            EventTypes.TASK_STARTED -> handleTaskStarted(projectId, lastEvent, events)
            EventTypes.TASK_EXECUTED -> handleTaskExecuted(lastEvent)
            else -> ExecutionAuthorityExecutionResult.NotTriggered
        }

        resolutionLayer.resolve(result, projectId, ledger, events)

        return result
    }

    private fun handleTaskStarted(
        projectId: String,
        taskEvent: Event,
        events: List<Event>
    ): ExecutionAuthorityExecutionResult {

        val taskId = taskEvent.payload["taskId"]?.toString()
            ?: return ExecutionAuthorityExecutionResult.Blocked("taskId missing")

        val contractId = taskEvent.payload["contractId"]?.toString()
            ?: return ExecutionAuthorityExecutionResult.Blocked("contractId missing")

        val reportReference = extractReportReference(events, contractId)
        if (reportReference.isBlank()) {
            return ExecutionAuthorityExecutionResult.Blocked("Missing report reference")
        }

        val contract = ExecutionContract(
            contractId = contractId,
            name = contractId,
            position = 1,
            reportReference = reportReference
        )

        val executionReport = try {
            nemoClawAdapter.execute(contract)
        } catch (e: Exception) {
            return ExecutionAuthorityExecutionResult.Blocked("Adapter failure: ${e.message}")
        }

        if (executionReport.artifact == null) {
            return ExecutionAuthorityExecutionResult.Blocked("Missing artifact")
        }

        if (executionReport.executionId != contract.executionId) {
            return ExecutionAuthorityExecutionResult.Executed(taskId, ExecutionStatus.FAILURE, null)
        }

        if (executionReport.status != "SUCCESS") {
            return ExecutionAuthorityExecutionResult.Executed(taskId, ExecutionStatus.FAILURE, null)
        }

        return ExecutionAuthorityExecutionResult.Executed(
            taskId,
            ExecutionStatus.SUCCESS,
            null
        )
    }

    private fun handleTaskExecuted(
        taskEvent: Event
    ): ExecutionAuthorityExecutionResult {

        val status = taskEvent.payload["executionStatus"]?.toString()
        if (status != "FAILURE") {
            return ExecutionAuthorityExecutionResult.NotTriggered
        }

        val taskId = taskEvent.payload["taskId"]?.toString()
            ?: return ExecutionAuthorityExecutionResult.Blocked("taskId missing")

        return ExecutionAuthorityExecutionResult.Executed(
            taskId,
            ExecutionStatus.FAILURE,
            null
        )
    }

    private fun extractReportReference(events: List<Event>, contractId: String): String {
        val contractCreated = events.firstOrNull {
            it.type == EventTypes.CONTRACT_CREATED &&
            it.payload["contractId"]?.toString() == contractId
        }

        if (contractCreated != null) {
            return contractCreated.payload["report_reference"]?.toString() ?: ""
        }

        val contractsGenerated = events.firstOrNull {
            it.type == EventTypes.CONTRACTS_GENERATED
        }

        return contractsGenerated?.payload?.get("report_reference")?.toString() ?: ""
    }

    fun ingestUniversalContract(
        contract: UniversalContract,
        projectId: String,
        ledger: EventLedger
    ): UniversalIngestionResult {

        if (contract.reportReference.isBlank()) {
            return UniversalIngestionResult.ValidationFailed(
                contract.contractId,
                "Missing report reference"
            )
        }

        ledger.appendEvent(
            projectId,
            EventTypes.CONTRACT_CREATED,
            mapOf(
                "contractId" to contract.contractId,
                "report_reference" to contract.reportReference
            )
        )

        ledger.appendEvent(
            projectId,
            EventTypes.CONTRACT_VALIDATED,
            mapOf(
                "contractId" to contract.contractId,
                "report_reference" to contract.reportReference
            )
        )

        return UniversalIngestionResult.Ingested(contract.contractId)
    }

    /**
     * Assembly execution is NOT allowed inside ExecutionAuthority.
     */
    fun assembleFromLedger(
        projectId: String,
        ledger: EventLedger
    ): AssemblyExecutionResult {
        return AssemblyExecutionResult.Blocked(
            "Assembly execution not permitted in ExecutionAuthority"
        )
    }
}
