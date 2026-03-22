package com.agoii.mobile.irs.scout

import com.agoii.mobile.irs.Finding
import com.agoii.mobile.irs.IntentData
import com.agoii.mobile.irs.ScoutEvidence
import com.agoii.mobile.irs.ScoutType

/**
 * EnvironmentScout — Knowledge Scout for the environment field.
 *
 * Single responsibility: verify that the declared runtime environment is
 * recognisable and that the required platform is available in the intent context.
 *
 * Rules:
 *  - Operates independently; does NOT call any other IRS module or scout.
 *  - Produces structured [ScoutEvidence] with a traceable [ScoutEvidence.confidence] score.
 *  - NO guessing: if the environment value is unknown → confidence ≤ 0.4 (LOW).
 *  - Every finding is traceable to a specific field or keyword match.
 */
class EnvironmentScout {

    companion object {
        /** Known platforms that can be positively identified from intent text. */
        private val KNOWN_PLATFORMS = setOf(
            "cloud", "aws", "gcp", "azure", "kubernetes", "k8s",
            "docker", "local", "on-premise", "on_premise", "on premise",
            "bare-metal", "baremetal", "serverless", "edge", "hybrid"
        )
        private const val HIGH_CONFIDENCE   = 0.9
        private const val MEDIUM_CONFIDENCE = 0.6
        private const val LOW_CONFIDENCE    = 0.2
    }

    /**
     * Scout the environment field of [intentData].
     *
     * Confidence levels:
     *  - HIGH   (0.9): environment value matches a known platform AND field has evidence.
     *  - MEDIUM (0.6): environment value matches a known platform but has no evidence, OR
     *                  environment is non-blank but unrecognised with evidence present.
     *  - LOW    (0.2): environment is blank, or unknown with no evidence.
     */
    fun scout(intentData: IntentData): ScoutEvidence {
        val envValue    = intentData.environment.value.lowercase().trim()
        val hasEvidence = intentData.environment.evidence.isNotEmpty()
        val trace       = mutableListOf<String>()
        val findings    = mutableListOf<Finding>()

        if (envValue.isBlank()) {
            findings.add(Finding("environment field is blank — platform cannot be verified", "ERROR"))
            trace.add("environment.value=blank")
            return ScoutEvidence(ScoutType.ENVIRONMENT, findings, LOW_CONFIDENCE, trace)
        }

        trace.add("environment.value=$envValue")

        val matchedPlatform = KNOWN_PLATFORMS.firstOrNull { envValue.contains(it) }
        if (matchedPlatform != null) {
            trace.add("platform-match=$matchedPlatform")
            findings.add(Finding("recognised platform: $matchedPlatform", "INFO"))
            if (hasEvidence) {
                trace.add("evidence-present=true")
                findings.add(Finding("environment evidence is present", "INFO"))
                return ScoutEvidence(ScoutType.ENVIRONMENT, findings, HIGH_CONFIDENCE, trace)
            } else {
                trace.add("evidence-present=false")
                findings.add(Finding("environment lacks backing evidence", "WARNING"))
                return ScoutEvidence(ScoutType.ENVIRONMENT, findings, MEDIUM_CONFIDENCE, trace)
            }
        } else {
            trace.add("platform-match=none")
            findings.add(Finding("unrecognised environment value: '$envValue'", "WARNING"))
            return if (hasEvidence) {
                trace.add("evidence-present=true")
                ScoutEvidence(ScoutType.ENVIRONMENT, findings, MEDIUM_CONFIDENCE, trace)
            } else {
                trace.add("evidence-present=false")
                findings.add(Finding("environment is unverifiable without evidence", "ERROR"))
                ScoutEvidence(ScoutType.ENVIRONMENT, findings, LOW_CONFIDENCE, trace)
            }
        }
    }
}
