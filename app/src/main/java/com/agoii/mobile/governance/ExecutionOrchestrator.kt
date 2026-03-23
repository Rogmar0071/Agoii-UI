package com.agoii.mobile.governance

/**
 * Fixed validation capacity — governance-owned deterministic baseline for CSL evaluation.
 * Must not be derived dynamically or read from any external source.
 */
private const val VC = 5

/**
 * ExecutionOrchestrator — pre-execution governance gate.
 *
 * Rules:
 *  - Single enforcement point for contract issuance decisions.
 *  - Fully governance-owned; no dependency on execution core.
 *  - Read-only: evaluates contracts without mutating state.
 */
class ExecutionOrchestrator(
    private val ssm: StateSurfaceMirror,
    private val csl: ContractSurfaceLayer
) {

    /**
     * Returns true if a contract at [position] may be issued.
     *
     * Gate conditions:
     *  - SSM must be initialized.
     *  - LG surface must be active.
     *  - CSL evaluation must return ALLOWED for the given position.
     */
    fun canIssue(position: Int): Boolean {
        if (!ssm.isInitialized()) return false
        if (!ssm.getActiveSurfaces().contains(SurfaceType.LG)) return false

        val result = csl.evaluate(
            ContractDescriptor(
                surface = SurfaceType.LG,
                executionCount = position,
                conditionCount = 0, // no conditional branches in execution contracts
                validationCapacity = VC
            )
        )

        return result.outcome == Outcome.ALLOWED
    }
}
