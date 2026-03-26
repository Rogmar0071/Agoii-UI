package com.agoii.mobile.execution

import com.agoii.mobile.contracts.AgentProfile
import com.agoii.mobile.contracts.ContractIntent
import com.agoii.mobile.contracts.ContractSystemOrchestrator
import com.agoii.mobile.core.EventLedger
import com.agoii.mobile.governor.Governor

/**
 * ExecutionEntryPoint — the ONLY component allowed to trigger contract derivation
 * and invoke [ExecutionAuthority].
 *
 * Responsibilities:
 *  1. Receive an intent payload from the orchestration layer.
 *  2. Derive contracts via [ContractSystemOrchestrator].
 *  3. Delegate all validation, authorization, and ledger writes to [ExecutionAuthority].
 *  4. Advance the Governor exactly once after a successful authorization.
 *
 * Rules:
 *  - Contains ZERO validation logic (delegated entirely to [ExecutionAuthority]).
 *  - Contains ZERO authorization logic (delegated entirely to [ExecutionAuthority]).
 *  - Only orchestration — no decisions.
 *  - Returns a structured [AuthorizationResult] on every path; no silent nulls.
 *  - [ExecutionAuthority] is private and not reachable from any other class.
 */
class ExecutionEntryPoint(
    ledger:  EventLedger,
    private val governor: Governor
) {

    private val executionAuthority         = ExecutionAuthority(ledger)
    private val contractSystemOrchestrator = ContractSystemOrchestrator()

    companion object {
        private const val DEFAULT_CONSTRAINTS = "standard"
        private const val DEFAULT_ENVIRONMENT = "mobile"
        private const val DEFAULT_RESOURCES   = "available"

        /**
         * Default agent profile used when evaluating [ContractSystemOrchestrator].
         * Represents a maximally capable agent for deterministic contract derivation.
         *
         * All capability dimensions use a 0–3 scale (higher = more capable).
         * driftTendency uses an inverted scale (0 = no drift, 3 = high drift).
         */
        private val DEFAULT_AGENT_PROFILE = AgentProfile(
            agentId             = "default-agent",
            constraintObedience = 3,  // maximum: always follows constraints
            structuralAccuracy  = 3,  // maximum: always follows structure
            driftTendency       = 0,  // minimum: zero deviation tendency
            complexityHandling  = 3,  // maximum: handles complex multi-step plans
            outputReliability   = 3   // maximum: fully deterministic output
        )
    }

    /**
     * Execute the full intent-to-contracts pipeline for [projectId].
     *
     * Flow (locked):
     *  1. Extract objective from [intentPayload].
     *  2. Call [ContractSystemOrchestrator.evaluate] to derive an [ExecutionPlan].
     *  3. Map [ExecutionStep] list → contract descriptors.
     *  4. Call [ExecutionAuthority.authorize] (sole pre-ledger gate).
     *  5. If authorized: ledger already written; advance [Governor.runGovernor].
     *  6. If blocked: return structured failure with status, reason, and stage.
     *
     * @param projectId     The project ledger to write to.
     * @param intentPayload The payload of the INTENT_SUBMITTED event.
     * @return [AuthorizationResult] — never null; always carries status + reason + stage.
     */
    fun executeIntent(
        projectId:     String,
        intentPayload: Map<String, Any>
    ): AuthorizationResult {
        val objective = intentPayload["objective"] as? String
            ?: return AuthorizationResult.blocked(
                "Intent payload missing 'objective'",
                "AUTHORIZATION"
            )
        val intentId = intentPayload["intentId"] as? String ?: objective

        val intent = ContractIntent(
            objective   = objective,
            constraints = DEFAULT_CONSTRAINTS,
            environment = DEFAULT_ENVIRONMENT,
            resources   = DEFAULT_RESOURCES
        )

        val csoResult = contractSystemOrchestrator.evaluate(intent, DEFAULT_AGENT_PROFILE)

        // Prefer the adapted plan (ADAPT decision); fall back to the scored plan (ACCEPT).
        val steps = csoResult.adaptedContract?.adaptedPlan?.steps
            ?: csoResult.scoredContract?.derivation?.executionPlan?.steps
            ?: return AuthorizationResult.blocked(
                "ContractSystemOrchestrator produced no execution plan",
                "AUTHORIZATION"
            )
        if (steps.isEmpty()) {
            return AuthorizationResult.blocked(
                "ContractSystemOrchestrator produced an empty execution plan",
                "AUTHORIZATION"
            )
        }

        val contracts: List<Map<String, Any>> = steps.map { step ->
            mapOf(
                "id"       to "contract_${step.position}",
                "name"     to step.description,
                "position" to step.position
            )
        }

        // ExecutionAuthority is the sole validation + authorization gate.
        val authResult = executionAuthority.authorize(
            projectId = projectId,
            intentId  = intentId,
            contracts = contracts
        )

        // Only advance the Governor when the ledger write succeeded.
        if (authResult.authorized) {
            governor.runGovernor(projectId)
        }

        return authResult
    }
}
