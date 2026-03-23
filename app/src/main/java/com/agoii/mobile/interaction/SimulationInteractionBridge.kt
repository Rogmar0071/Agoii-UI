package com.agoii.mobile.interaction

import com.agoii.mobile.simulation.SimulationResult

/**
 * Converts a [SimulationResult] into an Interaction-compatible key-value map.
 *
 * Purpose: bridge the Simulation System to the Interaction Contract System without
 * coupling the two packages directly.  All [SimulationResult] fields are preserved
 * verbatim so downstream components — [InteractionMapper], [InteractionFormatter] —
 * work with plain strings and remain independent of the simulation package.
 *
 * Laws:
 *  - Pure function: no I/O, no side effects.
 *  - The returned map is immutable.
 *  - List fields are joined with "; " so they round-trip through a single string value.
 */
class SimulationInteractionBridge {

    /**
     * Map all fields of [result] to a flat, string-keyed map.
     *
     * Key inventory:
     *  - "contractId"    — mirrors [SimulationResult.contractId]
     *  - "mode"          — [SimulationResult.mode] name (e.g. "FEASIBILITY")
     *  - "feasible"      — "true" or "false"
     *  - "confidence"    — decimal string in [0.0, 1.0]
     *  - "findings"      — findings joined with "; " (empty string when list is empty)
     *  - "failurePoints" — failure points joined with "; " (empty string when list is empty)
     *  - "scenarios"     — scenarios joined with "; " (empty string when list is empty)
     */
    fun map(result: SimulationResult): Map<String, String> = mapOf(
        "contractId"    to result.contractId,
        "mode"          to result.mode.name,
        "feasible"      to result.feasible.toString(),
        "confidence"    to result.confidence.toString(),
        "findings"      to result.findings.joinToString("; "),
        "failurePoints" to result.failurePoints.joinToString("; "),
        "scenarios"     to result.scenarios.joinToString("; ")
    )
}