package com.agoii.mobile.core.contract

import com.agoii.mobile.core.enforcement.EnforcementResult
import com.agoii.mobile.core.enforcement.EnforcementVerdict

// ─── Execution Router ─────────────────────────────────────────────────────────

/**
 * ExecutionRouter — terminal routing authority for approved contracts.
 *
 * Contracts MUST arrive with an APPROVED [EnforcementResult]. Any non-approved
 * contract causes an [IllegalStateException]; there is no fallback execution path.
 *
 * [route] is the sole entry point. It verifies the enforcement verdict and
 * returns [RouteDecision.PROCEED] for the approved contract.
 */
class ExecutionRouter {

    /**
     * Route an approved contract to execution.
     *
     * @param graph             The [ContractGraph] to route.
     * @param enforcementResult The [EnforcementResult] for this graph — MUST be APPROVED.
     * @return [RouteDecision.PROCEED] unconditionally when the contract is approved.
     * @throws IllegalStateException if [enforcementResult] verdict is not APPROVED.
     */
    fun route(graph: ContractGraph, enforcementResult: EnforcementResult): RouteDecision {
        check(enforcementResult.verdict == EnforcementVerdict.APPROVED) {
            "Contract '${graph.contractId}' cannot be routed: " +
            "enforcement verdict is ${enforcementResult.verdict} " +
            "with ${enforcementResult.violations.size} violation(s)"
        }
        return RouteDecision.PROCEED
    }
}

/** Terminal routing decision issued by [ExecutionRouter]. */
enum class RouteDecision { PROCEED }
