package com.agoii.mobile.governance

// ─── Surface classification ───────────────────────────────────────────────────

/**
 * Functional surface domains recognised by the CSL.
 *
 * GOVERNANCE — governance-layer-only contracts (rule binding, enforcement hooks, SSM).
 * BUSINESS   — domain / business logic surface.
 * FINANCIAL  — financial / ledger-state surface.
 * USER_DATA  — user data / identity surface.
 */
enum class ContractSurface {
    GOVERNANCE,
    BUSINESS,
    FINANCIAL,
    USER_DATA
}

// ─── Contract descriptor ──────────────────────────────────────────────────────

/**
 * Describes a contract as seen by the CSL gate.
 *
 * @property contractId         Unique identifier of the contract (e.g. "contract_1").
 * @property surfaces           Functional surfaces this contract touches (must be non-empty).
 * @property executionLoad      EL — total execution load score (component count × weight).
 * @property validationCapacity VC — total validation capacity score available for this contract.
 */
data class ContractDescriptor(
    val contractId:         String,
    val surfaces:           List<ContractSurface>,
    val executionLoad:      Int,
    val validationCapacity: Int
) {
    init {
        require(surfaces.isNotEmpty()) { "ContractDescriptor.surfaces must be non-empty" }
        require(executionLoad      >= 0) { "executionLoad must be ≥ 0" }
        require(validationCapacity >= 0) { "validationCapacity must be ≥ 0" }
    }
}

// ─── CSL gate result ──────────────────────────────────────────────────────────

/**
 * Result returned by the [ContractSurfaceLayer] gate.
 *
 * @property allowed         true → contract may be issued; false → issuance is blocked.
 * @property rejectionReason Machine-readable reason code when [allowed] is false (null otherwise).
 */
data class CslResult(
    val allowed:         Boolean,
    val rejectionReason: String? = null
)
