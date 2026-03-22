package com.agoii.mobile.governance

// ─── L1 — Governance Foundation Models ───────────────────────────────────────

/** Classifies the surface scope; LG carries a fixed weight of 2. */
enum class SurfaceType(val weight: Int) {
    LG(2)
}

/**
 * Describes a governance contract and its derived execution load.
 *
 * @property surface           The surface type governing this contract.
 * @property executionCount    E — number of execution steps.
 * @property conditionCount    C — number of conditional branches.
 * @property validationCapacity VC — maximum load the surface can absorb.
 *
 * Derived:
 *   executionLoad = w + E + (2 × C)
 */
data class ContractDescriptor(
    val surface: SurfaceType,
    val executionCount: Int,
    val conditionCount: Int,
    val validationCapacity: Int
) {
    val executionLoad: Int = surface.weight + executionCount + (2 * conditionCount)
}

/** Outcome of a CSL evaluation pass. */
enum class Outcome { ALLOWED, REJECTED }

/**
 * Result produced by the Contract Surface Layer after evaluating a contract.
 *
 * @property outcome ALLOWED or REJECTED.
 * @property reason  Human-readable explanation including EL and VC values.
 */
data class ValidationResult(
    val outcome: Outcome,
    val reason: String
)
