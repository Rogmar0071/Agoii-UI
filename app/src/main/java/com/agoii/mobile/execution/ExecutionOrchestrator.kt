package com.agoii.mobile.execution

import com.agoii.mobile.contractor.ContractorProfile
import com.agoii.mobile.core.LedgerValidationException

/**
 * ExecutionOrchestrator — DEPRECATED / NEUTRALIZED
 *
 * This class no longer performs execution.
 * All execution MUST go through ExecutionAuthority.
 *
 * Any attempt to use this class will BLOCK deterministically.
 */
@Deprecated(
    message = "ExecutionOrchestrator is deprecated. Use ExecutionAuthority.",
    level = DeprecationLevel.ERROR
)
class ExecutionOrchestrator(
    private val driverRegistry: DriverRegistry
) {

    fun execute(
        input: ContractorExecutionInput,
        contractor: ContractorProfile
    ): ContractorExecutionOutput {
        throw LedgerValidationException(
            "EXECUTION BLOCKED: ExecutionOrchestrator is disabled. Use ExecutionAuthority."
        )
    }
}
