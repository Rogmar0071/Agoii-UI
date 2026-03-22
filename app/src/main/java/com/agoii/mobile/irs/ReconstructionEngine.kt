package com.agoii.mobile.irs

/**
 * ReconstructionEngine — Step 1 of the IRS execution graph.
 *
 * Single responsibility: produce a structured [IntentData] from raw input and
 * evidence mappings.  No stage-to-stage communication; pure transformation only.
 *
 * Rules:
 *  - Accepts raw field strings and pre-mapped evidence refs.
 *  - Missing raw fields default to empty string (gap detection will flag them).
 *  - Does NOT call any other IRS module.
 */
class ReconstructionEngine {

    /**
     * Reconstruct a fully structured [IntentData] from raw field values and an
     * evidence mapping.
     *
     * @param rawFields Map of field name → raw string value.
     *                  Expected keys: "objective", "constraints", "environment", "resources".
     * @param evidence  Map of field name → list of [EvidenceRef] that back that field.
     */
    fun reconstruct(
        rawFields: Map<String, String>,
        evidence:  Map<String, List<EvidenceRef>>
    ): IntentData = IntentData(
        objective   = field("objective",   rawFields, evidence),
        constraints = field("constraints", rawFields, evidence),
        environment = field("environment", rawFields, evidence),
        resources   = field("resources",   rawFields, evidence)
    )

    private fun field(
        name:      String,
        rawFields: Map<String, String>,
        evidence:  Map<String, List<EvidenceRef>>
    ): IntentField = IntentField(
        value    = rawFields[name] ?: "",
        evidence = evidence[name]  ?: emptyList()
    )
}
