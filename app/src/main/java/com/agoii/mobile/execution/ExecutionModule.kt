// AGOII CONTRACT — EXECUTION MODULE COMPLETION PASS 3
// CLASSIFICATION:
// - Class: Structural
// - Reversibility: Forward-only
// - Execution Scope: Deterministic
// - Mutation Authority: Tier A
//
// PURPOSE:
// Complete the Execution Module as a CLOSED SYSTEM that receives state,
// evaluates execution stage, performs required execution, and returns control.
//
// FLOW:
// EventLedger → Governor → TASK_ASSIGNED → ExecutionModule → ExecutionAuthority → ContractorExecutor → RESULT EVENT
//
// PROHIBITIONS:
// - NO orchestration
// - NO event listeners
// - NO external triggers
// - NO async processing

package com.agoii.mobile.execution

import com.agoii.mobile.contractor.ContractorCapabilityVector
import com.agoii.mobile.contractor.ContractorProfile
import com.agoii.mobile.contractor.ContractorRegistry
import com.agoii.mobile.contractor.VerificationStatus
import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventRepository
import com.agoii.mobile.core.EventTypes

// ─── ContractorResult ─────────────────────────────────────────────────────

/**
 * Result of contractor execution.
 * 
 * @property taskId         The task identifier that was executed.
 * @property contractorId   The contractor that performed the execution.
 * @property status         SUCCESS or FAILURE.
 * @property artifact       The execution result data.
 * @property error          Optional error message when status is FAILURE.
 */
data class ContractorResult(
    val taskId: String,
    val contractorId: String,
    val status: ExecutionStatus,
    val artifact: Map<String, Any>,
    val error: String? = null
)

/**
 * TaskAssignedContract — input contract for contractor execution.
 * 
 * Represents a task that has been assigned by Governor and is ready for execution.
 */
data class TaskAssignedContract(
    val taskId: String,
    val contractorId: String,
    val position: Int,
    val total: Int,
    val reportReference: String?
)

// ─── ExecutionModule ──────────────────────────────────────────────────────

/**
 * ExecutionModule — CLOSED SYSTEM for contractor execution.
 * 
 * Responsibilities:
 * 1. Accept current state (event)
 * 2. Perform deterministic branch: IF event.type == TASK_ASSIGNED THEN execute
 * 3. Invoke ExecutionAuthority with TaskAssignedContract
 * 4. Receive ContractorResult
 * 5. Append RESULT event to ledger
 * 
 * Rules:
 * - NO orchestration
 * - NO event listeners
 * - NO external triggers
 * - Direct function call only
 * - ExecutionAuthority is called explicitly
 */
class ExecutionModule(
    private val ledger: EventRepository,
    private val registry: ContractorRegistry
) {

    private val executionAuthority = ExecutionAuthority()
    private val contractorExecutor = ContractorExecutor()

    /**
     * Process current state and execute if TASK_ASSIGNED.
     * 
     * This is the ONLY entry point for contractor execution.
     * Called directly after Governor emits TASK_ASSIGNED.
     * 
     * @param projectId The project identifier.
     * @param event     The current event (must be TASK_ASSIGNED).
     * @return          ContractorResult if execution occurred, null otherwise.
     */
    fun processState(projectId: String, event: Event): ContractorResult? {
        // Deterministic branch: only process TASK_ASSIGNED
        if (event.type != EventTypes.TASK_ASSIGNED) {
            return null
        }

        // Extract task assignment data
        val taskId = event.payload["taskId"] as? String ?: return null
        val contractorId = event.payload["contractorId"] as? String ?: return null
        val position = resolveInt(event.payload["position"]) ?: return null
        val total = resolveInt(event.payload["total"]) ?: return null
        val reportReference = event.payload["report_reference"] as? String

        // Build TaskAssignedContract
        val taskContract = TaskAssignedContract(
            taskId = taskId,
            contractorId = contractorId,
            position = position,
            total = total,
            reportReference = reportReference
        )

        // Invoke ExecutionAuthority
        val result = executionAuthority.authorizeContractorExecution(
            taskContract = taskContract,
            contractorProfile = resolveContractor(contractorId)
        )

        // Append RESULT event to ledger
        appendResultEvent(projectId, result)

        return result
    }

    /**
     * Resolve contractor from registry.
     */
    private fun resolveContractor(contractorId: String): ContractorProfile {
        // Find contractor in registry or return default
        val contractor = registry.allVerified().firstOrNull { it.id == contractorId }
        
        return contractor ?: ContractorProfile(
            id = contractorId,
            capabilities = ContractorCapabilityVector(
                constraintObedience = 2,
                structuralAccuracy = 2,
                driftScore = 1,
                complexityCapacity = 2,
                reliability = 2
            ),
            verificationCount = 0,
            successCount = 0,
            failureCount = 0,
            status = VerificationStatus.VERIFIED,
            source = "default",
            notes = listOf("Default contractor profile")
        )
    }

    /**
     * Append result event to ledger.
     */
    private fun appendResultEvent(projectId: String, result: ContractorResult) {
        val eventType = when (result.status) {
            ExecutionStatus.SUCCESS -> EventTypes.TASK_COMPLETED
            ExecutionStatus.FAILURE -> EventTypes.TASK_FAILED
        }

        val payload = mutableMapOf<String, Any>(
            "taskId" to result.taskId,
            "contractorId" to result.contractorId,
            "status" to result.status.name
        )

        if (result.error != null) {
            payload["error"] = result.error
        }

        if (result.artifact.isNotEmpty()) {
            payload["artifact"] = result.artifact
        }

        ledger.appendEvent(projectId, eventType, payload)
    }

    /**
     * Resolve int from various numeric types.
     */
    private fun resolveInt(value: Any?): Int? = when (value) {
        is Int -> value
        is Double -> value.toInt()
        is Long -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }
}
