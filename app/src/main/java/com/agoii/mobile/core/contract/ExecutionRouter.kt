package com.agoii.mobile.core.contract

// ─── Execution Router ─────────────────────────────────────────────────────────

/**
 * ExecutionRouter — terminal routing authority for pre-approved contracts.
 *
 * Enforcement is centralized in [ContractModule]. Contracts arrive here only
 * after the [EnforcementPipeline] has confirmed approval; no secondary approval
 * check or exception-based blocking is performed here.
 *
 * [route] is the sole entry point. It accepts the approved [ContractGraph]
 * and returns [RouteDecision.PROCEED].
 */
class ExecutionRouter {

    /**
     * Route a pre-approved contract to execution.
     *
     * @param graph The [ContractGraph] approved by the enforcement gate in [ContractModule].
     * @return [RouteDecision.PROCEED] unconditionally.
     */
    fun route(graph: ContractGraph): RouteDecision {
        return RouteDecision.PROCEED
    }
}

/** Terminal routing decision issued by [ExecutionRouter]. */
enum class RouteDecision { PROCEED }
