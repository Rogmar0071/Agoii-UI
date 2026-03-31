package com.agoii.mobile.contractor

import com.agoii.mobile.contractors.DeterministicMatchingEngine
import com.agoii.mobile.contractors.ExecutionContract as MatchingContract
import com.agoii.mobile.contractors.ResolutionTrace
import com.agoii.mobile.contracts.ContractCapability
import com.agoii.mobile.contracts.ExecutionType
import com.agoii.mobile.contracts.TargetDomain
import com.agoii.mobile.execution.ContractorExecutionInput
import com.agoii.mobile.execution.ContractorExecutionOutput
import com.agoii.mobile.execution.ContractorExecutor
import com.agoii.mobile.execution.DriverRegistry
import com.agoii.mobile.execution.ExecutionStatus

// ─── Result ───────────────────────────────────────────────────────────────────

sealed class ContractorSystemResult {

    data class Resolved(
        val assignment:      com.agoii.mobile.contractors.Assignment,
        val executionOutput: ContractorExecutionOutput,
        val trace:           ResolutionTrace,
        val executionType:   ExecutionType,
        val targetDomain:    TargetDomain
    ) : ContractorSystemResult()

    data class Blocked(
        val reason: String,
        val trace:  ResolutionTrace
    ) : ContractorSystemResult()
}

// ─── ContractorSystem ─────────────────────────────────────────────────────────

class ContractorSystem(
    private val matchingEngine: DeterministicMatchingEngine = DeterministicMatchingEngine(),
    private val driverRegistry: DriverRegistry
) {

    // ✅ SINGLE SOURCE OF TRUTH
    private val executor = ContractorExecutor(driverRegistry)

    fun execute(
        taskId:                String,
        contractId:            String,
        reportReference:       String,
        position:              Int,
        constraints:           List<String>,
        expectedOutput:        String,
        taskPayload:           Map<String, Any>,
        requiredCapabilities:  List<ContractCapability>,
        executionType:         ExecutionType,
        targetDomain:          TargetDomain,
        registry:              ContractorRegistry
    ): ContractorSystemResult {

        val matchContract = MatchingContract(
            contractId      = contractId,
            reportReference = reportReference,
            position        = position.toString()
        )

        val assigned = matchingEngine.resolve(
            matchContract,
            requiredCapabilities,
            registry
        )

        if (assigned.assignment.mode ==
            com.agoii.mobile.contractors.AssignmentMode.BLOCKED
        ) {
            return ContractorSystemResult.Blocked(
                reason = "NO_FEASIBLE_CONTRACTOR:${executionType.name}",
                trace  = assigned.trace
            )
        }

        val contractorIds = assigned.assignment.contractorIds
        if (contractorIds.isEmpty()) {
            return ContractorSystemResult.Blocked(
                reason = "MATCHING_RETURNED_EMPTY_CONTRACTORS",
                trace  = assigned.trace
            )
        }

        val profiles = contractorIds.mapNotNull { id ->
            registry.allVerified().find { it.id == id }
        }

        if (profiles.isEmpty()) {
            return ContractorSystemResult.Blocked(
                reason = "CONTRACTOR_PROFILES_NOT_FOUND",
                trace  = assigned.trace
            )
        }

        val primaryProfile = profiles.first()

        val input = ContractorExecutionInput(
            taskId               = taskId,
            taskDescription      = expectedOutput,
            taskPayload          = taskPayload,
            contractConstraints  = constraints,
            expectedOutputSchema = expectedOutput
        )

        val rawOutput = executor.execute(input, primaryProfile)

        if (rawOutput.status != ExecutionStatus.SUCCESS) {
            return ContractorSystemResult.Blocked(
                reason = rawOutput.error ?: "EXECUTION_FAILED",
                trace  = assigned.trace
            )
        }

        return ContractorSystemResult.Resolved(
            assignment      = assigned.assignment,
            executionOutput = rawOutput,
            trace           = assigned.trace,
            executionType   = executionType,
            targetDomain    = targetDomain
        )
    }
}
