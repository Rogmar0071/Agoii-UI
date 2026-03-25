package com.agoii.mobile.core.contract

import com.agoii.mobile.core.enforcement.EnforcementPipeline
import com.agoii.mobile.core.enforcement.EnforcementResult
import com.agoii.mobile.core.enforcement.EnforcementVerdict

// ─── Contract Engine ──────────────────────────────────────────────────────────

/**
 * ContractEngine — routes contract execution through the mandatory enforcement gate.
 *
 * Execution flow:
 *   1. Receive [ContractGraph].
 *   2. Run [EnforcementPipeline] — produces [EnforcementResult].
 *   3. Gate: if verdict is not APPROVED → halt; return result with no routing.
 *   4. Route approved contract through [ExecutionRouter].
 *
 * Non-negotiable principles:
 *  - No contract executes without an APPROVED enforcement verdict.
 *  - No fallback execution path exists.
 *  - The engine is stateless; every [execute] call is independent.
 */
class ContractEngine(
    private val enforcementPipeline: EnforcementPipeline = EnforcementPipeline(),
    private val executionRouter:     ExecutionRouter      = ExecutionRouter()
) {

    /**
     * Execute [graph] through the enforcement gate and routing layer.
     *
     * @param graph The [ContractGraph] to process.
     * @return [ContractExecutionResult] carrying the enforcement result and route decision.
     */
    fun execute(graph: ContractGraph): ContractExecutionResult {
        val enforcementResult = enforcementPipeline.run(graph)

        if (enforcementResult.verdict != EnforcementVerdict.APPROVED) {
            return ContractExecutionResult(
                graph             = graph,
                enforcementResult = enforcementResult,
                routeDecision     = null
            )
        }

        val routeDecision = executionRouter.route(graph, enforcementResult)

        return ContractExecutionResult(
            graph             = graph,
            enforcementResult = enforcementResult,
            routeDecision     = routeDecision
        )
    }
}

/**
 * Result of [ContractEngine.execute].
 *
 * @property graph             The input [ContractGraph].
 * @property enforcementResult Outcome of the enforcement pipeline.
 * @property routeDecision     [RouteDecision.PROCEED] when approved; null when rejected.
 */
data class ContractExecutionResult(
    val graph:             ContractGraph,
    val enforcementResult: EnforcementResult,
    val routeDecision:     RouteDecision?
) {
    /** true only when enforcement passed and routing was assigned. */
    val proceeded: Boolean get() = routeDecision == RouteDecision.PROCEED
}
