// AGOII CONTRACT — CONTRACTOR INVOCATION LAYER
// CLASSIFICATION:
// - Class: Structural
// - Reversibility: Forward-only
// - Execution Scope: Both
// - Mutation Authority: Tier C
//
// PURPOSE:
// Subordinate layer that processes TASK_ASSIGNED events and invokes contractors
// through Execution Authority. This is NOT an orchestration layer - it is a
// deterministic state-driven execution unit.
//
// RULES:
// - ONLY processes TaskAssignedContract from ledger
// - ONLY invokes through Execution Authority
// - NO decision logic
// - NO state progression
// - NO governor interaction

package com.agoii.mobile.execution

import com.agoii.mobile.contractor.ContractorProfile
import com.agoii.mobile.contractor.ContractorRegistry
import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventLedger
import com.agoii.mobile.core.EventTypes

/**
 * ContractorInvocationLayer — deterministic contractor execution unit.
 *
 * This is a SUBORDINATE module to Execution Authority. It reads TASK_ASSIGNED
 * events from the ledger, resolves contractors, and invokes Execution Authority
 * to authorize and execute.
 *
 * PROHIBITED:
 *  - orchestration logic
 *  - decision making
 *  - state progression
 *  - governor calls
 *  - event listeners
 *  - polling mechanisms
 *
 * PERMITTED:
 *  - read ledger state
 *  - resolve contractor from registry
 *  - invoke Execution Authority
 *  - append result events to ledger
 */
class ContractorInvocationLayer(
    private val ledger: EventLedger,
    private val executionAuthority: ExecutionAuthority,
    private val contractorRegistry: ContractorRegistry
) {

    /**
     * Process a TASK_ASSIGNED event and invoke contractor through Execution Authority.
     *
     * This is the ONLY method that triggers contractor execution in the system.
     *
     * FLOW (locked):
     *  1. Extract TaskAssignedContract from event payload (AERP-1 validation)
     *  2. Resolve contractor from registry
     *  3. Invoke Execution Authority.authorizeContractorExecution()
     *  4. Append result event to ledger
     *
     * DETERMINISM:
     *  - contractor selection is deterministic (from TASK_ASSIGNED event)
     *  - execution is deterministic (through Execution Authority)
     *  - result handling is deterministic (event append)
     *
     * @param projectId Project ledger identifier
     * @param taskAssignedEvent TASK_ASSIGNED event from ledger
     * @return ContractorResult (Authorized or Blocked)
     */
    fun processTaskAssignment(
        projectId: String,
        taskAssignedEvent: Event
    ): ContractorResult {

        // ---------- STEP 1: EXTRACT CONTRACT ----------

        val payload = taskAssignedEvent.payload

        val taskId = payload["taskId"] as? String
        if (taskId.isNullOrBlank()) {
            return createBlockedResult(
                taskId = "",
                contractorId = "",
                reportReference = "",
                reason = "MISSING_TASK_ID",
                stage = "EXTRACTION"
            )
        }

        val contractorId = payload["contractorId"] as? String
        if (contractorId.isNullOrBlank()) {
            return createBlockedResult(
                taskId = taskId,
                contractorId = "",
                reportReference = "",
                reason = "MISSING_CONTRACTOR_ID",
                stage = "EXTRACTION"
            )
        }

        val position = resolveInt(payload["position"])
        if (position == null || position <= 0) {
            return createBlockedResult(
                taskId = taskId,
                contractorId = contractorId,
                reportReference = "",
                reason = "INVALID_POSITION",
                stage = "EXTRACTION"
            )
        }

        val total = resolveInt(payload["total"])
        if (total == null || total <= 0) {
            return createBlockedResult(
                taskId = taskId,
                contractorId = contractorId,
                reportReference = "",
                reason = "INVALID_TOTAL",
                stage = "EXTRACTION"
            )
        }

        // ---------- STEP 2: DERIVE REPORT REFERENCE (RRIL-1) ----------

        val reportReference = deriveReportReference(projectId)
        if (reportReference.isNullOrBlank()) {
            return createBlockedResult(
                taskId = taskId,
                contractorId = contractorId,
                reportReference = "",
                reason = "MISSING_REPORT_REFERENCE",
                stage = "EXTRACTION"
            )
        }

        val contract = TaskAssignedContract(
            taskId = taskId,
            contractorId = contractorId,
            reportReference = reportReference,
            position = position,
            total = total
        )

        // ---------- STEP 3: RESOLVE CONTRACTOR ----------

        val contractor = resolveContractor(contractorId)
        if (contractor == null) {
            return createBlockedResult(
                taskId = taskId,
                contractorId = contractorId,
                reportReference = reportReference,
                reason = "CONTRACTOR_NOT_FOUND",
                stage = "RESOLUTION"
            )
        }

        // ---------- STEP 4: INVOKE EXECUTION AUTHORITY ----------

        val result = executionAuthority.authorizeContractorExecution(contract, contractor)

        // ---------- STEP 5: APPEND RESULT EVENT ----------

        appendResultEvent(projectId, result)

        return result
    }

    // ---------- HELPERS ----------

    /**
     * Resolve contractor from registry by ID.
     *
     * This is deterministic - contractor selection was made by Governor
     * when TASK_ASSIGNED was emitted.
     */
    private fun resolveContractor(contractorId: String): ContractorProfile? {
        return contractorRegistry.findById(contractorId)
    }

    /**
     * Derive report_reference from CONTRACTS_GENERATED event (RRIL-1).
     *
     * This enforces RRIL-1: report_reference originates from ExecutionEntryPoint
     * and MUST persist across all contracts.
     */
    private fun deriveReportReference(projectId: String): String? {
        val events = ledger.loadEvents(projectId)
        val contractsGenerated = events.firstOrNull { it.type == EventTypes.CONTRACTS_GENERATED }
            ?: return null
        return contractsGenerated.payload["report_id"] as? String
    }

    /**
     * Append result event to ledger based on ContractorResult type.
     *
     * AUTHORIZED → TASK_STARTED
     * BLOCKED → Recovery Contract (RCF-1)
     */
    private fun appendResultEvent(projectId: String, result: ContractorResult) {
        when (result) {
            is ContractorResult.Authorized -> {
                val payload = mapOf(
                    "taskId" to result.taskId,
                    "contractorId" to result.contractorId,
                    "reportReference" to result.reportReference,
                    "validationPassed" to result.validationPassed,
                    "position" to result.executionOutput.resultArtifact["position"],
                    "total" to result.executionOutput.resultArtifact["total"]
                )
                ledger.appendEvent(projectId, EventTypes.TASK_STARTED, payload)
            }
            is ContractorResult.Blocked -> {
                // RCF-1: Generate Recovery Contract
                val recoveryPayload = mapOf(
                    "taskId" to result.taskId,
                    "contractorId" to result.contractorId,
                    "reportReference" to result.reportReference,
                    "reason" to result.reason,
                    "stage" to result.stage,
                    "recoveryAction" to "REASSIGN"
                )
                ledger.appendEvent(projectId, EventTypes.TASK_FAILED, recoveryPayload)
            }
        }
    }

    /**
     * Create a Blocked result for early failures.
     */
    private fun createBlockedResult(
        taskId: String,
        contractorId: String,
        reportReference: String,
        reason: String,
        stage: String
    ): ContractorResult.Blocked {
        return ContractorResult.Blocked(
            taskId = taskId,
            contractorId = contractorId,
            reportReference = reportReference,
            reason = reason,
            stage = stage
        )
    }

    /**
     * Resolve numeric values from event payload (supports Double/Int/Long/String).
     */
    private fun resolveInt(value: Any?): Int? = when (value) {
        is Int    -> value
        is Double -> value.toInt()
        is Long   -> value.toInt()
        is String -> value.toIntOrNull()
        else      -> null
    }
}
