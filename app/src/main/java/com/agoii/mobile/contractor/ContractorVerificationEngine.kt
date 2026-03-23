package com.agoii.mobile.contractor

// ─── ContractorVerificationEngine ────────────────────────────────────────────

/**
 * ContractorVerificationEngine — validates contractor candidates through controlled
 * tests before they are admitted to the [ContractorRegistry].
 *
 * Evaluation dimensions (all must pass MINIMUM_SCORE for VERIFIED outcome):
 *  1. Constraint obedience — extracted from capability claims.
 *  2. Structural accuracy  — extracted from capability claims.
 *  3. Output determinism   — extracted from capability claims.
 *  4. Complexity capacity  — extracted from capability claims.
 *  5. Reliability          — extracted from capability claims.
 *
 * Score extraction rules:
 *  - Each capability claim value is mapped to an integer score in [0, 3]:
 *      "high" / "3" → 3
 *      "medium" / "2" → 2
 *      "low" / "1" → 1
 *      anything else → 0
 *  - A candidate is VERIFIED when every dimension score is ≥ [MINIMUM_SCORE].
 *
 * Rules:
 *  - Pure function: no state, no side effects (events are returned, not emitted).
 *  - Equal inputs always produce equal outputs.
 */
class ContractorVerificationEngine {

    companion object {
        /** Minimum per-dimension score required for VERIFIED outcome. */
        const val MINIMUM_SCORE = 1
    }

    /**
     * Evaluate [candidate] and return the [ContractorVerificationResult].
     *
     * @param candidate The pre-discovery candidate to evaluate.
     * @return [ContractorVerificationResult] with status VERIFIED or REJECTED.
     */
    fun verify(candidate: ContractorCandidate): ContractorVerificationResult {
        val claims = candidate.capabilityClaims

        val constraintObedience = extractScore(claims["constraintObedience"] ?: claims["constraint_obedience"])
        val structuralAccuracy  = extractScore(claims["structuralAccuracy"]  ?: claims["structural_accuracy"])
        val driftScore          = extractScore(claims["driftScore"]           ?: claims["drift"])
        val complexityCapacity  = extractScore(claims["complexityCapacity"]   ?: claims["complexity"])
        val reliability         = extractScore(claims["reliability"])

        val trace = mutableListOf<String>()
        trace += "constraintObedience → $constraintObedience"
        trace += "structuralAccuracy  → $structuralAccuracy"
        trace += "driftScore          → $driftScore"
        trace += "complexityCapacity  → $complexityCapacity"
        trace += "reliability         → $reliability"

        val passing = listOf(
            constraintObedience, structuralAccuracy, driftScore,
            complexityCapacity, reliability
        ).all { it >= MINIMUM_SCORE }

        return if (passing) {
            val profile = ContractorProfile(
                id                = candidate.id,
                capabilities      = ContractorCapabilityVector(
                    constraintObedience = constraintObedience,
                    structuralAccuracy  = structuralAccuracy,
                    driftScore          = driftScore,
                    complexityCapacity  = complexityCapacity,
                    reliability         = reliability
                ),
                verificationCount = 1,
                status            = VerificationStatus.VERIFIED,
                source            = candidate.source,
                notes             = trace
            )
            trace += "outcome: VERIFIED — all dimensions ≥ $MINIMUM_SCORE"
            ContractorVerificationResult(
                candidate       = candidate,
                status          = VerificationStatus.VERIFIED,
                assignedProfile = profile,
                reasons         = trace
            )
        } else {
            trace += "outcome: REJECTED — one or more dimensions < $MINIMUM_SCORE"
            ContractorVerificationResult(
                candidate       = candidate,
                status          = VerificationStatus.REJECTED,
                assignedProfile = null,
                reasons         = trace
            )
        }
    }

    // ─── Score extraction ─────────────────────────────────────────────────────

    private fun extractScore(raw: String?): Int = when (raw?.trim()?.lowercase()) {
        "3", "high"   -> 3
        "2", "medium" -> 2
        "1", "low"    -> 1
        else          -> 0
    }
}
