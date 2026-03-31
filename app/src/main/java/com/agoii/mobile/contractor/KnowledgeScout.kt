package com.agoii.mobile.contractor

// ─── KnowledgeScout ───────────────────────────────────────────────────────────

/**
 * KnowledgeScout — discovers contractor candidates when the [ContractorRegistry]
 * cannot satisfy a capability requirement.
 *
 * Discovery strategy (deterministic, no external I/O):
 *  - Generates candidates only for capability dimensions registered in [CapabilityMap].
 *  - Each candidate's capability claims are seeded from [requiredCapabilities]
 *    filtered to known [CapabilityMap.definitions].
 *  - No synthetic contractor generation: only capabilities with a registered
 *    [CapabilityDefinition] produce candidates.
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
     * Only capability dimensions present in [CapabilityMap.definitions] contribute
     * to candidate generation; unknown dimensions are silently omitted.
     *
     * @param requiredCapabilities Map of capability dimension → minimum score string.
     * @return List of [ContractorCandidate] ready for verification, or empty when
     *         no required capability has a registered definition.
     */
    fun discover(requiredCapabilities: Map<String, String>): List<ContractorCandidate> {
        if (requiredCapabilities.isEmpty()) return emptyList()

        // Retain only dimensions that are registered in CapabilityMap.
        val knownClaims = requiredCapabilities
            .filterKeys { it in CapabilityMap.definitions }

        if (knownClaims.isEmpty()) return emptyList()

        // Build a deterministic candidate id from known capability keys.
        val capKey      = knownClaims.keys.sorted().joinToString("-")
        val candidateId = "scout-$capKey"

        // Seed claims from the filtered requirements.
        val seededClaims = knownClaims.toMutableMap()

        // Ensure all standard dimensions present in CapabilityMap are included.
        for (dim in CapabilityMap.definitions.keys) {
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
