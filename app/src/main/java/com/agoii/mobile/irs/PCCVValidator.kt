package com.agoii.mobile.irs

/**
 * PCCVValidator — Step 6 of the IRS execution graph.
 *
 * Single responsibility: Pre-Certification Compliance Validation.
 * Verifies that the intent satisfies ALL certification preconditions before
 * a Certified result is emitted.
 *
 * Required checks (ALL must pass):
 *  1. evidence_coverage: every mandatory field (objective, constraints, environment,
 *     resources) has ≥ 1 EvidenceRef.
 *  2. Swarm consistency: [SwarmResult.consistent] must be true.
 *  3. Simulation feasibility: [SimulationResult.feasible] must be true.
 *
 * Rules:
 *  - [PCCVResult.passed] is true only when all three checks pass.
 *  - [PCCVResult.evidenceCoverage] reflects the result of check 1 independently.
 *  - Does NOT call any other IRS module.
 *
 * Orchestration contract:
 *  - If [PCCVResult.passed] is false the orchestrator must halt with REJECTED.
 */
class PCCVValidator {

    /**
     * Validate [intentData] against the upstream [swarmResult] and [simulationResult].
     */
    fun validate(
        intentData:       IntentData,
        swarmResult:      SwarmResult,
        simulationResult: SimulationResult
    ): PCCVResult {
        val errors = mutableListOf<String>()

        val evidenceCoverage = checkEvidenceCoverage(intentData, errors)

        if (!swarmResult.consistent) {
            errors.add("PCCV: swarm was not consistent — ${swarmResult.conflicts.size} conflict(s) detected")
        }
        if (!simulationResult.feasible) {
            errors.add("PCCV: simulation was not feasible — ${simulationResult.failurePoints.size} failure point(s) detected")
        }

        return PCCVResult(
            passed           = errors.isEmpty(),
            evidenceCoverage = evidenceCoverage,
            errors           = errors
        )
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private fun checkEvidenceCoverage(
        intentData: IntentData,
        errors:     MutableList<String>
    ): Boolean {
        var covered = true
        val fields = mapOf(
            "objective"   to intentData.objective,
            "constraints" to intentData.constraints,
            "environment" to intentData.environment,
            "resources"   to intentData.resources
        )
        fields.forEach { (name, field) ->
            if (field.evidence.isEmpty()) {
                errors.add("PCCV: $name has no evidence — coverage check failed")
                covered = false
            }
        }
        return covered
    }
}
