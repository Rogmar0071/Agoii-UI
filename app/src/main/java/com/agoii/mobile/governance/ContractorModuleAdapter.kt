package com.agoii.mobile.governance

import com.agoii.mobile.contractor.ContractorProfile

// ─── ContractorModuleAdapter — Contractor Module Structural State ─────────────

/**
 * ContractorModuleAdapter — exposes the full structural state of the contractor module
 * for a single contractor-resolution attempt.
 *
 * This adapter exposes structural state only. The Governor reads [getStateSignature]
 * and decides whether to proceed or return WAITING; the adapter does not validate or decide.
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
}
