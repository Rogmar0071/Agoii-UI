package com.agoii.mobile.contracts

import com.agoii.mobile.contractors.ResolutionTrace
import com.agoii.mobile.execution.AnchorState
import com.agoii.mobile.execution.ContractorExecutionOutput

class UniversalContractReport {

    fun generateReport(
        contract:        UniversalContract,
        taskId:          String,
        contractorId:    String,
        executionOutput: ContractorExecutionOutput,
        trace:           ResolutionTrace
    ): ContractReport {

        val artifact = executionOutput.resultArtifact

        return ContractReport(
            reportReference    = contract.reportReference,
            typeInventory      = artifact.keys.toList(),
            functionSignatures = listOf(contract.contractId),
            logicFlow          = UCS1_PIPELINE_STEPS,
            errorConditions    = listOfNotNull(executionOutput.error),
            traceStructure     = trace,
            rawOutput          = artifact["response"]?.toString() ?: executionOutput.error ?: "",
            normalizedOutput   = if (executionOutput.error == null)
                artifact["response"]?.toString()
            else null,
            exitCode           = if (executionOutput.error == null) 0 else 1,
            failureSurface     = listOfNotNull(executionOutput.error),
            policyViolations   = emptyList()
        )
    }

    fun extractAnchorState(report: ContractReport): AnchorState =
        AnchorState(
            reportReference    = report.reportReference,
            validatedTypes     = report.typeInventory.toList(),
            validatedStructure = report.typeInventory.toSet(),
            validatedPaths     = report.logicFlow.toList()
        )

    companion object {
        private val UCS1_PIPELINE_STEPS = listOf(
            "UCS1_VALIDATED",
            "UCS1_NORMALIZED",
            "UCS1_ENFORCED",
            "UCS1_ROUTED",
            "MATCHING_RESOLVED",
            "EXECUTION_INVOKED",
            "ARTIFACT_PRODUCED"
        )
    }
}
