package com.agoii.mobile.irs

class ContractScopeLaw {

    fun evaluate(input: ContractScopeInput): ContractScopeResult {

        val surfaceWeight = input.surfaces.sumOf { it.type.weight }

        val el = surfaceWeight +
                 input.edges +
                 (2 * input.crossCouplingFactor)

        val vc = input.ec +
                 input.rc +
                 input.sc +
                 input.cc +
                 input.dc

        val valid = el <= vc

        return ContractScopeResult(
            executionLoad = el,
            validationCapacity = vc,
            safetyCondition = valid,
            status = if (valid) "VALID" else "INVALID",
            requiredAction = if (valid) "PROCEED" else "SPLIT_REQUIRED"
        )
    }
}
