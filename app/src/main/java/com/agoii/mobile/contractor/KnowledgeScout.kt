package com.agoii.mobile.contractor

// ─── KnowledgeScout ───────────────────────────────────────────────────────────

/**
 * KnowledgeScout — discovers contractor candidates when the [ContractorRegistry]
 * cannot satisfy a capability requirement.
 *
 * Discovery strategy (deterministic, no external I/O):
 *  - Generates synthetic candidates from a fixed discovery template.
 *  - Each candidate's capability claims are seeded from [requiredCapabilities].
 *  - Candidate IDs are deterministically derived from the requirement key.
 *
 * Rules:
 *  - Pure function: no state, no side effects (events are returned, not emitted).
 *  - Every call with the same [requiredCapabilities] produces the same candidates.
 *  - Candidates are NOT verified here — they are forwarded to
 *    [ContractorVerificationEngine].
 */
class KnowledgeScout {

    /**
     * Discover candidate contractors for [requiredCapabilities].
     *
     * @param requiredCapabilities Map of capability dimension → minimum score string.
     * @return List of [ContractorCandidate] ready for verification.
     */
    fun discover(requiredCapabilities: Map<String, String>): List<ContractorCandidate> {
        if (requiredCapabilities.isEmpty()) return emptyList()

        // Build a deterministic candidate id from capability keys.
        val capKey = requiredCapabilities.keys.sorted().joinToString("-")
        val candidateId = "scout-$capKey"

        // Seed claims directly from the requirement, satisfying all dimensions.
        val seededClaims = requiredCapabilities.toMutableMap()

        // Ensure all five standard dimensions are present with at least "medium".
        for (dim in STANDARD_DIMENSIONS) {
            seededClaims.putIfAbsent(dim, "medium")
        }

        return listOf(
            ContractorCandidate(
                id               = candidateId,
                source           = "knowledge_scout",
                capabilityClaims = seededClaims
            )
        )
    }

    companion object {
        /** The five standard capability dimensions expected by [ContractorVerificationEngine]. */
        val STANDARD_DIMENSIONS = listOf(
            "constraintObedience",
            "structuralAccuracy",
            "driftScore",
            "complexityCapacity",
            "reliability"
        )
    }
}
