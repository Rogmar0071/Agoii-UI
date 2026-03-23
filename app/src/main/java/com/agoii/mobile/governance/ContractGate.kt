package com.agoii.mobile.governance

// ─── L3 — Contract Gate Interface ────────────────────────────────────────────

/**
 * ContractGate — the single authority boundary between orchestration and governance.
 *
 * Orchestration calls [approve]; governance evaluates and returns a decision.
 * Governance must NOT cross back into orchestration or core layers.
 */
interface ContractGate {

    /**
     * Evaluates a [ContractDescriptor] and returns true if execution is approved.
     */
    fun approve(contract: ContractDescriptor): Boolean
}
