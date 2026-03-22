package com.agoii.mobile.irs

/**
 * EvidenceValidator — validates evidence on four required dimensions.
 *
 * Validation dimensions (all four must pass for [EvidenceValidationResult.valid] = true):
 *  1. Presence    — ≥ 1 EvidenceRef per mandatory field (pre-enforced by GapDetector;
 *                   this validator re-confirms as a safety gate).
 *  2. Relevance   — each EvidenceRef.source contains a recognisable keyword related to
 *                   the field it backs (e.g., "env" or "environment" for the environment field).
 *  3. Coverage    — evidence is not concentrated on a single source; ≥ 1 unique source
 *                   is required when the field value is non-trivial (> 3 words).
 *  4. Consistency — no two EvidenceRef items for the same field share the same id
 *                   (duplicate refs would indicate copy-paste fabrication).
 *
 * Rules:
 *  - Single responsibility: only validates; never mutates intent or evidence.
 *  - Does NOT call any other IRS module.
 *  - Every detected issue is reported in [EvidenceValidationResult.reasons].
 */
class EvidenceValidator {

    companion object {
        /** Field name → list of source keywords considered relevant for that field. */
        private val FIELD_SOURCE_KEYWORDS = mapOf(
            "objective"   to listOf("objective", "goal", "intent", "purpose", "requirement", "spec"),
            "constraints" to listOf("constraint", "limit", "restriction", "rule", "policy", "bound"),
            "environment" to listOf("env", "environment", "platform", "infra", "cloud", "runtime", "host"),
            "resources"   to listOf("resource", "team", "budget", "tool", "asset", "system", "service")
        )
    }

    /**
     * Validate all four evidence dimensions for [intentData].
     *
     * @param intentData The intent whose evidence will be inspected.
     * @return [EvidenceValidationResult] with [EvidenceValidationResult.valid] = true only
     *         when all four dimensions pass for every mandatory field.
     */
    fun validate(intentData: IntentData): EvidenceValidationResult {
        val issues = mutableListOf<String>()

        val fields = mapOf(
            "objective"   to intentData.objective,
            "constraints" to intentData.constraints,
            "environment" to intentData.environment,
            "resources"   to intentData.resources
        )

        fields.forEach { (name, field) ->
            checkPresence(name, field, issues)
            checkRelevance(name, field, issues)
            checkCoverage(name, field, issues)
            checkConsistency(name, field, issues)
        }

        return EvidenceValidationResult(valid = issues.isEmpty(), reasons = issues)
    }

    // ─── Dimension checks ─────────────────────────────────────────────────────

    /** Dimension 1: Presence — at least one EvidenceRef must exist. */
    private fun checkPresence(
        fieldName: String,
        field:     IntentField,
        issues:    MutableList<String>
    ) {
        if (field.evidence.isEmpty()) {
            issues.add("[$fieldName] presence: no evidence references found")
        }
    }

    /**
     * Dimension 2: Relevance — at least one EvidenceRef source must contain a keyword
     * associated with the field's domain.
     */
    private fun checkRelevance(
        fieldName: String,
        field:     IntentField,
        issues:    MutableList<String>
    ) {
        if (field.evidence.isEmpty()) return   // already caught by checkPresence
        val keywords = FIELD_SOURCE_KEYWORDS[fieldName] ?: return
        val relevant = field.evidence.any { ref ->
            val src = ref.source.lowercase()
            keywords.any { kw -> src.contains(kw) }
        }
        if (!relevant) {
            issues.add(
                "[$fieldName] relevance: no evidence source matches domain keywords " +
                "${keywords.take(3).joinToString("|")} — evidence may be misattributed"
            )
        }
    }

    /**
     * Dimension 3: Coverage — when the field value is substantial (> 3 words),
     * at least 2 distinct evidence sources must be present to avoid single-source bias.
     */
    private fun checkCoverage(
        fieldName: String,
        field:     IntentField,
        issues:    MutableList<String>
    ) {
        if (field.evidence.isEmpty()) return
        val wordCount = field.value.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
        if (wordCount > 3) {
            val uniqueSources = field.evidence.map { it.source }.toSet()
            if (uniqueSources.size < 2) {
                issues.add(
                    "[$fieldName] coverage: field has $wordCount words but only " +
                    "${uniqueSources.size} distinct evidence source(s) — " +
                    "add a second source to improve coverage"
                )
            }
        }
    }

    /**
     * Dimension 4: Consistency — no two EvidenceRef items in the same field may
     * share the same [EvidenceRef.id] (duplicate ids suggest fabricated evidence).
     */
    private fun checkConsistency(
        fieldName: String,
        field:     IntentField,
        issues:    MutableList<String>
    ) {
        if (field.evidence.size < 2) return
        val seen     = mutableSetOf<String>()
        val duplicates = mutableListOf<String>()
        field.evidence.forEach { ref ->
            if (!seen.add(ref.id)) duplicates.add(ref.id)
        }
        if (duplicates.isNotEmpty()) {
            issues.add(
                "[$fieldName] consistency: duplicate evidence id(s) detected — " +
                duplicates.joinToString(", ")
            )
        }
    }
}
