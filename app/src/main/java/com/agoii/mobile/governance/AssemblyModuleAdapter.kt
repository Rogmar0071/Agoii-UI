package com.agoii.mobile.governance

import com.agoii.mobile.assembly.AssemblyResult

// ─── AssemblyModuleAdapter — Assembly Module Structural State ─────────────────

/**
 * AssemblyModuleAdapter — exposes the full structural state of the assembly module
 * after an [com.agoii.mobile.assembly.AssemblyValidator] pass.
 *
 * This adapter exposes structural state only. The Governor reads [getStateSignature]
 * and decides whether to append ASSEMBLY_VALIDATED or return NO_EVENT;
 * the adapter does not validate or decide.
 *
 * @property result The full assembly validation result from AssemblyValidator.
 */
class AssemblyModuleAdapter(
    private val result: AssemblyResult
) : ModuleState {

    override fun getStateSignature(): Map<String, Any> = mapOf(
        "assemblyValid"    to result.isValid,
        "completionStatus" to result.completionStatus,
        "missingElements"  to result.missingElements,
        "failedChecks"     to result.failedChecks
    )
}
