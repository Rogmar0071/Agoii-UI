package com.agoii.mobile.governance

import com.agoii.mobile.contractor.ContractorProfile

// ─── ContractorModuleAdapter — Contractor Module Structural State ─────────────

/**
 * ContractorModuleAdapter — exposes the full structural state of the contractor module
 * for a single contractor-resolution attempt.
 *
 * The Governor queries this adapter before appending any task-assignment event.
 * Validation is complete only when a verified contractor is present.
 *
 * @property contractor The resolved contractor profile, or null if none was found.
 */
class ContractorModuleAdapter(
    private val contractor: ContractorProfile?
) : ModuleState {

    override fun getStateSignature(): Map<String, Any> = mapOf(
        "contractorAvailable" to (contractor != null),
        "contractorId"        to (contractor?.id ?: ""),
        "contractorVerified"  to (contractor != null)
    )

    override fun isValidationComplete(): Boolean = contractor != null

    override fun getValidationErrors(): List<String> =
        if (contractor != null) emptyList()
        else listOf("No verified contractor available for task assignment")
}
