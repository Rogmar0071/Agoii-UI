package com.agoii.mobile.core.contract

import com.agoii.mobile.core.ReplayStructuralState

// ─── Contract Module ──────────────────────────────────────────────────────────

/**
 * ContractModule — the mandatory enforcement integration point in the contract lifecycle.
 *
 * Execution flow:
 *   1. Accept [ReplayStructuralState] and contract metadata.
 *   2. Construct [ContractGraph] from structural state.
 *   3. Pass graph through [ContractEngine] — enforcement gate runs before any routing.
 *   4. Return [ContractModuleResult].
 *
 * The enforcement pipeline is an unconditional gate between graph construction and
 * execution routing. No contract may bypass this gate.
 *
 * The module is stateless; every [process] call is independent.
 */
class ContractModule(
    private val contractEngine: ContractEngine = ContractEngine()
) {

    /**
     * Process a contract from structural state through the full enforcement pipeline.
     *
     * @param contractId     Unique identifier for this contract.
     * @param state          Current [ReplayStructuralState] to base the graph on.
     * @param declaredFields Field paths this contract references.
     * @param derivedFields  Derivation expressions this contract declares.
     * @return [ContractModuleResult] with the outcome of enforcement and routing.
     */
    fun process(
        contractId:     String,
        state:          ReplayStructuralState,
        declaredFields: List<String>,
        derivedFields:  Map<String, String>
    ): ContractModuleResult {

        // Step A: Construct ContractGraph from structural state
        val graph = ContractGraph(
            contractId     = contractId,
            state          = state,
            declaredFields = declaredFields,
            derivedFields  = derivedFields
        )

        // Step B: Pass through enforcement gate — mandatory; cannot be skipped
        val executionResult = contractEngine.execute(graph)

        return ContractModuleResult(
            contractId      = contractId,
            executionResult = executionResult,
            approved        = executionResult.proceeded
        )
    }
}

/**
 * Result produced by [ContractModule.process].
 *
 * @property contractId      The contract identifier.
 * @property executionResult Full engine result including enforcement trace.
 * @property approved        true only when enforcement passed and routing proceeded.
 */
data class ContractModuleResult(
    val contractId:      String,
    val executionResult: ContractExecutionResult,
    val approved:        Boolean
)
