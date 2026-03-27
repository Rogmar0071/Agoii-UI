package com.agoii.mobile.contractors

import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventTypes

/**
 * FirstExecutionLoopOrchestrator — orchestrates the FEL flow.
 *
 * This orchestrator sits between the Governor and the contractor execution layer.
 * It is invoked AFTER the Governor writes a TASK_ASSIGNED event and BEFORE
 * task execution begins.
 *
 * Flow:
 * 1. Read TASK_ASSIGNED event from ledger
 * 2. Extract contract details and build ExecutionContract
 * 3. Call ContractorsModule to select contractor
 * 4. Call ContractorInvocationLayer to execute
 * 5. Return result (no ledger write for FEL)
 *
 * Rules:
 * - NO bypass of ContractorsModule
 * - NO mutation of contract structure
 * - NO additional event types
 * - Result is traceable via contract_id and report_reference
 */
class FirstExecutionLoopOrchestrator(
    private val registry: ContractorRegistry,
    private val invocationLayer: ContractorInvocationLayer = ContractorInvocationLayer()
) {
    
    private val matchingEngine = DeterministicMatchingEngine()
    
    /**
     * Execute the FEL for a given TASK_ASSIGNED event.
     *
     * @param events The full event ledger (to extract context).
     * @param taskAssignedEvent The TASK_ASSIGNED event triggering execution.
     * @return ContractorResult with execution outcome.
     */
    fun executeTask(
        events: List<Event>,
        taskAssignedEvent: Event
    ): ContractorResult {
        require(taskAssignedEvent.type == EventTypes.TASK_ASSIGNED) {
            "Expected TASK_ASSIGNED event, got: ${taskAssignedEvent.type}"
        }
        
        // Extract task details from event
        val taskId = taskAssignedEvent.payload["taskId"] as? String
            ?: return errorResult("Missing taskId", "unknown", "unknown")
        
        val position = resolveInt(taskAssignedEvent.payload["position"])
            ?: return errorResult("Missing position", taskId, "unknown")
        
        // Extract contract_id and report_reference from contracts_generated event
        val contractsGenerated = events.firstOrNull { 
            it.type == EventTypes.CONTRACTS_GENERATED 
        } ?: return errorResult("No CONTRACTS_GENERATED event found", taskId, "unknown")
        
        val reportId = contractsGenerated.payload["report_id"] as? String
            ?: return errorResult("Missing report_id", taskId, "unknown")
        
        val contracts = contractsGenerated.payload["contracts"] as? List<*>
            ?: return errorResult("Missing contracts list", taskId, reportId)
        
        val contractData = contracts.firstOrNull { contract ->
            (contract as? Map<*, *>)?.get("position")?.let { resolveInt(it) } == position
        } as? Map<*, *> ?: return errorResult("Contract not found for position $position", taskId, reportId)
        
        val contractId = contractData["contractId"] as? String
            ?: return errorResult("Missing contractId", taskId, reportId)
        
        // Build ExecutionContract for ContractorsModule
        val executionContract = ExecutionContract(
            contractId = contractId,
            reportReference = reportId,
            position = position.toString()
        )
        
        // Define contract requirements (minimal for FEL)
        // Note: In production, these requirements should be derived from the contract
        // payload or configured based on the objective. For FEL demonstration,
        // we use basic requirements that all registered contractors can satisfy.
        val requirements = listOf(
            ContractRequirement("code_generation", 3, 1.0),
            ContractRequirement("reasoning", 2, 0.5)
        )
        
        // Call ContractorsModule to select contractor
        val taskContract = matchingEngine.resolve(
            contract = executionContract,
            requirements = requirements,
            registry = registry
        )
        
        // Build contract payload
        val contractPayload = mapOf(
            "taskId" to taskId,
            "contractId" to contractId,
            "position" to position,
            "reportReference" to reportId,
            "objective" to "Execute contract $contractId at position $position"
        )
        
        // Invoke contractor via invocation layer
        return invocationLayer.invoke(taskContract, contractPayload)
    }
    
    private fun errorResult(
        message: String, 
        contractId: String, 
        reportRef: String
    ): ContractorResult {
        return ContractorResult(
            contract_id = contractId,
            report_reference = reportRef,
            contractor_id = "none",
            output = mapOf("error" to message),
            status = "failure",
            error = message
        )
    }
    
    private fun resolveInt(value: Any?): Int? = when (value) {
        is Int    -> value
        is Double -> value.toInt()
        is Long   -> value.toInt()
        is String -> value.toIntOrNull()
        else      -> null
    }
}
