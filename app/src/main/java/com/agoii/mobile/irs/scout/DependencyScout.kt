package com.agoii.mobile.irs.scout

import com.agoii.mobile.irs.Finding
import com.agoii.mobile.irs.IntentData
import com.agoii.mobile.irs.ScoutEvidence
import com.agoii.mobile.irs.ScoutType

/**
 * DependencyScout — Knowledge Scout for the resources and constraints fields.
 *
 * Single responsibility: discover explicit tool, system, and service dependencies
 * mentioned in the intent's resources and constraints fields.
 *
 * Rules:
 *  - Operates independently; does NOT call any other IRS module or scout.
 *  - Produces structured [ScoutEvidence] with a traceable confidence score.
 *  - NO guessing: dependencies mentioned but unverifiable → LOW confidence.
 *  - Only marks as HIGH confidence when named dependencies are from the known catalogue
 *    AND evidence is present.
 */
class DependencyScout {

    companion object {
        /** Tool / service keywords the scout recognises by name. */
        private val KNOWN_TOOLS = setOf(
            "gradle", "maven", "npm", "yarn", "docker", "kubernetes", "terraform",
            "ansible", "jenkins", "github", "gitlab", "bitbucket", "jira",
            "postgresql", "mysql", "redis", "kafka", "rabbitmq", "nginx",
            "aws", "gcp", "azure", "firebase", "supabase", "fastapi", "spring",
            "retrofit", "okhttp", "compose", "react", "angular", "vue"
        )
        private const val HIGH_CONFIDENCE   = 0.85
        private const val MEDIUM_CONFIDENCE = 0.55
        private const val LOW_CONFIDENCE    = 0.25
    }

    /**
     * Scout the resources and constraints fields of [intentData] for dependency declarations.
     *
     * Confidence levels:
     *  - HIGH   (0.85): ≥ 1 named known tool/service found AND field has evidence.
     *  - MEDIUM (0.55): known tools found but no evidence, OR non-blank fields with evidence
     *                   but no recognised tool name.
     *  - LOW    (0.25): both fields are blank or have no recognised content and no evidence.
     */
    fun scout(intentData: IntentData): ScoutEvidence {
        val resourcesValue    = intentData.resources.value.lowercase()
        val constraintsValue  = intentData.constraints.value.lowercase()
        val combined          = "$resourcesValue $constraintsValue"
        val hasEvidence       = intentData.resources.evidence.isNotEmpty() ||
                                intentData.constraints.evidence.isNotEmpty()
        val trace             = mutableListOf<String>()
        val findings          = mutableListOf<Finding>()

        trace.add("resources.value=${intentData.resources.value}")
        trace.add("constraints.value=${intentData.constraints.value}")

        val matchedTools = KNOWN_TOOLS.filter { combined.contains(it) }

        if (matchedTools.isEmpty()) {
            trace.add("tool-matches=none")
            findings.add(Finding("no named tool or service dependencies detected", "WARNING"))
            return if (hasEvidence) {
                trace.add("evidence-present=true")
                ScoutEvidence(ScoutType.DEPENDENCY, findings, MEDIUM_CONFIDENCE, trace)
            } else {
                trace.add("evidence-present=false")
                findings.add(Finding("dependency landscape is completely unverifiable", "ERROR"))
                ScoutEvidence(ScoutType.DEPENDENCY, findings, LOW_CONFIDENCE, trace)
            }
        }

        matchedTools.forEach { tool ->
            trace.add("tool-match=$tool")
            findings.add(Finding("dependency identified: $tool", "INFO"))
        }

        return if (hasEvidence) {
            trace.add("evidence-present=true")
            ScoutEvidence(ScoutType.DEPENDENCY, findings, HIGH_CONFIDENCE, trace)
        } else {
            trace.add("evidence-present=false")
            findings.add(Finding("named dependencies found but lack backing evidence", "WARNING"))
            ScoutEvidence(ScoutType.DEPENDENCY, findings, MEDIUM_CONFIDENCE, trace)
        }
    }
}
