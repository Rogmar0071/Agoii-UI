package com.agoii.mobile.governance

// ─── Governance Gate — external, stateless, non-invasive ─────────────────────

/**
 * ContractGate — the single governance enforcement point.
 *
 * Rules (AGOII-GOVERNANCE-GATE-01):
 *  - Accepts a [ContractDescriptor] and delegates evaluation to [ContractSurfaceLayer].
 *  - Returns ALLOW (true) or REJECT (false) — no side effects, no event emission.
 *  - Does not access the EventStore.
 *  - Governor is completely unaware of this class.
 */
class ContractGate(
    private val csl: ContractSurfaceLayer = ContractSurfaceLayer()
) {

    /**
     * Evaluates [contract] against the Contract Surface Layer.
     *
     * @return true if the contract is ALLOWED (EL ≤ VC), false if REJECTED (EL > VC).
     */
    fun approve(contract: ContractDescriptor): Boolean {
        val result = csl.evaluate(contract)
        return result.outcome == Outcome.ALLOWED
    }
}
