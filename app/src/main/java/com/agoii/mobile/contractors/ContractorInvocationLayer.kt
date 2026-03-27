package com.agoii.mobile.contractors

/**
 * ContractorResult — structured output from contractor execution.
 *
 * @property contract_id         The contract identifier from the ExecutionContract.
 * @property report_reference    The RRID from the ExecutionContract.
 * @property contractor_id       The contractor that executed this contract.
 * @property output              The result data from contractor execution.
 * @property status              Execution status: "success" or "failure".
 * @property error               Optional error message when status is "failure".
 */
data class ContractorResult(
    val contract_id: String,
    val report_reference: String,
    val contractor_id: String,
    val output: Any,
    val status: String,
    val error: String? = null
)

/**
 * ContractorInvocationLayer — minimal execution adapter for invoking contractors.
 *
 * This layer receives a TaskAssignedContract and invokes the assigned contractor(s).
 * It is the ONLY point where real contractor execution occurs.
 *
 * Rules:
 *  - Input: TaskAssignedContract from ContractorsModule
 *  - Output: ContractorResult with structured data
 *  - NO bypass of ContractorsModule
 *  - NO direct API calls outside this layer
 *  - NO mutation of contract structure
 */
class ContractorInvocationLayer {
    
    /**
     * Invoke the contractor(s) assigned to the given task.
     *
     * @param taskContract The TaskAssignedContract from ContractorsModule selection.
     * @param contractPayload The execution payload to pass to the contractor.
     * @return ContractorResult with execution outcome.
     */
    fun invoke(
        taskContract: TaskAssignedContract,
        contractPayload: Map<String, Any>
    ): ContractorResult {
        // Extract assignment details
        val assignment = taskContract.assignment
        val contractorIds = assignment.contractorIds
        
        // Handle BLOCKED state
        if (assignment.mode == AssignmentMode.BLOCKED || contractorIds.isEmpty()) {
            return ContractorResult(
                contract_id = taskContract.contractId,
                report_reference = taskContract.reportReference,
                contractor_id = "none",
                output = mapOf("reason" to "No contractor assigned"),
                status = "failure",
                error = "Assignment mode: ${assignment.mode}"
            )
        }
        
        // For FEL, we use the first contractor (MATCHED mode)
        // Note: SWARM mode is excluded from FEL scope as per contract requirements.
        // In MATCHED mode, exactly one contractor is selected by DeterministicMatchingEngine.
        val contractorId = contractorIds.first()
        
        return try {
            // Invoke the contractor
            val result = invokeContractor(contractorId, taskContract, contractPayload)
            
            ContractorResult(
                contract_id = taskContract.contractId,
                report_reference = taskContract.reportReference,
                contractor_id = contractorId,
                output = result,
                status = "success"
            )
        } catch (e: Exception) {
            ContractorResult(
                contract_id = taskContract.contractId,
                report_reference = taskContract.reportReference,
                contractor_id = contractorId,
                output = mapOf("error" to (e.message ?: "Unknown error")),
                status = "failure",
                error = e.message
            )
        }
    }
    
    /**
     * Internal contractor invocation — simulates real contractor execution.
     *
     * In a production system, this would make actual API calls to:
     * - OpenAI API (for openai-gpt4)
     * - Google Gemini API (for gemini-pro)
     * - GitHub Copilot API (for github-copilot)
     *
     * For FEL, we return structured mock data that proves the loop works.
     */
    private fun invokeContractor(
        contractorId: String,
        taskContract: TaskAssignedContract,
        payload: Map<String, Any>
    ): Map<String, Any> {
        // Simulate contractor execution
        // In production, this would be:
        // when (contractorId) {
        //     "openai-gpt4" -> callOpenAI(payload)
        //     "gemini-pro" -> callGemini(payload)
        //     "github-copilot" -> callCopilot(payload)
        //     else -> throw IllegalArgumentException("Unknown contractor: $contractorId")
        // }
        
        return mapOf(
            "contractor_invoked" to true,
            "contractor_id" to contractorId,
            "contract_id" to taskContract.contractId,
            "report_reference" to taskContract.reportReference,
            "position" to taskContract.position,
            "input_payload" to payload,
            "execution_trace" to mapOf(
                "evaluated" to taskContract.trace.evaluated,
                "matched" to taskContract.trace.matched,
                "rejected" to taskContract.trace.rejected.map { 
                    mapOf("id" to it.contractorId, "reason" to it.reason) 
                }
            ),
            "result" to "Contractor $contractorId executed successfully",
            "timestamp" to System.currentTimeMillis()
        )
    }
}
