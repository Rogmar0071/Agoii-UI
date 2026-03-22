package com.agoii.mobile.irs.reality

import com.agoii.mobile.irs.IntentData
import com.agoii.mobile.irs.RealityValidationResult
import com.agoii.mobile.irs.RiskLevel

/**
 * RealityValidator — pure aggregator for the REALITY_VALIDATION stage.
 *
 * IRS-05C Contract Rules:
 *  - This is the ONLY class in `irs/reality/` that the orchestrator calls directly.
 *  - [EvidenceScoringEngine], [ContradictionEngine], and [RealitySimulationEngine] are
 *    called independently; they do NOT interact with each other.
 *  - The validator contains NO scoring, detection, or threshold logic of its own.
 *    All [RealityValidationResult.reasons] are sourced directly from sub-module outputs.
 *  - [RealityValidationResult.riskLevel] is derived from a fixed boolean rule table
 *    (no subjective score thresholds).
 *  - [RealityValidationResult.confidence] = [CredibilityReport.overallScore] directly;
 *    no multipliers or adjustments (that is the scoring engine's responsibility).
 *  - Deterministic: same intent input always yields the same result.
 *
 * Output derivation (strict rule table — no implicit logic):
 *  - reasons    = credibilityReport.reasons + contradictionReport.reasons + simulationResult.failurePoints
 *  - valid      = reasons.isEmpty()
 *  - confidence = credibilityReport.overallScore  (no adjustment)
 *  - riskLevel:
 *      HIGH   — contradictions detected OR simulation infeasible
 *      MEDIUM — credibility NOT acceptable (isAcceptable == false)
 *      LOW    — all checks pass
 */
class RealityValidator(
    private val gateway:                 RealityKnowledgeGateway  = RealityKnowledgeGateway(),
    private val evidenceScoringEngine:   EvidenceScoringEngine    = EvidenceScoringEngine(),
    private val contradictionEngine:     ContradictionEngine      = ContradictionEngine(),
    private val realitySimulationEngine: RealitySimulationEngine  = RealitySimulationEngine()
) {

    /**
     * Aggregate the outputs of all three reality sub-modules into a single graded result.
     *
     * @param intent The intent to validate (post evidence-validation, pre swarm-validation).
     * @return [RealityValidationResult] with graded risk, direct confidence, and full traceability.
     */
    fun validate(intent: IntentData): RealityValidationResult {
        val credibilityReport   = evidenceScoringEngine.score(intent)
        val contradictionReport = contradictionEngine.detect(intent)
        val simulationResult    = realitySimulationEngine.simulate(intent)

        // Pure aggregation — no inline logic; all strings come from sub-module outputs.
        val reasons = credibilityReport.reasons +
                      contradictionReport.reasons +
                      simulationResult.failurePoints

        val valid = reasons.isEmpty()

        // Rule table — no score thresholds, no subjective interpretation.
        val riskLevel: RiskLevel = when {
            contradictionReport.hasContradictions || !simulationResult.feasible -> RiskLevel.HIGH
            !credibilityReport.isAcceptable                                     -> RiskLevel.MEDIUM
            else                                                                -> RiskLevel.LOW
        }

        // Direct: confidence is the scoring engine's output — no adjustment by the aggregator.
        val confidence: Double = credibilityReport.overallScore

        return RealityValidationResult(
            valid               = valid,
            riskLevel           = riskLevel,
            confidence          = confidence,
            reasons             = reasons,
            credibilityReport   = credibilityReport,
            contradictionReport = contradictionReport,
            simulationResult    = simulationResult
        )
    }
}


