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

class ExecutionAuthority(
    private val contractorExecutor: ContractorExecutor,
    private val resultValidator: ResultValidator
) {

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

    // ---------- CONTRACTOR EXECUTION (RE-ENTRY) ----------

    /**
     * Re-entry point for Execution Authority after Governor emits TASK_ASSIGNED.
     *
     * This is the ONLY method allowed to authorize and invoke contractor execution.
     *
     * VALIDATION (AERP-1):
     *  - TaskAssignedContract structure
     *  - contractor profile validity
     *  - report_reference persistence (RRIL-1)
     *
     * EXECUTION:
     *  - invoke ContractorExecutor (subordinate module)
     *  - validate result
     *  - produce ContractorResult (Authorized or Blocked)
     *
     * FAILURE HANDLING (RCF-1):
     *  - ALL failures produce RecoveryContract
     *  - NO retries, NO silent failures
     *
     * @param contract TaskAssignedContract extracted from ledger
     * @param contractor Contractor profile resolved from registry
     * @return ContractorResult (Authorized or Blocked)
     */
    fun authorizeContractorExecution(
        contract: TaskAssignedContract,
        contractor: com.agoii.mobile.contractor.ContractorProfile
    ): ContractorResult {

        // ---------- AERP-1 VALIDATION: STRUCTURE ----------

        if (contract.taskId.isBlank()) {
            return ContractorResult.Blocked(
                taskId = contract.taskId,
                contractorId = contract.contractorId,
                reportReference = contract.reportReference,
                reason = "INVALID_TASK_ID",
                stage = "STRUCTURE"
            )
        }

        if (contract.contractorId.isBlank()) {
            return ContractorResult.Blocked(
                taskId = contract.taskId,
                contractorId = contract.contractorId,
                reportReference = contract.reportReference,
                reason = "INVALID_CONTRACTOR_ID",
                stage = "STRUCTURE"
            )
        }

        if (contract.reportReference.isBlank()) {
            return ContractorResult.Blocked(
                taskId = contract.taskId,
                contractorId = contract.contractorId,
                reportReference = contract.reportReference,
                reason = "MISSING_REPORT_REFERENCE",
                stage = "STRUCTURE"
            )
        }

        if (contract.position <= 0) {
            return ContractorResult.Blocked(
                taskId = contract.taskId,
                contractorId = contract.contractorId,
                reportReference = contract.reportReference,
                reason = "INVALID_POSITION",
                stage = "STRUCTURE"
            )
        }

        if (contract.total <= 0) {
            return ContractorResult.Blocked(
                taskId = contract.taskId,
                contractorId = contract.contractorId,
                reportReference = contract.reportReference,
                reason = "INVALID_TOTAL",
                stage = "STRUCTURE"
            )
        }

        // ---------- AERP-1 VALIDATION: AUTHORIZATION ----------

        if (contractor.id != contract.contractorId) {
            return ContractorResult.Blocked(
                taskId = contract.taskId,
                contractorId = contract.contractorId,
                reportReference = contract.reportReference,
                reason = "CONTRACTOR_ID_MISMATCH",
                stage = "AUTHORIZATION"
            )
        }

        if (contractor.capabilities.capabilityScore <= 0.0) {
            return ContractorResult.Blocked(
                taskId = contract.taskId,
                contractorId = contract.contractorId,
                reportReference = contract.reportReference,
                reason = "ZERO_CAPABILITY_SCORE",
                stage = "AUTHORIZATION"
            )
        }

        // ---------- EXECUTION ----------

        val executionInput = ContractorExecutionInput(
            taskId = contract.taskId,
            taskDescription = "Task ${contract.taskId} - Contractor ${contract.contractorId} - Position ${contract.position}/${contract.total}",
            taskPayload = mapOf(
                "position" to contract.position,
                "total" to contract.total,
                "reportReference" to contract.reportReference
            ),
            contractConstraints = listOf("standard"),
            expectedOutputSchema = "executionArtifact"
        )

        val executionOutput = try {
            contractorExecutor.execute(executionInput, contractor)
        } catch (e: Exception) {
            return ContractorResult.Blocked(
                taskId = contract.taskId,
                contractorId = contract.contractorId,
                reportReference = contract.reportReference,
                reason = "EXECUTION_EXCEPTION: ${e.message}",
                stage = "EXECUTION"
            )
        }

        // ---------- VALIDATION ----------

        val validationResult = resultValidator.validateOutput(
            output = executionOutput.resultArtifact,
            expectedSchema = "executionArtifact"
        )

        val validationPassed = validationResult.verdict == "VALID"

        // ---------- RETURN RESULT ----------

        return ContractorResult.Authorized(
            taskId = contract.taskId,
            contractorId = contract.contractorId,
            reportReference = contract.reportReference,
            executionOutput = executionOutput,
            validationPassed = validationPassed
        )
    }
}
