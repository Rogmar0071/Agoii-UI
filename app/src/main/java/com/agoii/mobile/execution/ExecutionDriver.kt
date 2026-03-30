package com.agoii.mobile.execution

// ─── ExecutionDriver ──────────────────────────────────────────────────────────

/**
 * ExecutionDriver — the sole interface through which contractor work is performed.
 *
 * RULES:
 *  - Every execution source MUST have a registered driver.
 *  - No driver → the system blocks (no silent fallback, no stub).
 *  - Drivers are registered externally via [DriverRegistry]; none are hardcoded here.
 *  - This is a pure interface: no state, no default implementations.
 *
 * CONTRACT: AGOII-RCF-EXECUTION-INFRASTRUCTURE-01
 */
interface ExecutionDriver {

    /**
     * Execute [input] and return the contractor output.
     *
     * @param input The structured execution contract passed from [ContractorExecutor].
     * @return [ContractorExecutionOutput] with the execution result.
     */
    fun execute(input: ContractorExecutionInput): ContractorExecutionOutput
}
