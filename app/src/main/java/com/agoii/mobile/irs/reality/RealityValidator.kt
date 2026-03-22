package com.agoii.mobile.irs.reality

import com.agoii.mobile.irs.IntentData
import com.agoii.mobile.irs.RealityValidationResult

/**
 * RealityValidator — coordinates knowledge retrieval, evidence scoring, contradiction
 * detection, and real-world simulation to produce a single [RealityValidationResult].
 *
 * IRS-05 Contract Rules:
 *  - This is the ONLY class in `irs/reality/` that the orchestrator calls directly.
 *  - [EvidenceScoringEngine], [ContradictionEngine], and [RealitySimulationEngine] are
 *    called internally; they do NOT interact with each other.
 *  - The result is fully traceable: all issues reference specific fields or facts.
 *  - Passes only when credibility is acceptable AND no contradictions exist AND
 *    simulation reports feasible.
 *  - Deterministic: same intent input always yields the same result.
 *
 * Failure conditions (any one causes [RealityValidationResult.passed] = false):
 *  1. Credibility report is not acceptable ([CredibilityReport.isAcceptable] = false).
 *  2. At least one contradiction is detected ([ContradictionReport.hasContradictions] = true).
 *  3. Reality simulation reports infeasible ([RealitySimulationResult.feasible] = false).
 */
class RealityValidator(
    private val gateway:                RealityKnowledgeGateway = RealityKnowledgeGateway(),
    private val evidenceScoringEngine:  EvidenceScoringEngine   = EvidenceScoringEngine(),
    private val contradictionEngine:    ContradictionEngine     = ContradictionEngine(),
    private val realitySimulationEngine: RealitySimulationEngine = RealitySimulationEngine()
) {

    /**
     * Validate [intent] against the reality knowledge layer.
     *
     * @param intent The intent to validate (post evidence-validation, pre swarm-validation).
     * @return [RealityValidationResult] capturing all credibility, contradiction, and simulation findings.
     */
    fun validate(intent: IntentData): RealityValidationResult {
        val credibilityReport    = evidenceScoringEngine.score(intent)
        val contradictionReport  = contradictionEngine.detect(intent)
        val simulationResult     = realitySimulationEngine.simulate(intent)

        val issues = mutableListOf<String>()

        if (!credibilityReport.isAcceptable) {
            val low = credibilityReport.lowCredibilityFields
            issues.add(
                "reality: credibility check failed — overall score " +
                "%.2f".format(credibilityReport.overallScore) +
                (if (low.isNotEmpty()) "; low-credibility fields: ${low.joinToString(", ")}" else "")
            )
        }

        if (contradictionReport.hasContradictions) {
            contradictionReport.contradictions.forEach { c ->
                issues.add("reality: contradiction detected between [${c.fieldA}] and [${c.fieldB}] — ${c.description}")
            }
        }

        if (!simulationResult.feasible) {
            simulationResult.failurePoints.forEach { fp ->
                issues.add(fp)
            }
        }

        return RealityValidationResult(
            passed              = issues.isEmpty(),
            credibilityReport   = credibilityReport,
            contradictionReport = contradictionReport,
            issues              = issues
        )
    }
}

