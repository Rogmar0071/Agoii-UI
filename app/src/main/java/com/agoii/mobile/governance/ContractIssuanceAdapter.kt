package com.agoii.mobile.governance

// ─── ContractIssuanceAdapter — SSM + CSL Structural State ────────────────────

/**
 * ContractIssuanceAdapter — exposes the full structural state of the contract-issuance
 * gate, combining [StateSurfaceMirror] (SSM) and [ContractSurfaceLayer] (CSL) checks.
 *
 * This adapter exposes structural state only. The Governor reads [getStateSignature]
 * and makes all issuance decisions itself; the adapter does not validate or decide.
 *
 * @property ssmInitialized  Whether the SSM has been explicitly initialized.
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
}
