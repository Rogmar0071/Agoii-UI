package com.agoii.mobile.irs.reality

import com.agoii.mobile.irs.CredibilityReport
import com.agoii.mobile.irs.EvidenceRef
import com.agoii.mobile.irs.IntentData
import com.agoii.mobile.irs.KnowledgeFact

/**
 * CredibilityScorer — scores the credibility of each mandatory intent field's evidence
 * against knowledge facts retrieved from the [RealityKnowledgeGateway].
 *
 * IRS-05 Contract Rules:
 *  - Operates independently; does NOT call any other IRS module.
 *  - Scoring is fully deterministic — same input always yields same output.
 *  - Fields with no matching knowledge facts receive a neutral score of 0.5
 *    (neither boosted nor penalised when the gateway has nothing relevant).
 *  - Fields with LOW evidence quality receive a score reduction.
 *  - The [CredibilityReport.isAcceptable] threshold is 0.5 overall AND per field.
 *
 * Scoring algorithm (per field):
 *  1. Base score = average of [KnowledgeFact.credibilityScore] for all retrieved facts.
 *     When no facts are retrieved the base is 0.5 (unknown → neutral, not failure).
 *  2. Evidence bonus: +0.1 per unique evidence source that contains a domain keyword
 *     (capped at +0.2 total bonus).
 *  3. Unavailability penalty: −0.3 when the field value signals unavailability.
 *  4. Final score is clamped to [0.0, 1.0].
 */
class CredibilityScorer(
    private val gateway: RealityKnowledgeGateway = RealityKnowledgeGateway()
) {

    companion object {
        private val UNAVAILABILITY_MARKERS = listOf("unavailable", "no_resource", "not available", "none", "n/a")
        private val DOMAIN_FIELD_MAP = mapOf(
            "objective"   to "objective",
            "constraints" to "constraint",
            "environment" to "environment",
            "resources"   to "resources"
        )
        private const val NEUTRAL_BASE          = 0.5
        private const val MAX_EVIDENCE_BONUS    = 0.2
        private const val EVIDENCE_BONUS_STEP   = 0.1
        private const val UNAVAILABILITY_PENALTY= 0.3
    }

    /**
     * Score all mandatory intent fields against retrieved knowledge facts.
     *
     * @param intent The intent to score.
     * @return [CredibilityReport] with per-field scores, overall score, and list of low-credibility fields.
     */
    fun score(intent: IntentData): CredibilityReport {
        val facts = gateway.queryAll(intent)

        val fields = mapOf(
            "objective"   to intent.objective,
            "constraints" to intent.constraints,
            "environment" to intent.environment,
            "resources"   to intent.resources
        )

        val fieldScores = fields.map { (name, field) ->
            val domain     = DOMAIN_FIELD_MAP[name] ?: name
            val domainFacts= facts[domain] ?: emptyList()
            name to scoreField(name, field.value, field.evidence, domainFacts)
        }.toMap()

        val overallScore = if (fieldScores.isEmpty()) 0.0
                           else fieldScores.values.average()

        val lowCredibilityFields = fieldScores
            .filter { (_, score) -> score < 0.5 }
            .keys.toList()

        return CredibilityReport(
            overallScore         = overallScore.coerceIn(0.0, 1.0),
            fieldScores          = fieldScores,
            lowCredibilityFields = lowCredibilityFields
        )
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private fun scoreField(
        fieldName:   String,
        value:       String,
        evidence:    List<EvidenceRef>,
        facts:       List<KnowledgeFact>
    ): Double {
        // Step 1: base score from knowledge facts (or neutral when none available)
        val base = if (facts.isEmpty()) NEUTRAL_BASE
                   else facts.map { it.credibilityScore }.average()

        // Step 2: evidence quality bonus (each relevant source adds a small boost)
        val domainKeywords = listOf(fieldName, DOMAIN_FIELD_MAP[fieldName] ?: fieldName)
        val relevantSourceCount = evidence.count { ref ->
            val src = ref.source.lowercase()
            domainKeywords.any { kw -> src.contains(kw) }
        }
        val bonus = (relevantSourceCount * EVIDENCE_BONUS_STEP).coerceAtMost(MAX_EVIDENCE_BONUS)

        // Step 3: unavailability penalty
        val lv = value.lowercase()
        val penalty = if (UNAVAILABILITY_MARKERS.any { lv.contains(it) }) UNAVAILABILITY_PENALTY else 0.0

        return (base + bonus - penalty).coerceIn(0.0, 1.0)
    }
}
