package com.agoii.mobile.core.contract

import com.agoii.mobile.core.ReplayStructuralState
import com.agoii.mobile.core.enforcement.EnforcementPipeline
import com.agoii.mobile.core.enforcement.EnforcementResult

// ─── Contract Module ──────────────────────────────────────────────────────────

/**
 * ContractModule — the sole authority for contract execution authorization.
 *
 * Execution flow:
 *   1. Accept [ReplayStructuralState] and contract metadata.
 *   2. Construct [ContractGraph] from structural state.
 *   3. Run [EnforcementPipeline] — single, centralized enforcement gate.
 *   4. Block execution when enforcement fails; return rejected [ContractModuleResult].
 *   5. Route approved contract through [ContractEngine] → [ExecutionRouter].
 *   6. Return [ContractModuleResult].
 *
 * Non-negotiable principles:
 *  - [EnforcementPipeline] is the exclusive enforcement authority.
 *  - No enforcement logic exists outside this class.
 *  - No fallback execution path exists.
 *  - No exception-based control flow.
 *  - The module is stateless; every [process] call is independent.
 */
class ContractModule(
    private val enforcementPipeline: EnforcementPipeline = EnforcementPipeline(),
    private val contractEngine:      ContractEngine      = ContractEngine()
) {

    /**
     * Process a contract from structural state through the enforcement gate and routing layer.
     *
     * @param contractId     Unique identifier for this contract.
     * @param state          Current [ReplayStructuralState] to base the graph on.
     * @param declaredFields Field paths this contract references.
     * @param derivedFields  Derivation expressions this contract declares.
     * @return [ContractModuleResult] with the enforcement outcome and, when approved, the
     *         routing result.
     */
    fun process(
        contractId:     String,
        state:          ReplayStructuralState,
        declaredFields: List<String>,
        derivedFields:  Map<String, String>
    ): ContractModuleResult {

        // Step 1: Construct ContractGraph from structural state
        val graph = ContractGraph(
            contractId     = contractId,
            state          = state,
            declaredFields = declaredFields,
            derivedFields  = derivedFields
        )

        // Step 2: Single centralized enforcement gate — mandatory; cannot be skipped
        val enforcementResult = enforcementPipeline.run(graph)

        // Step 3: Block execution if enforcement did not approve
        if (!enforcementResult.approved) {
            return ContractModuleResult(
                contractId        = contractId,
                enforcementResult = enforcementResult,
                executionResult   = null,
                approved          = false
            )
        }

        // Step 4: Route approved contract — enforcement is complete; no secondary checks
        val executionResult = contractEngine.execute(graph)

        return ContractModuleResult(
            contractId        = contractId,
            enforcementResult = enforcementResult,
            executionResult   = executionResult,
            approved          = executionResult.proceeded
        )
    }
}

/**
 * Result produced by [ContractModule.process].
 *
 * @property contractId        The contract identifier.
 * @property enforcementResult Full enforcement trace from the 8-step pipeline.
 * @property executionResult   Routing result; null when enforcement blocked execution.
 * @property approved          true only when enforcement passed and routing proceeded.
 */
data class ContractModuleResult(
    val contractId:        String,
    val enforcementResult: EnforcementResult,
    val executionResult:   ContractExecutionResult?,
    val approved:          Boolean
)
