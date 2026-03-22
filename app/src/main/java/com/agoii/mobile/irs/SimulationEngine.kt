package com.agoii.mobile.irs

/**
 * SimulationEngine — Step 5 of the IRS execution graph.
 *
 * Single responsibility: validate real-world feasibility of the intent by
 * inspecting evidence coverage and detecting execution failure points.
 *
 * Critical rules:
 *  - Consumes evidence embedded in [IntentData] fields directly.
 *  - If ANY mandatory field has zero EvidenceRef the simulation MUST fail.
 *  - Placeholder / structural-only checks are forbidden; every failure must be
 *    traceable to a concrete constraint violation.
 *  - Does NOT call any other IRS module.
 *
 * Orchestration contract:
 *  - If [SimulationResult.feasible] is false the orchestrator must halt with INFEASIBLE.
 */
class SimulationEngine {

    /**
     * Run feasibility simulation over [intentData].
     *
     * Failure conditions (any one causes [SimulationResult.feasible] = false):
     *  1. Any mandatory field has 0 EvidenceRef (cannot validate without evidence).
     *  2. Objective value is blank (no executable intent defined).
     *  3. Resources field indicates unavailability while constraints express dependencies
     *     (heuristic: value contains "unavailable" or "no_resource").
     */
    fun simulate(intentData: IntentData): SimulationResult {
        val failurePoints = mutableListOf<String>()

        // Rule 1: evidence coverage is mandatory for simulation to proceed
        checkEvidenceCoverage(intentData, failurePoints)

        // Rule 2: objective must express a meaningful intent
        if (intentData.objective.value.isBlank()) {
            failurePoints.add("objective is blank — no executable intent defined")
        }

        // Rule 3: resource-constraint contradiction detection
        detectConstraintContradictions(intentData, failurePoints)

        return SimulationResult(
            feasible      = failurePoints.isEmpty(),
            failurePoints = failurePoints
        )
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private fun checkEvidenceCoverage(
        intentData:    IntentData,
        failures:      MutableList<String>
    ) {
        val fields = mapOf(
            "objective"   to intentData.objective,
            "constraints" to intentData.constraints,
            "environment" to intentData.environment,
            "resources"   to intentData.resources
        )
        fields.forEach { (name, field) ->
            if (field.evidence.isEmpty()) {
                failures.add("$name has no evidence — simulation cannot validate feasibility")
            }
        }
    }

    private fun detectConstraintContradictions(
        intentData: IntentData,
        failures:   MutableList<String>
    ) {
        val resourceValue   = intentData.resources.value.lowercase()
        val constraintValue = intentData.constraints.value.lowercase()

        // Heuristic: if resources signal unavailability while constraints are non-empty,
        // the system cannot satisfy its own declared constraints.
        val resourceUnavailable =
            resourceValue.contains("unavailable") || resourceValue.contains("no_resource")
        if (resourceUnavailable && constraintValue.isNotBlank()) {
            failures.add(
                "resource unavailability contradicts non-empty constraint requirements"
            )
        }
    }
}
