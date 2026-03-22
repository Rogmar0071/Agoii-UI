package com.agoii.mobile.irs.reality

import com.agoii.mobile.irs.Contradiction
import com.agoii.mobile.irs.ContradictionReport
import com.agoii.mobile.irs.IntentData
import com.agoii.mobile.irs.KnowledgeFact

/**
 * ContradictionDetector — cross-source, cross-field contradiction detection.
 *
 * IRS-05 Contract Rules:
 *  - Operates independently; does NOT call any other IRS module.
 *  - Detection is fully deterministic — same input always yields same output.
 *  - All contradictions are traced to specific field pairs and knowledge facts.
 *  - NO guessing: only flags contradictions when a rule matches known patterns.
 *
 * Detection rules (applied in order):
 *  1. Knowledge-fact conflict: a retrieved fact explicitly contradicts a field value
 *     (e.g. fact says "serverless cannot maintain persistent connections" while
 *      constraint says "must maintain persistent connection").
 *  2. Cross-field semantic conflict: pairs of field values that are semantically
 *     incompatible (e.g. "offline" in constraints + "cloud-only" environment).
 *  3. Resource-constraint conflict: resources signal unavailability while constraints
 *     declare requirements that depend on those resources.
 */
class ContradictionDetector(
    private val gateway: RealityKnowledgeGateway = RealityKnowledgeGateway()
) {

    companion object {
        // ── Knowledge-fact conflict markers ─────────────────────────────────
        private data class FactConflictRule(
            val factKeyword:  String,   // keyword that must appear in a retrieved fact's claim
            val fieldName:    String,   // intent field whose value is checked
            val valueKeyword: String,   // keyword that must appear in the field value
            val description:  String    // human-readable contradiction description
        )

        private val FACT_CONFLICT_RULES = listOf(
            FactConflictRule("cold-start",   "constraints", "real-time",
                "serverless cold-start latency conflicts with real-time constraint"),
            FactConflictRule("persistent connections", "environment", "serverless",
                "serverless environment cannot support persistent connections as required by constraints"),
            FactConflictRule("internet connectivity", "constraints", "offline",
                "cloud environment requires internet connectivity — incompatible with offline constraint"),
            FactConflictRule("stateless",    "constraints", "stateful",
                "horizontal scaling requires stateless design — conflicts with stateful constraint")
        )

        // ── Cross-field semantic conflict pairs ──────────────────────────────
        private data class SemanticConflictRule(
            val fieldA:       String,
            val keywordA:     String,
            val fieldB:       String,
            val keywordB:     String,
            val description:  String
        )

        private val SEMANTIC_CONFLICT_RULES = listOf(
            SemanticConflictRule("constraints", "offline",  "environment", "cloud",
                "offline constraint is incompatible with cloud-only environment"),
            SemanticConflictRule("constraints", "offline",  "environment", "aws",
                "offline constraint is incompatible with AWS environment"),
            SemanticConflictRule("constraints", "offline",  "environment", "gcp",
                "offline constraint is incompatible with GCP environment"),
            SemanticConflictRule("constraints", "offline",  "environment", "azure",
                "offline constraint is incompatible with Azure environment"),
            SemanticConflictRule("constraints", "real-time","environment", "serverless",
                "real-time constraint is incompatible with serverless environment (cold starts)"),
            SemanticConflictRule("constraints", "zero-latency", "environment", "serverless",
                "zero-latency constraint is incompatible with serverless environment"),
            SemanticConflictRule("resources",  "unavailable", "constraints", "",
                "resource unavailability conflicts with non-empty constraints")
        )
    }

    /**
     * Detect contradictions across intent fields using knowledge facts and semantic rules.
     *
     * @param intent The intent to inspect.
     * @return [ContradictionReport] with all detected contradictions (may be empty).
     */
    fun detect(intent: IntentData): ContradictionReport {
        val contradictions = mutableListOf<Contradiction>()
        val facts = gateway.queryAll(intent)

        detectFactConflicts(intent, facts, contradictions)
        detectSemanticConflicts(intent, contradictions)

        return ContradictionReport(
            hasContradictions = contradictions.isNotEmpty(),
            contradictions    = contradictions.toList()
        )
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private fun detectFactConflicts(
        intent:         IntentData,
        facts:          Map<String, List<KnowledgeFact>>,
        contradictions: MutableList<Contradiction>
    ) {
        val allFacts = facts.values.flatten()
        val fieldValues = mapOf(
            "objective"   to intent.objective.value.lowercase(),
            "constraints" to intent.constraints.value.lowercase(),
            "environment" to intent.environment.value.lowercase(),
            "resources"   to intent.resources.value.lowercase()
        )

        for (rule in FACT_CONFLICT_RULES) {
            val fieldValue = fieldValues[rule.fieldName] ?: continue
            if (!fieldValue.contains(rule.valueKeyword)) continue

            // Check whether any retrieved fact contains the conflicting keyword.
            val conflictingFact = allFacts.firstOrNull { fact ->
                fact.claim.lowercase().contains(rule.factKeyword)
            } ?: continue

            contradictions.add(
                Contradiction(
                    fieldA      = rule.fieldName,
                    fieldB      = "environment",
                    description = "${rule.description} [fact: \"${conflictingFact.claim}\"]"
                )
            )
        }
    }

    private fun detectSemanticConflicts(
        intent:         IntentData,
        contradictions: MutableList<Contradiction>
    ) {
        val fieldValues = mapOf(
            "objective"   to intent.objective.value.lowercase(),
            "constraints" to intent.constraints.value.lowercase(),
            "environment" to intent.environment.value.lowercase(),
            "resources"   to intent.resources.value.lowercase()
        )

        for (rule in SEMANTIC_CONFLICT_RULES) {
            val valueA = fieldValues[rule.fieldA] ?: continue
            val valueB = fieldValues[rule.fieldB] ?: continue

            val aMatches = valueA.contains(rule.keywordA)
            // For the resource-unavailability rule keywordB is empty — check non-blank instead.
            val bMatches = if (rule.keywordB.isEmpty()) valueB.isNotBlank()
                           else valueB.contains(rule.keywordB)

            if (aMatches && bMatches) {
                // Avoid duplicate contradictions for the same field pair + description.
                val alreadyDetected = contradictions.any {
                    it.fieldA == rule.fieldA && it.fieldB == rule.fieldB &&
                    it.description == rule.description
                }
                if (!alreadyDetected) {
                    contradictions.add(
                        Contradiction(
                            fieldA      = rule.fieldA,
                            fieldB      = rule.fieldB,
                            description = rule.description
                        )
                    )
                }
            }
        }
    }
}
