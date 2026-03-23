package com.agoii.mobile.orchestration

import com.agoii.mobile.governance.ContractDescriptor
import com.agoii.mobile.governance.ContractGate
import com.agoii.mobile.governance.SurfaceType

// ─── Orchestrator — Execution Sequencing Authority ────────────────────────────

/**
 * ExecutionOrchestrator — controls execution sequencing and delegates
 * approval decisions to the governance gate.
 *
 * Responsibilities:
 *  - Owns the decision of whether a given execution position may proceed.
 *  - Calls [ContractGate.approve] for each position; never evaluates CSL directly.
 *  - Does NOT write events, iterate contracts, or call the Governor.
 */
class ExecutionOrchestrator(
    private val gate: ContractGate
) {
    fun canExecute(position: Int): Boolean {
        return gate.approve(
            ContractDescriptor(
                surface = SurfaceType.LG,
                executionCount = position,
                conditionCount = 0,
                validationCapacity = VC
            )
        )
    }

    companion object {
        private const val VC = 5
    }
}
