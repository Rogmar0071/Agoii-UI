package com.agoii.mobile.irs.reality

import com.agoii.mobile.irs.IntentData
import com.agoii.mobile.irs.RealityValidationResult
import com.agoii.mobile.irs.RiskLevel

/**
 * RealityValidator — pure aggregator for the REALITY_VALIDATION stage.
 *
 * IRS-05B Contract Rules:
 *  - This is the ONLY class in `irs/reality/` that the orchestrator calls directly.
 *  - [EvidenceScoringEngine], [ContradictionEngine], and [RealitySimulationEngine] are
 *    called independently; they do NOT interact with each other.
 *  - The validator contains NO scoring, detection, or formatting logic of its own.
 *    All [RealityValidationResult.reasons] are sourced directly from sub-module outputs.
 *  - [RealityValidationResult.riskLevel] and [RealityValidationResult.confidence] are
 *    derived mechanically from sub-module outputs — no hidden logic.
 *  - Deterministic: same intent input always yields the same result.
 *
 * Output derivation (pure aggregation):
 *  - reasons    = credibilityReport.reasons + contradictionReport.reasons + simulationResult.failurePoints
 *  - valid      = reasons.isEmpty()
 *  - riskLevel  = HIGH when contradictions or infeasible; MEDIUM when credibility concern; LOW otherwise.
 *  - confidence = credibilityReport.overallScore, adjusted ×0.75 on credibility failure, ×0.5 on hard failure.
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
     * @return [RealityValidationResult] with graded risk, confidence, and full traceability.
     */
    fun validate(intent: IntentData): RealityValidationResult {
        val credibilityReport   = evidenceScoringEngine.score(intent)
        val contradictionReport = contradictionEngine.detect(intent)
        val simulationResult    = realitySimulationEngine.simulate(intent)

        // Pure aggregation: collect reasons directly from sub-module outputs — no inline logic.
        val reasons = credibilityReport.reasons +
                      contradictionReport.reasons +
                      simulationResult.failurePoints

        val valid = reasons.isEmpty()

        // Risk level: graded from sub-module outcomes — no custom business logic.
        val riskLevel: RiskLevel = when {
            contradictionReport.hasContradictions || !simulationResult.feasible -> RiskLevel.HIGH
            !credibilityReport.isAcceptable                                     -> RiskLevel.MEDIUM
            credibilityReport.overallScore < 0.7                                -> RiskLevel.MEDIUM
            else                                                                -> RiskLevel.LOW
        }

        // Confidence: derived from credibility score, adjusted for hard failures.
        val confidence: Double = when {
            contradictionReport.hasContradictions || !simulationResult.feasible ->
                (credibilityReport.overallScore * 0.5).coerceIn(0.0, 1.0)
            !credibilityReport.isAcceptable ->
                (credibilityReport.overallScore * 0.75).coerceIn(0.0, 1.0)
            else -> credibilityReport.overallScore
        }

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


