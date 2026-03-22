package com.agoii.mobile.irs.admission

import com.agoii.mobile.irs.ExecutionGraph
import com.agoii.mobile.irs.IntentData

/**
 * ExecutionGraphBuilder — derives a deterministic [ExecutionGraph] from [IntentData].
 *
 * ELVC Contract:
 *  - N (nodes)      = count of mandatory intent fields that have a non-blank value.
 *  - E (edges)      = sequential dependency edges in the linear execution chain = max(0, N − 1).
 *  - C (crossLinks) = number of distinct evidence-ref IDs that appear in more than one field.
 *
 * All calculations are integer-based, rule-based, and fully reproducible.
 * No weights, probabilities, or ML scoring are used.
 */
object ExecutionGraphBuilder {

    /**
     * Build an [ExecutionGraph] from [intentData].
     *
     * @param intentData The current intent (post-scouting, pre-admission).
     * @return A deterministic [ExecutionGraph] encoding the structural complexity of the intent.
     */
    fun build(intentData: IntentData): ExecutionGraph {
        val fields = listOf(
            intentData.objective,
            intentData.constraints,
            intentData.environment,
            intentData.resources
        )

        // N: active atomic tasks — fields with a non-blank value.
        val n = fields.count { it.value.isNotBlank() }

        // E: sequential dependency edges — linear chain has (n − 1) edges.
        val e = if (n > 1) n - 1 else 0

        // C: cross-dependency links — evidence ref IDs shared across two or more fields.
        val fieldRefSets = fields.map { field -> field.evidence.map { it.id }.toSet() }
        val allIds = fieldRefSets.flatten().toSet()
        val c = allIds.count { id -> fieldRefSets.count { refs -> id in refs } > 1 }

        return ExecutionGraph(nodes = n, edges = e, crossLinks = c)
    }
}
