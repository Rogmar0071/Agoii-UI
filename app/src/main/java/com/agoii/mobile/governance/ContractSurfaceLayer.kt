package com.agoii.mobile.governance

// ─── L2 — Contract Surface Layer Engine ──────────────────────────────────────

/**
 * Stateless evaluator for governance contracts.
 *
 * Reads L1 models; does not modify them.
 */
class ContractSurfaceLayer {

    /**
     * Evaluates a [ContractDescriptor] against its own validation capacity.
     *
     * Logic:
     *   EL ≤ VC → ALLOWED
     *   EL  > VC → REJECTED
     *
     * @return [ValidationResult] with outcome and a reason that cites EL and VC.
     */
    fun evaluate(contract: ContractDescriptor): ValidationResult {
        val el = contract.executionLoad
        val vc = contract.validationCapacity
        return if (el <= vc) {
            ValidationResult(
                outcome = Outcome.ALLOWED,
                reason = "EL=$el is within VC=$vc"
            )
        } else {
            ValidationResult(
                outcome = Outcome.REJECTED,
                reason = "EL=$el exceeds VC=$vc"
            )
        }
    }
}
