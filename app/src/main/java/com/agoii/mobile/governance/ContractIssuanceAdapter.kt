package com.agoii.mobile.governance

// ─── ContractIssuanceAdapter — SSM + CSL Structural State ────────────────────

/**
 * ContractIssuanceAdapter — exposes the full structural state of the contract-issuance
 * gate, combining [StateSurfaceMirror] (SSM) and [ContractSurfaceLayer] (CSL) checks.
 *
 * This adapter replaces any internal boolean gate logic in the Governor with an
 * explicit, queryable structural snapshot.
 *
 * @property ssmInitialized  Whether the SSM has been explicitly initialised.
 * @property lgSurfaceActive Whether the LG surface is currently active in the SSM.
 * @property cslResult       Full CSL evaluation result for the contract at [position].
 * @property position        The contract-issuance position being evaluated.
 */
class ContractIssuanceAdapter(
    private val ssmInitialized:  Boolean,
    private val lgSurfaceActive: Boolean,
    private val cslResult:       ValidationResult,
    private val position:        Int
) : ModuleState {

    override fun getStateSignature(): Map<String, Any> = mapOf(
        "ssmInitialized"  to ssmInitialized,
        "lgSurfaceActive" to lgSurfaceActive,
        "cslOutcome"      to cslResult.outcome.name,
        "cslReason"       to cslResult.reason,
        "position"        to position
    )

    override fun isValidationComplete(): Boolean =
        ssmInitialized && lgSurfaceActive && cslResult.outcome == Outcome.ALLOWED

    override fun getValidationErrors(): List<String> = buildList {
        if (!ssmInitialized)  add("SSM not initialized")
        if (!lgSurfaceActive) add("LG surface not active in SSM")
        if (cslResult.outcome != Outcome.ALLOWED) add(cslResult.reason)
    }
}
