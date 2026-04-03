package com.agoii.mobile.execution

import com.agoii.mobile.assembly.AssemblyExecutionResult
import com.agoii.mobile.assembly.AssemblyModule
import com.agoii.mobile.contractor.ContractorRegistry
import com.agoii.mobile.contracts.ContractReport
import com.agoii.mobile.contracts.UniversalContract
import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventLedger
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.execution.adapter.NemoClawAdapter

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

        return ExecutionAuthorityResult.Approved(sorted)
    }

    fun executeFromLedger(
        projectId: String,
        ledger: EventLedger
    ): ExecutionAuthorityExecutionResult {

        val events = ledger.loadEvents(projectId)
        val lastEvent = events.lastOrNull()
            ?: return ExecutionAuthorityExecutionResult.NotTriggered

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
     * This enforces strict architectural boundaries.
     */
    fun assembleFromLedger(
        projectId: String,
        ledger: EventLedger
    ): AssemblyExecutionResult {
        return AssemblyExecutionResult.Failure(
            "Assembly execution not permitted in ExecutionAuthority"
        )
    }
}
