package com.agoii.mobile.irs.scout

import com.agoii.mobile.irs.Finding
import com.agoii.mobile.irs.IntentData
import com.agoii.mobile.irs.ScoutEvidence
import com.agoii.mobile.irs.ScoutType

/**
 * ConstraintScout — Knowledge Scout for the constraints field.
 *
 * Single responsibility: verify that declared constraints are realistic and
 * internally consistent with the rest of the intent (objective, environment,
 * resources).
 *
 * Rules:
 *  - Operates independently; does NOT call any other IRS module or scout.
 *  - Produces structured [ScoutEvidence] with a traceable confidence score.
 *  - NO guessing: unknown or vague constraints → confidence ≤ 0.4 (LOW).
 *  - Contradictions between constraints and resources always produce an ERROR finding.
 *
 * Contradiction detection heuristics (deterministic text-based):
 *  1. Resources signal unavailability while constraints express requirements.
 *  2. Constraints require "real-time" or "zero-latency" but environment is not a
 *     low-latency platform (e.g., serverless with cold starts).
 *  3. Constraints declare "offline" capability but environment is cloud-only.
 */
class ConstraintScout {

    companion object {
        private val UNAVAILABILITY_MARKERS = listOf("unavailable", "no_resource", "not available", "none")
        private val REALTIME_MARKERS       = listOf("real-time", "realtime", "zero-latency", "zero latency")
        private val OFFLINE_MARKERS        = listOf("offline", "no internet", "air-gapped")
        private val CLOUD_ONLY_MARKERS     = listOf("cloud", "aws", "gcp", "azure", "serverless")

        private const val HIGH_CONFIDENCE   = 0.88
        private const val MEDIUM_CONFIDENCE = 0.58
        private const val LOW_CONFIDENCE    = 0.22
    }

    /**
     * Scout the constraints of [intentData] for realism and internal consistency.
     *
     * Confidence levels:
     *  - HIGH   (0.88): no contradictions detected AND constraints are non-blank with evidence.
     *  - MEDIUM (0.58): no contradictions detected but constraints are vague or lack evidence.
     *  - LOW    (0.22): constraints are blank, or contradictions are found.
     */
    fun scout(intentData: IntentData): ScoutEvidence {
        val constraintValue = intentData.constraints.value.lowercase()
        val resourceValue   = intentData.resources.value.lowercase()
        val environmentValue= intentData.environment.value.lowercase()
        val hasEvidence     = intentData.constraints.evidence.isNotEmpty()
        val trace           = mutableListOf<String>()
        val findings        = mutableListOf<Finding>()

        trace.add("constraints.value=${intentData.constraints.value}")
        trace.add("resources.value=${intentData.resources.value}")
        trace.add("environment.value=${intentData.environment.value}")

        if (constraintValue.isBlank()) {
            findings.add(Finding("constraints field is blank — cannot validate realism", "ERROR"))
            trace.add("constraints.blank=true")
            return ScoutEvidence(ScoutType.CONSTRAINT, findings, LOW_CONFIDENCE, trace)
        }

        val contradictions = detectContradictions(
            constraintValue, resourceValue, environmentValue, trace, findings
        )

        return when {
            contradictions > 0 -> {
                ScoutEvidence(ScoutType.CONSTRAINT, findings, LOW_CONFIDENCE, trace)
            }
            hasEvidence -> {
                trace.add("evidence-present=true")
                findings.add(Finding("constraints appear realistic and evidence-backed", "INFO"))
                ScoutEvidence(ScoutType.CONSTRAINT, findings, HIGH_CONFIDENCE, trace)
            }
            else -> {
                trace.add("evidence-present=false")
                findings.add(Finding("constraints look plausible but lack backing evidence", "WARNING"))
                ScoutEvidence(ScoutType.CONSTRAINT, findings, MEDIUM_CONFIDENCE, trace)
            }
        }
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    /**
     * Run all contradiction checks. Returns the total number of contradictions found.
     * Appends ERROR findings and trace entries for each contradiction detected.
     */
    private fun detectContradictions(
        constraintValue:  String,
        resourceValue:    String,
        environmentValue: String,
        trace:            MutableList<String>,
        findings:         MutableList<Finding>
    ): Int {
        var count = 0

        // Contradiction 1: resources unavailable while constraints express requirements
        val resourceUnavailable = UNAVAILABILITY_MARKERS.any { resourceValue.contains(it) }
        if (resourceUnavailable && constraintValue.isNotBlank()) {
            trace.add("contradiction=resource-unavailable-vs-constraints")
            findings.add(
                Finding(
                    "resource unavailability contradicts non-empty constraint requirements",
                    "ERROR"
                )
            )
            count++
        }

        // Contradiction 2: real-time required but environment is serverless (cold starts)
        val requiresRealTime = REALTIME_MARKERS.any { constraintValue.contains(it) }
        val isServerless     = environmentValue.contains("serverless")
        if (requiresRealTime && isServerless) {
            trace.add("contradiction=realtime-vs-serverless")
            findings.add(
                Finding(
                    "real-time constraint is incompatible with serverless environment (cold starts)",
                    "ERROR"
                )
            )
            count++
        }

        // Contradiction 3: offline required but environment is cloud-only
        val requiresOffline  = OFFLINE_MARKERS.any { constraintValue.contains(it) }
        val isCloudOnly      = CLOUD_ONLY_MARKERS.any { environmentValue.contains(it) }
        if (requiresOffline && isCloudOnly) {
            trace.add("contradiction=offline-vs-cloud")
            findings.add(
                Finding(
                    "offline constraint is incompatible with cloud-based environment",
                    "ERROR"
                )
            )
            count++
        }

        return count
    }
}
