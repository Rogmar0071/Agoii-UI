package com.agoii.mobile.irs

/**
 * ScoutOrchestrator — Step 3 of the IRS execution graph (conditional).
 *
 * Single responsibility: attempt to fill evidence gaps by searching a supplementary
 * evidence pool.  Scouting is deterministic — no external or probabilistic calls.
 *
 * Rules:
 *  - Only called when GapDetector has found gaps.
 *  - Enriches the intent only with evidence from the explicitly supplied pool.
 *  - Cannot manufacture evidence; signals failure when a gap cannot be filled.
 *  - Does NOT call any other IRS module.
 */
class ScoutOrchestrator {

    /**
     * @property enriched      true when at least one gap field received new evidence.
     * @property updatedIntent The intent after scouting (may be unchanged if no evidence found).
     * @property scoutedFields Fields that received at least one new [EvidenceRef].
     */
    data class ScoutResult(
        val enriched:      Boolean,
        val updatedIntent: IntentData,
        val scoutedFields: List<String>
    )

    /**
     * Attempt to fill evidence for [gaps] using [availableEvidence].
     *
     * @param intentData       Intent to enrich.
     * @param gaps             Field names that were flagged by [GapDetector].
     * @param availableEvidence Supplementary evidence pool keyed by field name.
     */
    fun scout(
        intentData:        IntentData,
        gaps:              List<String>,
        availableEvidence: Map<String, List<EvidenceRef>> = emptyMap()
    ): ScoutResult {
        val scoutedFields = mutableListOf<String>()

        fun enrich(field: IntentField, name: String): IntentField {
            if (name !in gaps) return field
            val extra = availableEvidence[name] ?: emptyList()
            if (extra.isEmpty()) return field
            scoutedFields.add(name)
            return field.copy(evidence = field.evidence + extra)
        }

        val updated = IntentData(
            objective   = enrich(intentData.objective,   "objective"),
            constraints = enrich(intentData.constraints, "constraints"),
            environment = enrich(intentData.environment, "environment"),
            resources   = enrich(intentData.resources,   "resources")
        )
        return ScoutResult(
            enriched      = scoutedFields.isNotEmpty(),
            updatedIntent = updated,
            scoutedFields = scoutedFields
        )
    }
}
