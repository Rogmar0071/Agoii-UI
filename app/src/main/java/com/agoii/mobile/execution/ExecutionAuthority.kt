// AGOII CONTRACT — EXECUTION AUTHORITY MODULE (ONE-SHOT)
// CLASSIFICATION:
// - Class: Structural
// - Reversibility: Forward-only
// - Execution Scope: Both
// - Mutation Authority: Tier C
//
// PURPOSE:
// Validate and authorize execution contracts BEFORE ledger write.
// PURE validation + authorization ONLY.
//
// MUST NOT:
// - write to ledger
// - derive contracts
// - execute tasks
// - call external systems

package com.agoii.mobile.execution

import com.agoii.mobile.contractor.ContractorProfile

// ---------- INPUT ----------

data class ExecutionContractInput(
    val contracts: List<ExecutionContract>,
    val reportId: String
)

data class ExecutionContract(
    val contractId: String,
    val name: String,
    val position: Int,
    val reportReference: String
)

// ---------- OUTPUT ----------

sealed class ExecutionAuthorityResult {

    data class Approved(
        val orderedContracts: List<ExecutionContract>
    ) : ExecutionAuthorityResult()

    data class Blocked(
        val reason: String
    ) : ExecutionAuthorityResult()
}

// ---------- EXECUTION AUTHORITY ----------

/**
 * ExecutionAuthority - validates and executes TaskAssignedContracts.
 * 
 * Each ExecutionAuthority instance maintains its own ContractorExecutor
 * to ensure thread-safety and state isolation across different execution contexts.
 */
class ExecutionAuthority {

    private val contractorExecutor = ContractorExecutor()

    companion object {
        // Execution input templates
        private const val TASK_DESCRIPTION_TEMPLATE = "Execute task %s at position %d of %d"
        private const val OUTPUT_SCHEMA_STANDARD = "Standard task execution result with position tracking"
    }

    fun evaluate(input: ExecutionContractInput): ExecutionAuthorityResult {

        val reportId  = input.reportId
        val contracts = input.contracts

        // ---------- RULE 0: REPORT ID PRESENT ----------

        if (reportId.isBlank()) {
            return ExecutionAuthorityResult.Blocked("MISSING_REPORT_ID")
        }

        // ---------- GUARD: INCOMPLETE CONTRACT ----------

        if (contracts.isEmpty()) {
            return ExecutionAuthorityResult.Blocked("INCOMPLETE_CONTRACT")
        }

        // ---------- RULE 1: NON-EMPTY ----------

        if (contracts.isEmpty()) {
            return ExecutionAuthorityResult.Blocked("EMPTY_CONTRACTS")
        }

        // ---------- RULE 2: FIELD VALIDATION ----------

        for (contract in contracts) {

            if (contract.contractId.isBlank()) {
                return ExecutionAuthorityResult.Blocked("INVALID_FIELD")
            }

            if (contract.name.isBlank()) {
                return ExecutionAuthorityResult.Blocked("INVALID_FIELD")
            }

            if (contract.position <= 0) {
                return ExecutionAuthorityResult.Blocked("INVALID_FIELD")
            }

            if (contract.reportReference.isBlank()) {
                return ExecutionAuthorityResult.Blocked("MISSING_REPORT_REFERENCE")
            }

            if (contract.reportReference != reportId) {
                return ExecutionAuthorityResult.Blocked("REPORT_REFERENCE_MISMATCH")
            }
        }

        // ---------- RULE 3: POSITION SEQUENCE ----------

        val sorted = contracts.sortedBy { it.position }

        val expectedPositions = (1..contracts.size).toList()
        val actualPositions = sorted.map { it.position }

        if (expectedPositions != actualPositions) {
            return ExecutionAuthorityResult.Blocked("INVALID_POSITION_SEQUENCE")
        }

        // ---------- RULE 4: UNIQUE CONTRACT IDS ----------

        val ids = contracts.map { it.contractId }
        if (ids.size != ids.toSet().size) {
            return ExecutionAuthorityResult.Blocked("DUPLICATE_CONTRACT_ID")
        }

        // ---------- RULE 5: DETERMINISTIC ORDER ----------

        return ExecutionAuthorityResult.Approved(sorted)
    }

    /**
     * Authorize and execute contractor for a task assignment.
     * 
     * This method is the EXPLICIT INVOCATION path for contractor execution.
     * Called by ExecutionModule when processing TASK_ASSIGNED events.
     * 
     * Flow:
     * 1. Validate TaskAssignedContract (AERP-1)
     * 2. Resolve contractor profile
     * 3. Execute via ContractorExecutor
     * 4. Return ContractorResult
     * 
     * @param taskContract      The task assignment contract.
     * @param contractorProfile The assigned contractor's profile.
     * @return                  ContractorResult with execution outcome.
     */
    fun authorizeContractorExecution(
        taskContract: TaskAssignedContract,
        contractorProfile: ContractorProfile
    ): ContractorResult {
        
        // ---------- AERP-1 VALIDATION ----------
        
        // Validate task ID
        if (taskContract.taskId.isBlank()) {
            return ContractorResult(
                taskId = taskContract.taskId,
                contractorId = taskContract.contractorId,
                status = ExecutionStatus.FAILURE,
                artifact = emptyMap(),
                error = "INVALID_TASK_ID"
            )
        }
        
        // Validate contractor ID
        if (taskContract.contractorId.isBlank()) {
            return ContractorResult(
                taskId = taskContract.taskId,
                contractorId = taskContract.contractorId,
                status = ExecutionStatus.FAILURE,
                artifact = emptyMap(),
                error = "INVALID_CONTRACTOR_ID"
            )
        }
        
        // Validate position (1-indexed: contracts start at position 1)
        if (taskContract.position <= 0) {
            return ContractorResult(
                taskId = taskContract.taskId,
                contractorId = taskContract.contractorId,
                status = ExecutionStatus.FAILURE,
                artifact = emptyMap(),
                error = "INVALID_POSITION"
            )
        }
        
        // Validate total
        if (taskContract.total <= 0) {
            return ContractorResult(
                taskId = taskContract.taskId,
                contractorId = taskContract.contractorId,
                status = ExecutionStatus.FAILURE,
                artifact = emptyMap(),
                error = "INVALID_TOTAL"
            )
        }
        
        // Validate position is within bounds [1, total]
        if (taskContract.position > taskContract.total) {
            return ContractorResult(
                taskId = taskContract.taskId,
                contractorId = taskContract.contractorId,
                status = ExecutionStatus.FAILURE,
                artifact = emptyMap(),
                error = "POSITION_EXCEEDS_TOTAL"
            )
        }
        
        // Validate contractor capability
        if (contractorProfile.capabilities.capabilityScore <= 0) {
            return ContractorResult(
                taskId = taskContract.taskId,
                contractorId = taskContract.contractorId,
                status = ExecutionStatus.FAILURE,
                artifact = emptyMap(),
                error = "INSUFFICIENT_CAPABILITY"
            )
        }
        
        // ---------- EXECUTE VIA CONTRACTOR EXECUTOR ----------
        
        // Build execution input
        val executionInput = ContractorExecutionInput(
            taskId = taskContract.taskId,
            taskDescription = String.format(
                TASK_DESCRIPTION_TEMPLATE,
                taskContract.taskId,
                taskContract.position,
                taskContract.total
            ),
            taskPayload = mapOf(
                "position" to taskContract.position,
                "total" to taskContract.total,
                "reportReference" to (taskContract.reportReference ?: "")
            ),
            contractConstraints = emptyList(),
            expectedOutputSchema = OUTPUT_SCHEMA_STANDARD
        )
        
        // Execute
        val executionOutput = contractorExecutor.execute(executionInput, contractorProfile)
        
        // Convert to ContractorResult
        return ContractorResult(
            taskId = executionOutput.taskId,
            contractorId = contractorProfile.id,
            status = executionOutput.status,
            artifact = executionOutput.resultArtifact,
            error = executionOutput.error
        )
    }
}
