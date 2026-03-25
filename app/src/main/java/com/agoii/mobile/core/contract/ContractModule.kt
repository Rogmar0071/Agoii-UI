package com.agoii.mobile.core.contract

import com.agoii.mobile.core.ReplayStructuralState
import com.agoii.mobile.core.enforcement.EnforcementPipeline
import com.agoii.mobile.core.enforcement.EnforcementResult
import com.agoii.mobile.core.enforcement.SurfaceMap

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
     * @param declaredFields Field paths this contract references (used to populate
     *                       [SurfaceMap.fieldUsage]).
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

        // Step 1: Construct the full SurfaceMap — real files, data classes, field usage, dependencies
        val surface = SurfaceMap(
            files        = listOf(
                "core/Replay.kt",
                "core/contract/ContractGraph.kt",
                "core/contract/ContractEngine.kt",
                "core/contract/ContractModule.kt",
                "core/contract/ExecutionRouter.kt",
                "core/enforcement/EnforcementPipeline.kt",
                "core/enforcement/EnforcementValidator.kt",
                "core/enforcement/EnforcementResult.kt"
            ),
            dataClasses  = mapOf(
                "ReplayStructuralState"    to listOf("intent", "contracts", "execution", "assembly"),
                "IntentStructuralState"    to listOf("intent.structurallyComplete"),
                "ContractStructuralState"  to listOf("contracts.generated", "contracts.valid"),
                "ExecutionStructuralState" to listOf(
                    "execution.totalTasks",
                    "execution.assignedTasks",
                    "execution.completedTasks",
                    "execution.validatedTasks",
                    "execution.fullyExecuted"
                ),
                "AssemblyStructuralState"  to listOf(
                    "assembly.assemblyStarted",
                    "assembly.assemblyValidated",
                    "assembly.assemblyCompleted",
                    "assembly.assemblyValid"
                )
            ),
            fieldUsage   = declaredFields.associateWith { listOf(contractId) },
            dependencies = listOf("ReplayStructuralState")
        )

        // Step 2: Construct ContractGraph from the full structural surface
        val graph = ContractGraph(
            contractId    = contractId,
            state         = state,
            surface       = surface,
            derivedFields = derivedFields
        )

        // Step 3: Single centralized enforcement gate — mandatory; cannot be skipped
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
