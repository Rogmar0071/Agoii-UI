package com.agoii.mobile.irs

/**
 * ContractScopeLaw — evaluates CSL-1 for a given [ContractScopeInput].
 *
 * System rule (CSL-1 §10):
 *   Do not change more than you can prove.
 *
 * A contract is VALID iff EL ≤ VC.
 * If EL > VC the contract MUST be decomposed before issuance.
 *
 * This class is stateless and produces only deterministic, reproducible outputs.
 */
class ContractScopeLaw {

    /**
     * Evaluate the Contract Scope Law for [input].
     *
     * Formulas (CSL-1 §2.5 and §3.1):
     *   EL = Σ w(s)  +  E  +  (2 × C)
     *   VC = EC + RC + SC + CC + DC
     *   VALID ⟺ EL ≤ VC
     *
     * @param input All surfaces, graph metrics, and validation evidence counts.
     * @return [ContractScopeResult] with computed EL/VC, status, and required action.
     */
    fun evaluate(input: ContractScopeInput): ContractScopeResult {
        val el = input.surfaces.sumOf { it.type.weight } +
                 input.edges +
                 (2 * input.crossCouplingFactor)
        val vc = input.ec + input.rc + input.sc + input.cc + input.dc
        val valid = el <= vc
        return ContractScopeResult(
            executionLoad      = el,
            validationCapacity = vc,
            status             = if (valid) "VALID" else "INVALID",
            requiredAction     = if (valid) "EXECUTE" else "SPLIT"
        )
    }
}
