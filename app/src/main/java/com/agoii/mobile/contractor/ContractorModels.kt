package com.agoii.mobile.contractor

// ─── Contractor System — Foundation Models ────────────────────────────────────

// ─── Capability Vector ────────────────────────────────────────────────────────

/**
 * Structured capability dimensions for a contractor.
 *
 * All dimension scores are in [0, 3].
 *
 * @property constraintObedience  How reliably the contractor follows constraints.
 * @property structuralAccuracy   How accurately the contractor follows structure.
 * @property driftScore           How likely the contractor is to deviate (0 = no drift).
 * @property complexityCapacity   Ability to handle complex, multi-step work.
 * @property reliability          Consistency and determinism of output.
 */
data class ContractorCapabilityVector(
    val constraintObedience: Int,
    val structuralAccuracy:  Int,
    val driftScore:          Int,
    val complexityCapacity:  Int,
    val reliability:         Int,
    val communication:       Int = 0
) {
    init {
        require(constraintObedience in 0..3) { "constraintObedience must be in [0, 3]" }
        require(structuralAccuracy  in 0..3) { "structuralAccuracy must be in [0, 3]" }
        require(driftScore          in 0..3) { "driftScore must be in [0, 3]" }
        require(complexityCapacity  in 0..3) { "complexityCapacity must be in [0, 3]" }
        require(reliability         in 0..3) { "reliability must be in [0, 3]" }
        require(communication       in 0..3) { "communication must be in [0, 3]" }
    }

    /**
     * Effective capability score — inverting driftScore so lower drift contributes
     * positively.  Max = 15.
     */
    val capabilityScore: Int get() =
        constraintObedience + structuralAccuracy + (3 - driftScore) +
        complexityCapacity  + reliability
}

// ─── Verification Status ──────────────────────────────────────────────────────

/** Lifecycle state of a contractor within the verification pipeline. */
enum class VerificationStatus { UNVERIFIED, VERIFIED, REJECTED }

// ─── Contractor Profile ───────────────────────────────────────────────────────

/**
 * Complete profile of a contractor, including capability vector, scoring dimensions,
 * historical performance metrics, and current verification status.
 *
 * Rules:
 *  - [id] must be unique across the registry.
 *  - A contractor can only be stored in [ContractorRegistry] when [status] = VERIFIED.
 *  - Scores are updated after each execution via the verification pipeline.
 *
 * @property id                 Unique contractor identifier.
 * @property capabilities       Structured capability dimensions.
 * @property verificationCount  Number of completed verification cycles.
 * @property successCount       Number of successfully completed tasks.
 * @property failureCount       Number of failed tasks.
 * @property status             Current verification status.
 * @property source             Origin of this contractor (e.g. "api", "model", "tool").
 * @property notes              Optional human-readable notes from the verification process.
 */
data class ContractorProfile(
    val id:                String,
    val capabilities:      ContractorCapabilityVector,
    val verificationCount: Int                 = 0,
    val successCount:      Int                 = 0,
    val failureCount:      Int                 = 0,
    val status:            VerificationStatus  = VerificationStatus.UNVERIFIED,
    val source:            String              = "unknown",
    val notes:             List<String>        = emptyList()
) {
    /** Reliability ratio in [0.0, 1.0]; 0.0 when no executions have been recorded. */
    val reliabilityRatio: Double get() {
        val total = successCount + failureCount
        return if (total == 0) 0.0 else successCount.toDouble() / total
    }
}

// ─── Candidate (pre-verification) ────────────────────────────────────────────

/**
 * A contractor candidate discovered by [KnowledgeScout] before entering the
 * verification pipeline.
 *
 * @property id                Proposed unique identifier.
 * @property source            Where this candidate was discovered.
 * @property capabilityClaims  Raw capability claims extracted from discovery.
 */
data class ContractorCandidate(
    val id:               String,
    val source:           String,
    val capabilityClaims: Map<String, String>
)

// ─── Verification Result ──────────────────────────────────────────────────────

/**
 * Output of the [ContractorVerificationEngine] for a single candidate.
 *
 * @property candidate        The candidate that was evaluated.
 * @property status           VERIFIED or REJECTED.
 * @property assignedProfile  The resulting [ContractorProfile] (non-null when VERIFIED).
 * @property reasons          Ordered evaluation trace covering every test dimension.
 */
data class ContractorVerificationResult(
    val candidate:       ContractorCandidate,
    val status:          VerificationStatus,
    val assignedProfile: ContractorProfile?,
    val reasons:         List<String>
)

// ─── Contractor Events ────────────────────────────────────────────────────────

/** All event type strings emitted by the contractor system. */
object ContractorEventTypes {
    const val CONTRACTOR_DISCOVERED           = "contractor_discovered"
    const val CONTRACTOR_VERIFICATION_STARTED = "contractor_verification_started"
    const val CONTRACTOR_VERIFIED             = "contractor_verified"
    const val CONTRACTOR_REJECTED             = "contractor_rejected"
    const val CONTRACTOR_PROFILE_UPDATED      = "contractor_profile_updated"
}
