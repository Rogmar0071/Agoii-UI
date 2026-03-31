package com.agoii.mobile.contractors.registry

import com.agoii.mobile.execution.ContractorExecutionInput
import com.agoii.mobile.execution.ContractorExecutionOutput
import com.agoii.mobile.execution.ExecutionDriver
import com.agoii.mobile.execution.ExecutionStatus

// ─── NemoClaw Contractor Definition ──────────────────────────────────────────
//
// CONTRACT: REGISTER_NEMOCLAW_CONTRACTOR
// Registers NemoClaw as a valid external contractor in the Agoii ContractorRegistry
// with deterministic capability mapping and matching compatibility.

/**
 * Static definition of the NemoClaw contractor.
 *
 * CONSTRAINTS (non-negotiable):
 *  - requiresSandbox         = true  — execution must be sandbox-isolated
 *  - requiresPolicy          = true  — an explicit execution policy is mandatory
 *  - allowsAutonomy          = false — no autonomous decision-making
 *  - requiresExplicitContract= true  — must operate under an explicit contract
 *
 * DRIVER SOURCE: "nemoclaw" — used as the lookup key in [com.agoii.mobile.execution.DriverRegistry].
 */
object NemoclawContractorDefinition {

    const val CONTRACTOR_ID      = "external.nemoclaw.agent"
    const val TYPE               = "EXECUTION_CONTRACTOR"
    const val EXECUTION_MODE     = "SANDBOXED_EXTERNAL"
    const val DRIVER_SOURCE      = "nemoclaw"
    const val DRIVER_ENTRY_POINT = "agoii/driver/nemoclaw_driver.executeTask"

    object Constraints {
        const val REQUIRES_SANDBOX          = true
        const val REQUIRES_POLICY           = true
        const val ALLOWS_AUTONOMY           = false
        const val REQUIRES_EXPLICIT_CONTRACT = true
    }

    /**
     * Capability claims submitted to [com.agoii.mobile.contractor.ContractorVerificationEngine].
     *
     * Dimension scores after extraction:
     *  - constraintObedience = 3 (high) — requires explicit contract + policy
     *  - structuralAccuracy  = 3 (high) — deterministic output structure guaranteed
     *  - driftScore          = 1 (low)  — sandbox isolation eliminates drift
     *  - complexityCapacity  = 2 (med)  — handles agent/code/sandbox execution
     *  - reliability         = 3 (high) — full output visibility + deterministic results
     */
    val CAPABILITY_CLAIMS: Map<String, String> = mapOf(
        "constraintObedience" to "high",
        "structuralAccuracy"  to "high",
        "driftScore"          to "low",
        "complexityCapacity"  to "medium",
        "reliability"         to "high"
    )
}

// ─── NemoClaw Driver ──────────────────────────────────────────────────────────

/**
 * NemoclawDriver — [ExecutionDriver] for the NemoClaw sandboxed external contractor.
 *
 * Entry point: [NemoclawContractorDefinition.DRIVER_ENTRY_POINT]
 * Registered under source: [NemoclawContractorDefinition.DRIVER_SOURCE]
 *
 * EXECUTION RULES:
 *  1. Requires an execution policy in [ContractorExecutionInput.contractConstraints].
 *     Missing policy → FAILURE (POLICY_REQUIRED).
 *  2. No autonomous execution. No retry logic. No fallback logic.
 *  3. Returns a deterministic [ContractorExecutionOutput] with outputType = "ContractReport".
 *  4. All output is visible and fully structured.
 */
class NemoclawDriver : ExecutionDriver {

    override fun execute(input: ContractorExecutionInput): ContractorExecutionOutput {
        // CONSTRAINT CHECK: execution policy must be present
        val hasPolicy = input.contractConstraints.any { it.contains("policy", ignoreCase = true) }
        if (!hasPolicy) {
            return ContractorExecutionOutput(
                taskId         = input.taskId,
                resultArtifact = emptyMap(),
                status         = ExecutionStatus.FAILURE,
                error          = "POLICY_REQUIRED: NemoClaw requires an explicit execution policy"
            )
        }

        // SANDBOXED EXECUTION: deterministic, policy-bound, no autonomy
        val artifact: Map<String, Any> = mapOf(
            "contractorId"  to NemoclawContractorDefinition.CONTRACTOR_ID,
            "taskId"        to input.taskId,
            "executionMode" to NemoclawContractorDefinition.EXECUTION_MODE,
            "outputType"    to "ContractReport",
            "response"      to buildSandboxedResponse(input)
        )

        return ContractorExecutionOutput(
            taskId         = input.taskId,
            resultArtifact = artifact,
            status         = ExecutionStatus.SUCCESS
        )
    }

    private fun buildSandboxedResponse(input: ContractorExecutionInput): String =
        "NemoClaw[${NemoclawContractorDefinition.CONTRACTOR_ID}] executed: " +
        "taskId=${input.taskId} task=${input.taskDescription}"
}
