package com.agoii.mobile.irs

/**
 * GapDetector — Step 2 of the IRS execution graph.
 *
 * Single responsibility: identify mandatory intent fields that have fewer than
 * one attached [EvidenceRef].
 *
 * Rules:
 *  - Inspects objective, constraints, environment, and resources fields.
 *  - A field is a gap when its evidence list is empty.
 *  - Returns [GapResult.hasGaps] = true when at least one gap is found.
 *  - Does NOT call any other IRS module.
 *
 * Orchestration contract:
 *  - If [GapResult.hasGaps] is true the orchestrator must halt with NEEDS_CLARIFICATION.
 */
class GapDetector {

    /**
     * @property hasGaps true when one or more mandatory fields lack evidence.
     * @property gaps    Names of the fields that are missing evidence.
     */
    data class GapResult(
        val hasGaps: Boolean,
        val gaps:    List<String>
    )

    /**
     * Inspect each mandatory field of [intentData] for missing evidence coverage.
     */
    fun detect(intentData: IntentData): GapResult {
        val gaps = mutableListOf<String>()
        if (intentData.objective.evidence.isEmpty())   gaps.add("objective")
        if (intentData.constraints.evidence.isEmpty()) gaps.add("constraints")
        if (intentData.environment.evidence.isEmpty()) gaps.add("environment")
        if (intentData.resources.evidence.isEmpty())   gaps.add("resources")
        return GapResult(hasGaps = gaps.isNotEmpty(), gaps = gaps)
    }
}
