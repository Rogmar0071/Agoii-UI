package com.agoii.mobile.irs.reality

import com.agoii.mobile.irs.IntentData
import com.agoii.mobile.irs.RealitySimulationResult

/**
 * RealitySimulationEngine — independent real-world feasibility simulation layer.
 *
 * IRS-05 Contract Rules:
 *  - Operates independently; does NOT call any other IRS module.
 *  - Simulation is fully deterministic — same input always yields the same output.
 *  - All failure points are traceable to a specific constraint rule and field pair.
 *  - NO guessing: only reports failures when a named rule matches known conflict patterns.
 *  - The gateway is used read-only for fact-backed constraint evaluation.
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
        // Constraint rules: each rule produces a failure point when it fires.
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
     * @param intent The intent to simulate (post reality-validation, pre swarm-validation).
     * @return [RealitySimulationResult] with feasibility decision, all detected failure points,
     *         and a full [RealitySimulationResult.ruleTrace] for replay.
     */
    fun simulate(intent: IntentData): RealitySimulationResult {
        val ruleResults    = CONSTRAINTS.map { c -> Pair(c, c.check(intent)) }
        val failurePoints  = ruleResults.mapNotNull { (_, msg) -> msg }
        val ruleTrace      = ruleResults.map { (c, msg) ->
            if (msg == null) "${c.id}[${c.description}]: PASS"
            else             "${c.id}[${c.description}]: FAIL"
        }

        return RealitySimulationResult(
            feasible           = failurePoints.isEmpty(),
            failurePoints      = failurePoints,
            constraintsChecked = CONSTRAINTS.size,
            ruleTrace          = ruleTrace
        )
    }
}
