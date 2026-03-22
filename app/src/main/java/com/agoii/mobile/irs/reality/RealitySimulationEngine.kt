package com.agoii.mobile.irs.reality

import com.agoii.mobile.irs.IntentData
import com.agoii.mobile.irs.RealitySimulationResult
import com.agoii.mobile.irs.SimulationRuleResult

/**
 * RealitySimulationEngine — independent real-world feasibility simulation layer.
 *
 * IRS-05C Contract Rules:
 *  - Operates independently; does NOT call any other IRS module.
 *  - Simulation is fully deterministic — same input always yields the same output.
 *  - All failure points are traceable to a specific constraint rule and field pair.
 *  - NO guessing: only reports failures when a named rule matches known conflict patterns.
 *  - Every rule evaluation produces exactly one [SimulationRuleResult] (PASS or FAIL).
 *  - [RealitySimulationResult.evaluations] is the authoritative traceability artifact;
 *    [failurePoints] and [constraintsChecked] are derived from it — no hidden state.
 *
 * Simulation scope:
 *  1. Resource-availability constraints — ensures required resources are present.
 *  2. Environment-capability constraints — verifies the environment can support the objective.
 *  3. Constraint-environment compatibility — detects impossible combinations.
 *  4. Objective-reachability constraints — checks measurability and scope of the objective.
 *
 * @property gateway Injected knowledge gateway used for fact-backed constraint evaluation.
 */
class RealitySimulationEngine(
    private val gateway: RealityKnowledgeGateway = RealityKnowledgeGateway()
) {

    companion object {
        // Constraint rules: each rule produces a failure message when it fires, null when passing.
        private data class SimConstraint(
            val id:          String,
            val description: String,
            val check:       (IntentData) -> String?   // returns failure message or null
        )

        private val CONSTRAINTS: List<SimConstraint> = listOf(

            // ── Resource availability ────────────────────────────────────────
            SimConstraint("RES-01", "Resource availability check") { intent ->
                val res = intent.resources.value.lowercase()
                if (res.contains("unavailable") || res.contains("no_resource") ||
                    res == "none" || res == "n/a") {
                    "sim: required resources are unavailable — delivery is infeasible [field: resources]"
                } else null
            },

            // ── Offline + cloud environment conflict ─────────────────────────
            SimConstraint("ENV-01", "Offline-cloud compatibility check") { intent ->
                val con = intent.constraints.value.lowercase()
                val env = intent.environment.value.lowercase()
                if (con.contains("offline") &&
                    (env.contains("cloud") || env.contains("aws") ||
                     env.contains("gcp")   || env.contains("azure"))) {
                    "sim: offline constraint cannot be met in cloud environment [fields: constraints × environment]"
                } else null
            },

            // ── Real-time + serverless conflict ──────────────────────────────
            SimConstraint("ENV-02", "Real-time serverless compatibility check") { intent ->
                val con = intent.constraints.value.lowercase()
                val env = intent.environment.value.lowercase()
                if ((con.contains("real-time") || con.contains("zero-latency")) &&
                    env.contains("serverless")) {
                    "sim: real-time / zero-latency constraint cannot be met on serverless (cold-start latency) [fields: constraints × environment]"
                } else null
            },

            // ── Stateful + horizontal-scaling conflict ───────────────────────
            SimConstraint("CON-01", "Stateful horizontal-scaling compatibility check") { intent ->
                val con = intent.constraints.value.lowercase()
                if (con.contains("stateful") && con.contains("horizontal")) {
                    "sim: stateful design conflicts with horizontal scaling requirement [field: constraints]"
                } else null
            },

            // ── Objective measurability ──────────────────────────────────────
            SimConstraint("OBJ-01", "Objective scope/measurability check") { intent ->
                val obj = intent.objective.value.trim()
                if (obj.length < 5) {
                    "sim: objective is too vague to be certifiable — must be a measurable statement [field: objective]"
                } else null
            },

            // ── GDPR residency: personal-data outside EU ─────────────────────
            SimConstraint("CON-02", "GDPR data-residency check") { intent ->
                val con = intent.constraints.value.lowercase()
                val env = intent.environment.value.lowercase()
                if (con.contains("gdpr") && !env.contains("eu") && !env.contains("europe")) {
                    "sim: GDPR compliance requires EU data residency — environment does not specify EU region [fields: constraints × environment]"
                } else null
            }
        )
    }

    /**
     * Simulate the intent against all real-world constraint rules.
     *
     * Every rule produces exactly one [SimulationRuleResult] regardless of outcome.
     * [RealitySimulationResult.failurePoints] and [RealitySimulationResult.constraintsChecked]
     * are derived from [RealitySimulationResult.evaluations] — no hidden state.
     *
     * @param intent The intent to simulate (post evidence-validation, pre swarm-validation).
     * @return [RealitySimulationResult] with typed evaluations and derived feasibility state.
     */
    fun simulate(intent: IntentData): RealitySimulationResult {
        val evaluations = CONSTRAINTS.map { c ->
            val message = c.check(intent)
            SimulationRuleResult(
                ruleId      = c.id,
                description = c.description,
                triggered   = message != null,
                message     = message
            )
        }
        val failurePoints = evaluations.mapNotNull { it.message }

        return RealitySimulationResult(
            feasible           = failurePoints.isEmpty(),
            failurePoints      = failurePoints,
            constraintsChecked = evaluations.size,
            evaluations        = evaluations
        )
    }
}

