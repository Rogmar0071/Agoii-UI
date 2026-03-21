package com.agoii.mobile.irs

/**
 * IRS-01 Data Models
 *
 * All types used by the Intent Resolution System. These are strictly external
 * to the Agoii Core — no core module imports or modifies these types.
 */

/** Raw user input before any processing by IRS-01. */
data class RawIntent(
    val rawInput: String,
    val optionalContext: Map<String, Any> = emptyMap()
)

/**
 * Structured draft produced by the Intent Reconstruction Engine.
 * Fields are nullable because reconstruction extracts only what is explicitly
 * present — no assumption filling is performed.
 */
data class IntentDraft(
    val intentId: String,
    val rawInput: String,
    val objective: String?          = null,
    val successCriteria: String?    = null,
    val constraints: String?        = null,
    val environment: String?        = null,
    val resources: String?          = null,
    val acceptanceBoundary: String? = null
)

/** Output of the Gap Detection Engine: which required fields are absent. */
data class GapReport(
    val missingFields: List<String>,
    val isComplete: Boolean = missingFields.isEmpty()
)

/**
 * A single item in the evidence map produced by the IRS.
 * Every extracted field is traceable to its source with a reference.
 */
data class EvidenceRef(
    val field: String,
    val source: String,
    val extractedValue: String
)

/**
 * PCCV Gate report — one Boolean per mandatory dimension.
 * All five dimensions must be true for the intent to be certified.
 */
data class PccvReport(
    /** All required fields are present. */
    val completeness: Boolean,
    /** No contradictions detected across fields. */
    val consistency: Boolean,
    /** Validated as logically feasible by scouts and simulation. */
    val feasibility: Boolean,
    /** No field was auto-filled or assumed silently. */
    val nonAssumption: Boolean,
    /** Intent is deterministic and executable without interpretation. */
    val reproducibility: Boolean
) {
    val allPass: Boolean
        get() = completeness && consistency && feasibility && nonAssumption && reproducibility
}

/**
 * Failure states emitted when IRS-01 cannot certify an intent.
 * Each state MUST prevent certification.
 */
enum class IrsFailureState {
    /** One or more required fields are missing. */
    INTENT_INCOMPLETE,
    /** Fields contain conflicting definitions. */
    INTENT_INCONSISTENT,
    /** No supporting evidence for one or more claims. */
    INTENT_UNVERIFIED,
    /** Simulation indicates the intent cannot be executed. */
    INTENT_INFEASIBLE,
    /** Swarm validation detected disagreement across passes. */
    INTENT_UNSTABLE
}

/**
 * The only valid input for `intent_submitted` events in Agoii Core.
 * Produced exclusively by IRS-01 after ALL pipeline stages pass.
 */
data class CertifiedIntent(
    val intentId: String,
    val objective: String,
    val successCriteria: String,
    val constraints: String,
    val environment: String,
    val resources: String,
    val acceptanceBoundary: String,
    val evidenceRefs: List<EvidenceRef>,
    val pccvReport: PccvReport,
    val validationStatus: String = "CERTIFIED"
)

/** The result returned by [IntentResolutionSystem.process]. */
sealed class IrsResult {
    /** Intent passed all IRS-01 stages and is ready to enter core. */
    data class Certified(val intent: CertifiedIntent) : IrsResult()

    /** Intent was blocked by IRS-01 and must not enter core. */
    data class Rejected(
        val failureState: IrsFailureState,
        val reason: String,
        val missingFields: List<String> = emptyList()
    ) : IrsResult()
}
