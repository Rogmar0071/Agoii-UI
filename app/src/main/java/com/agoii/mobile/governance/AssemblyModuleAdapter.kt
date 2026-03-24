package com.agoii.mobile.governance

import com.agoii.mobile.assembly.AssemblyResult

// ─── AssemblyModuleAdapter — Assembly Module Structural State ─────────────────

/**
 * AssemblyModuleAdapter — exposes the full structural state of the assembly module
 * after an [com.agoii.mobile.assembly.AssemblyValidator] pass.
 *
 * The Governor queries this adapter before appending [com.agoii.mobile.core.EventTypes.ASSEMBLY_VALIDATED].
 * Validation is complete only when [AssemblyResult.isValid] is true.
 *
 * @property result The full assembly validation result from [AssemblyValidator].
 */
class AssemblyModuleAdapter(
    private val result: AssemblyResult
) : ModuleState {

    override fun getStateSignature(): Map<String, Any> = mapOf(
        "assemblyValid"      to result.isValid,
        "completionStatus"   to result.completionStatus,
        "missingElements"    to result.missingElements,
        "failedChecks"       to result.failedChecks
    )

    override fun isValidationComplete(): Boolean = result.isValid

    override fun getValidationErrors(): List<String> =
        result.missingElements.map { "Missing: $it" } + result.failedChecks
}
