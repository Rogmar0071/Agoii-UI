package com.agoii.mobile.contractor

// ─── ContractorEventEmitter ───────────────────────────────────────────────────

/**
 * Pure event record produced by contractor-system state transitions.
 *
 * @property type    One of the [ContractorEventTypes] string constants.
 * @property payload Key-value data describing the event.
 */
data class ContractorEvent(
    val type:    String,
    val payload: Map<String, Any> = emptyMap()
)

/**
 * ContractorEventEmitter — builds typed [ContractorEvent] records for every
 * contractor lifecycle transition.
 *
 * Rules:
 *  - Every emitter method MUST be called before or after the corresponding
 *    state change — no silent transitions anywhere.
 *  - This class is stateless; callers decide how to persist / route events.
 */
class ContractorEventEmitter {

    /** Emit when a new candidate is discovered by [KnowledgeScout]. */
    fun discovered(candidate: ContractorCandidate): ContractorEvent =
        ContractorEvent(
            type    = ContractorEventTypes.CONTRACTOR_DISCOVERED,
            payload = mapOf(
                "contractorId" to candidate.id,
                "source"       to candidate.source,
                "claims"       to candidate.capabilityClaims.keys.toList()
            )
        )

    /** Emit when the [ContractorVerificationEngine] begins evaluating a candidate. */
    fun verificationStarted(candidate: ContractorCandidate): ContractorEvent =
        ContractorEvent(
            type    = ContractorEventTypes.CONTRACTOR_VERIFICATION_STARTED,
            payload = mapOf(
                "contractorId" to candidate.id,
                "source"       to candidate.source
            )
        )

    /** Emit when a candidate passes verification and receives a [ContractorProfile]. */
    fun verified(profile: ContractorProfile): ContractorEvent =
        ContractorEvent(
            type    = ContractorEventTypes.CONTRACTOR_VERIFIED,
            payload = mapOf(
                "contractorId"        to profile.id,
                "capabilityScore"     to profile.capabilities.capabilityScore,
                "verificationCount"   to profile.verificationCount
            )
        )

    /** Emit when a candidate fails verification. */
    fun rejected(candidate: ContractorCandidate, reasons: List<String>): ContractorEvent =
        ContractorEvent(
            type    = ContractorEventTypes.CONTRACTOR_REJECTED,
            payload = mapOf(
                "contractorId" to candidate.id,
                "reasons"      to reasons
            )
        )

    /** Emit when an existing verified profile is updated after an execution outcome. */
    fun profileUpdated(profile: ContractorProfile): ContractorEvent =
        ContractorEvent(
            type    = ContractorEventTypes.CONTRACTOR_PROFILE_UPDATED,
            payload = mapOf(
                "contractorId"    to profile.id,
                "successCount"    to profile.successCount,
                "failureCount"    to profile.failureCount,
                "reliabilityRatio" to profile.reliabilityRatio
            )
        )
}
