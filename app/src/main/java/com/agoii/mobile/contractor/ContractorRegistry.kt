package com.agoii.mobile.contractor

// ─── ContractorRegistry ───────────────────────────────────────────────────────

/**
 * ContractorRegistry — the sole authoritative store of verified contractors.
 *
 * Invariants:
 *  - Only contractors with [VerificationStatus.VERIFIED] may be stored.
 *  - Insertion is only possible through [registerVerified] (called by the
 *    verification pipeline after [ContractorVerificationEngine] returns VERIFIED).
 *  - Lookup returns the best-matching contractor for a capability requirement.
 *  - Profile updates (after execution outcomes) are only permitted on existing
 *    verified entries, and always emit [ContractorEventTypes.CONTRACTOR_PROFILE_UPDATED].
 *  - All mutations emit events via [ContractorEventEmitter]; callers receive and
 *    route the returned events.
 *
 * Rules:
 *  - Stateful: the registry maintains its own in-memory store.
 *  - No hardcoded contractors.
 *  - No direct insertion without verification.
 */
class ContractorRegistry(
    private val emitter: ContractorEventEmitter = ContractorEventEmitter()
) {

    // Internal mutable store; keyed by contractor id.
    private val store: MutableMap<String, ContractorProfile> = mutableMapOf()

    // ─── Registration ─────────────────────────────────────────────────────────

    /**
     * Register a verified [profile] in the registry.
     *
     * Only contractors with [VerificationStatus.VERIFIED] are accepted.
     *
     * @throws IllegalArgumentException when [profile] is not VERIFIED.
     * @return The emitted [ContractorEvent] for this registration.
     */
    fun register(profile: ContractorProfile): ContractorEvent {
        require(profile.status == VerificationStatus.VERIFIED) {
            "Only VERIFIED contractors may be registered. Got: ${profile.status}"
        }
        store[profile.id] = profile
        return emitter.verified(profile)
    }

    /**
     * Alias for [register] retained for call-sites established prior to this contract.
     *
     * @throws IllegalArgumentException when [profile] is not VERIFIED.
     * @return The emitted [ContractorEvent] for this registration.
     */
    fun registerVerified(profile: ContractorProfile): ContractorEvent = register(profile)

    // ─── Lookup ───────────────────────────────────────────────────────────────

    /**
     * Return all verified contractors.
     *
     * This is the canonical single-source accessor; [allVerified] is an alias
     * retained for locked call-sites.
     */
    fun getAll(): List<ContractorProfile> =
        store.values.filter { it.status == VerificationStatus.VERIFIED }

    /**
     * Return all verified contractors.
     *
     * Alias for [getAll] retained for call-sites established prior to this contract.
     */
    fun allVerified(): List<ContractorProfile> = getAll()

    /**
     * Look up a verified contractor by [id].
     *
     * @param id Unique contractor identifier.
     * @return The [ContractorProfile] if present and VERIFIED, or null.
     */
    fun getById(id: String): ContractorProfile? =
        store[id]?.takeIf { it.status == VerificationStatus.VERIFIED }

    /**
     * Look up the best contractor matching [requiredCapabilities].
     *
     * Matching logic:
     *  1. Filter to contractors whose every required dimension meets or exceeds
     *     the minimum score.
     *  2. Among eligible contractors, select the one with the highest
     *     [ContractorCapabilityVector.capabilityScore].
     *  3. Tie-breaking: prefer higher [ContractorProfile.reliabilityRatio].
     *
     * @param requiredCapabilities  Map of dimension name → minimum score (0–3).
     * @return Best-matching [ContractorProfile], or null when no match exists.
     */
    fun findBestMatch(requiredCapabilities: Map<String, Int>): ContractorProfile? {
        return store.values
            .filter { it.status == VerificationStatus.VERIFIED }
            .filter { profile -> meetsRequirements(profile, requiredCapabilities) }
            .maxWithOrNull(
                compareBy(
                    { it.capabilities.capabilityScore },
                    { it.reliabilityRatio }
                )
            )
    }

    // ─── Profile update ───────────────────────────────────────────────────────

    /**
     * Record an execution outcome for [contractorId].
     *
     * @param contractorId  Unique contractor identifier.
     * @param success       Whether the execution succeeded.
     * @return The emitted [ContractorEvent], or null when the id is not found.
     */
    fun recordOutcome(contractorId: String, success: Boolean): ContractorEvent? {
        val existing = store[contractorId] ?: return null
        val updated = existing.copy(
            successCount = existing.successCount + if (success) 1 else 0,
            failureCount = existing.failureCount + if (success) 0 else 1
        )
        store[contractorId] = updated
        return emitter.profileUpdated(updated)
    }

    // ─── Internals ────────────────────────────────────────────────────────────

    private fun meetsRequirements(
        profile: ContractorProfile,
        requirements: Map<String, Int>
    ): Boolean {
        val cap = profile.capabilities
        return requirements.all { (dim, score) ->
            when (dim) {
                // Standard dimensions: score is a minimum floor (higher = more capable).
                "constraintObedience" -> cap.constraintObedience >= score
                "structuralAccuracy"  -> cap.structuralAccuracy  >= score
                "complexityCapacity"  -> cap.complexityCapacity  >= score
                "reliability"         -> cap.reliability         >= score
                // driftScore: score is a maximum ceiling (lower drift = more reliable).
                // A requirement of driftScore=1 means "drift must be at most 1".
                "driftScore"          -> cap.driftScore          <= score
                else                  -> true
            }
        }
    }
}
