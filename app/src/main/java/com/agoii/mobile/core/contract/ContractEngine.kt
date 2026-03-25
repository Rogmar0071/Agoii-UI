package com.agoii.mobile.core.contract

// ─── Contract Engine ──────────────────────────────────────────────────────────

/**
 * ContractEngine — routes pre-approved contracts to execution.
 *
 * Enforcement is centralized exclusively in [ContractModule]. ContractEngine
 * receives only contracts that have already passed the enforcement gate and
 * delegates routing to [ExecutionRouter] without performing any additional
 * enforcement or approval checks.
 *
 * The engine is stateless; every [execute] call is independent.
 */
class ContractEngine(
    private val executionRouter: ExecutionRouter = ExecutionRouter()
) {

    /**
     * Route [graph] to execution via [ExecutionRouter].
     *
     * @param graph The [ContractGraph] already approved by [ContractModule].
     * @return [ContractExecutionResult] carrying the route decision.
     */
    fun execute(graph: ContractGraph): ContractExecutionResult {
        val routeDecision = executionRouter.route(graph)
        return ContractExecutionResult(
            graph         = graph,
            routeDecision = routeDecision
        )
    }
}

/**
 * Result of [ContractEngine.execute].
 *
 * @property graph         The input [ContractGraph].
 * @property routeDecision [RouteDecision.PROCEED] when the contract was routed.
 */
data class ContractExecutionResult(
    val graph:         ContractGraph,
    val routeDecision: RouteDecision
) {
    /** true when routing was assigned. */
    val proceeded: Boolean get() = routeDecision == RouteDecision.PROCEED
}
