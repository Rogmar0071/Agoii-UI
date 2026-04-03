package com.agoii.mobile.execution

import com.agoii.mobile.core.EventLedger
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.core.Event

/**
 * ExecutionResolutionLayer — Single resolution point for ExecutionAuthority decisions.
 *
 * CONTRACT: AGOII–EXECUTION-RESOLUTION-LAYER-001
 *
 * RESPONSIBILITY:
 *   Translates ExecutionAuthorityExecutionResult into ledger events.
 *   Does NOT re-evaluate logic or reinterpret results.
 *   Provides exactly ONE deterministic emission path per result.
 *
 * ARCHITECTURE:
 *   ExecutionAuthority → DECIDES (pure, side-effect free)
 *   ResolutionLayer    → COMMITS (translates decision → event)
 *   Ledger             → STORES (persists events)
 *
 * RULES:
 *   1. SINGLE RESOLUTION POINT: All results pass through resolve()
 *   2. STRICT MAPPING: Blocked→TASK_BLOCKED, Executed→TASK_EXECUTED
 *   3. NO LOGIC LEAKAGE: Pure translation only
 *   4. FULL COVERAGE: All result types have exactly one emission path
 *   5. RECOVERY HOOK: FAILURE results trigger recovery decision
 */
class ExecutionResolutionLayer {

    companion object {
        /** Recovery source identifier for ExecutionAuthority-triggered recoveries. */
        private const val RECOVERY_SOURCE = "EXECUTION_AUTHORITY"
        
        /** Maximum delta recovery attempts before convergence ceiling. */
        private const val MAX_DELTA = 3
    }

    /**
     * Resolve ExecutionAuthorityExecutionResult into ledger events.
     *
     * CONTRACT: AGOII–EXECUTION-RESOLUTION-LAYER-001
     *
     * @param result    Execution result from ExecutionAuthority.
     * @param projectId Project identifier.
     * @param ledger    Event ledger for emission.
     * @param events    Event history for recovery decision context.
     * @return Resolution outcome (for diagnostic purposes).
     */
    fun resolve(
        result:    ExecutionAuthorityExecutionResult,
        projectId: String,
        ledger:    EventLedger,
        events:    List<Event>
    ): ResolutionOutcome {
        return when (result) {
            is ExecutionAuthorityExecutionResult.Executed -> {
                resolveExecuted(result, projectId, ledger, events)
            }
            is ExecutionAuthorityExecutionResult.Blocked -> {
                resolveBlocked(result, projectId, ledger)
            }
            ExecutionAuthorityExecutionResult.IcsCompleted -> {
                // ICS_COMPLETED event already handled by ExecutionAuthority
                // No additional emission needed
                ResolutionOutcome.IcsCompleted
            }
            ExecutionAuthorityExecutionResult.NotTriggered -> {
                // No action needed - execution was not triggered
                ResolutionOutcome.NotTriggered
            }
        }
    }

    /**
     * Resolve Executed result → TASK_EXECUTED event.
     *
     * RULE 2: Strict mapping
     * RULE 5: Recovery hook for FAILURE
     */
    private fun resolveExecuted(
        result:    ExecutionAuthorityExecutionResult.Executed,
        projectId: String,
        ledger:    EventLedger,
        events:    List<Event>
    ): ResolutionOutcome {
        val status = result.executionStatus
        
        // Emit TASK_EXECUTED event
        val eventPayload = mutableMapOf<String, Any>(
            "taskId" to result.taskId,
            "contractId" to result.taskId, // Use taskId as contractId for now
            "executionStatus" to status.name
        )
        
        // Add artifact and validated sections for SUCCESS
        if (status == ExecutionStatus.SUCCESS && result.report != null) {
            // TODO: Extract artifact from report when available
            // For now, just record SUCCESS without artifact payload
        }
        
        ledger.appendEvent(
            projectId,
            EventTypes.TASK_EXECUTED,
            eventPayload
        )
        
        // RULE 5: Recovery hook for FAILURE
        if (status == ExecutionStatus.FAILURE) {
            return resolveFailureRecovery(result.taskId, projectId, ledger, events)
        }
        
        return ResolutionOutcome.Success
    }

    /**
     * Resolve Blocked result → TASK_BLOCKED event.
     *
     * RULE 2: Strict mapping
     * RULE 3: No logic leakage - direct translation
     */
    private fun resolveBlocked(
        result:    ExecutionAuthorityExecutionResult.Blocked,
        projectId: String,
        ledger:    EventLedger
    ): ResolutionOutcome {
        // Note: TASK_BLOCKED is not in EventTypes yet
        // For now, emit TASK_EXECUTED(FAILURE) with reason
        ledger.appendEvent(
            projectId,
            EventTypes.TASK_EXECUTED,
            mapOf(
                "executionStatus" to ExecutionStatus.FAILURE.name,
                "reason" to result.reason,
                "blocked" to true
            )
        )
        
        return ResolutionOutcome.Blocked(result.reason)
    }

    /**
     * Resolve FAILURE recovery decision.
     *
     * RULE 5: Recovery hook based strictly on result
     *
     * Decision logic:
     *   - If recoveryCount < MAX_DELTA → emit RECOVERY_CONTRACT
     *   - If recoveryCount >= MAX_DELTA → emit CONTRACT_FAILED
     */
    private fun resolveFailureRecovery(
        contractId: String,
        projectId:  String,
        ledger:     EventLedger,
        events:     List<Event>
    ): ResolutionOutcome {
        // Check convergence ceiling
        val recoveryCount = countRecoveryAttempts(events, contractId)
        
        if (recoveryCount >= MAX_DELTA) {
            // Emit CONTRACT_FAILED (convergence ceiling reached)
            ledger.appendEvent(
                projectId,
                EventTypes.CONTRACT_FAILED,
                mapOf(
                    "contractId" to contractId,
                    "reason" to "NON_CONVERGENT_SYSTEM",
                    "recoveryAttempts" to recoveryCount,
                    "maxDelta" to MAX_DELTA
                )
            )
            return ResolutionOutcome.ConvergenceCeiling(contractId, recoveryCount)
        }
        
        // Emit RECOVERY_CONTRACT
        val recoveryId = deriveRecoveryId(projectId, contractId, recoveryCount)
        val reportReference = extractReportReference(events, contractId)
        
        ledger.appendEvent(
            projectId,
            EventTypes.RECOVERY_CONTRACT,
            mapOf(
                "recoveryId" to recoveryId,
                "contractId" to contractId,
                "taskId" to contractId,
                "report_reference" to reportReference,
                "failureClass" to "VALIDATION_FAILURE",
                "violationField" to "unknown",
                "source" to RECOVERY_SOURCE
            )
        )
        
        return ResolutionOutcome.RecoveryTriggered(contractId, recoveryCount + 1)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HELPER FUNCTIONS (Pure utility - no side effects)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Count RECOVERY_CONTRACT events for given contractId.
     */
    private fun countRecoveryAttempts(events: List<Event>, contractId: String): Int =
        events.count { event ->
            event.type == EventTypes.RECOVERY_CONTRACT &&
            event.payload["contractId"]?.toString() == contractId
        }

    /**
     * Derive deterministic recovery ID.
     */
    private fun deriveRecoveryId(projectId: String, contractId: String, attempt: Int): String =
        "RCF::$projectId::$contractId::attempt_$attempt"

    /**
     * Extract report_reference from CONTRACT_CREATED or CONTRACTS_GENERATED.
     */
    private fun extractReportReference(events: List<Event>, contractId: String): String {
        val contractCreated = events.firstOrNull { event ->
            event.type == EventTypes.CONTRACT_CREATED &&
            event.payload["contractId"]?.toString() == contractId
        }
        if (contractCreated != null) {
            return contractCreated.payload["report_reference"]?.toString() ?: ""
        }

        val contractsGenerated = events.firstOrNull { it.type == EventTypes.CONTRACTS_GENERATED }
        return contractsGenerated?.payload?.get("report_reference")?.toString() ?: ""
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// RESOLUTION OUTCOME
// ══════════════════════════════════════════════════════════════════════════════

/**
 * Resolution outcome for diagnostic and monitoring purposes.
 * Does NOT affect control flow - purely informational.
 */
sealed class ResolutionOutcome {
    /** TASK_EXECUTED(SUCCESS) emitted. */
    object Success : ResolutionOutcome()
    
    /** TASK_EXECUTED(FAILURE) + RECOVERY_CONTRACT emitted. */
    data class RecoveryTriggered(
        val contractId: String,
        val attemptNumber: Int
    ) : ResolutionOutcome()
    
    /** TASK_EXECUTED(FAILURE) + CONTRACT_FAILED emitted. */
    data class ConvergenceCeiling(
        val contractId: String,
        val finalAttempts: Int
    ) : ResolutionOutcome()
    
    /** TASK_EXECUTED(FAILURE) with blocked=true emitted. */
    data class Blocked(
        val reason: String
    ) : ResolutionOutcome()
    
    /** ICS_COMPLETED - no emission needed. */
    object IcsCompleted : ResolutionOutcome()
    
    /** NotTriggered - no emission needed. */
    object NotTriggered : ResolutionOutcome()
}
