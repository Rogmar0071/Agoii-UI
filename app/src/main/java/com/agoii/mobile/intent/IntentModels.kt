package com.agoii.mobile.intent

import com.agoii.mobile.contracts.ContractIntent

// ─── RRID ─────────────────────────────────────────────────────────────────────

/**
 * Report Reference ID — an externally supplied anchor propagated to every output
 * produced by the Intent Module (IntentReport, RecoveryContract).
 *
 * The RRID is NEVER generated inside this module.  It is accepted as input at
 * session creation and stored verbatim so that every output can be traced back
 * to the originating report reference.
 *
 * @property value Opaque string identifier; supplied by the caller at session creation.
 */
data class Rrid(val value: String)

// ─── Intent Field State ───────────────────────────────────────────────────────

/**
 * A single mandatory field carried by the IntentMaster.
 *
 * @property value     Current field value; may be blank while not yet collected.
 * @property validated true when the field has a non-blank, accepted value.
 *                     Once true, the field MUST NOT be overwritten.
 */
data class IntentFieldState(
    val value:     String,
    val validated: Boolean
)

// ─── Intent Status ────────────────────────────────────────────────────────────

/**
 * Lifecycle state of an [IntentMaster].
 *
 * Transitions (forward-only, success path):
 *   PENDING → IN_PROGRESS → COMPLETE
 *
 * RECOVERING and FAILED may be set by external orchestration layers; the Intent
 * Module itself does not drive these transitions.
 */
enum class IntentStatus {
    /** Constructed from IngressContract; no interaction has occurred yet. */
    PENDING,

    /** Actively collecting missing mandatory fields via interaction. */
    IN_PROGRESS,

    /** All mandatory fields collected and validated; report has been frozen. */
    COMPLETE,

    /** Stagnation detected by an external layer; a Recovery Contract (RCF-1) may have been issued. */
    RECOVERING,

    /** Terminal failure set by an external layer; max recovery attempts exceeded or unrecoverable error. */
    FAILED
}

// ─── Interaction Record ───────────────────────────────────────────────────────

/**
 * A single logged interaction cycle within the IntentMaster.
 *
 * Each record captures one prompt/response pair targeting a specific field.
 * Records are append-only and are never removed or modified after creation
 * (except for the [response] and [resolved] fields being set on resolution).
 *
 * @property sequence      1-based position in the ordered interaction log.
 * @property fieldTargeted Name of the mandatory field this interaction targets.
 * @property prompt        The question or request issued to the external actor.
 * @property response      The reply received; null when not yet resolved.
 * @property resolved      true when a non-blank response has been accepted.
 */
data class InteractionRecord(
    val sequence:      Int,
    val fieldTargeted: String,
    val prompt:        String,
    val response:      String?,
    val resolved:      Boolean
)

// ─── Recovery Contract ────────────────────────────────────────────────────────

/**
 * Recovery Contract (RCF-1) issued by the Recovery Trigger when stagnation is
 * detected in the interaction log.
 *
 * @property intentId      Session identifier from the originating IntentMaster.
 * @property rrid          RRID anchor propagated from the session.
 * @property type          Contract type; always "RCF-1".
 * @property reason        Human-readable description of the stagnation detected.
 * @property missingFields Names of mandatory fields that remain incomplete.
 */
data class RecoveryContract(
    val intentId:      String,
    val rrid:          Rrid,
    val type:          String,
    val reason:        String,
    val missingFields: List<String>
)

// ─── Communication Contract ───────────────────────────────────────────────────

/**
 * A typed contract issued by the [IntentModule] when a mandatory field requires
 * an external response.
 *
 * The Intent Module MUST NOT use free-form text interaction.  Every request for
 * missing information MUST be expressed as a [CommunicationContract] so that
 * downstream systems can validate, route, and audit each interaction cycle.
 *
 * @property intentId        Session identifier of the originating IntentMaster.
 * @property reportReference RRID value propagated from the session anchor.
 * @property type            Contract kind; always "FIELD_REQUEST" for field collection.
 * @property fieldRequired   Name of the mandatory field being collected.
 * @property sequence        1-based position of this contract in the interaction log.
 * @property prompt          Deterministic, human-readable description of the request.
 */
data class CommunicationContract(
    val intentId:        String,
    val reportReference: String,
    val type:            String,
    val fieldRequired:   String,
    val sequence:        Int,
    val prompt:          String
)

// ─── Intent Report ────────────────────────────────────────────────────────────

/**
 * Frozen CONTRACT REPORT produced when the IntentMaster reaches [IntentStatus.COMPLETE].
 *
 * The report is immutable once generated and carries the full resolved state of
 * all mandatory fields along with traceability metadata.
 *
 * MQP requirement — the report MUST include:
 *  - Full type inventory (all types used within the session).
 *  - State structure (resolved field values).
 *  - Field completeness status (per-field validation flag).
 *  - Interaction history (ordered log of all interaction records).
 *  - Error conditions (any incomplete or invalid conditions at freeze time).
 *
 * Rules:
 *  - Descriptive ONLY — no fixes, no interpretation.
 *  - Produced before the ContractIntent is forwarded to the ContractSystemOrchestrator.
 *
 * @property intentId                 Session identifier.
 * @property rrid                     RRID anchor for this report; equals the externally supplied
 *                                    report reference.
 * @property reportReference          String value of the [rrid]; propagated to downstream systems.
 * @property objective                Resolved, validated objective field value.
 * @property constraints              Resolved, validated constraints field value.
 * @property environment              Resolved, validated environment field value.
 * @property resources                Resolved, validated resources field value.
 * @property completenessAt           Completeness ratio at time of report generation (always 1.0).
 * @property interactionCount         Total number of interaction records logged during collection.
 * @property recoveryAttempts         Number of Recovery Contracts issued during the session.
 * @property typeInventory            Ordered list of all types used within the IntentMaster at
 *                                    freeze time; descriptive only.
 * @property fieldCompletenessStatus  Per-field validation flag keyed by field name.
 * @property errorConditions          Ordered list of error or incompleteness descriptions detected
 *                                    at freeze time; empty when all fields are fully validated.
 * @property interactionHistory       Full ordered copy of the interaction log at freeze time.
 */
data class IntentReport(
    val intentId:                String,
    val rrid:                    Rrid,
    val reportReference:         String,
    val objective:               String,
    val constraints:             String,
    val environment:             String,
    val resources:               String,
    val completenessAt:          Double,
    val interactionCount:        Int,
    val recoveryAttempts:        Int,
    val typeInventory:           List<String>,
    val fieldCompletenessStatus: Map<String, Boolean>,
    val errorConditions:         List<String>,
    val interactionHistory:      List<InteractionRecord>
)

// ─── Intent Master ────────────────────────────────────────────────────────────

/**
 * Central state artifact of the Intent Module.
 *
 * The IntentMaster is constructed from an IngressContract and evolves through
 * interaction cycles until all mandatory fields are validated.  It is never
 * deleted; its state advances monotonically forward.
 *
 * Mandatory fields: objective, constraints, environment, resources.
 *
 * @property intentId         Session identifier (equals the IngressContract.contractId).
 * @property rrid             RRID anchor supplied externally at session creation; never
 *                            generated inside the Intent Module.
 * @property reportReference  String value of [rrid]; propagated to all outputs.
 * @property status           Current lifecycle state.
 * @property objective        Objective field state.
 * @property constraints      Constraints field state.
 * @property environment      Environment field state.
 * @property resources        Resources field state.
 * @property interactionLog   Ordered, append-only log of all interaction cycles.
 * @property completeness     Ratio of validated fields; 0.0 = none, 1.0 = all.
 * @property recoveryAttempts Number of Recovery Contracts issued so far.
 * @property report           Frozen IntentReport; non-null only when [status] = COMPLETE.
 */
data class IntentMaster(
    val intentId:         String,
    val rrid:             Rrid,
    val reportReference:  String,
    val status:           IntentStatus,
    val objective:        IntentFieldState,
    val constraints:      IntentFieldState,
    val environment:      IntentFieldState,
    val resources:        IntentFieldState,
    val interactionLog:   List<InteractionRecord>,
    val completeness:     Double,
    val recoveryAttempts: Int,
    val report:           IntentReport?
) {
    init {
        require(completeness in 0.0..1.0) {
            "completeness must be in [0.0, 1.0], got $completeness"
        }
        require(recoveryAttempts >= 0) {
            "recoveryAttempts must be ≥ 0, got $recoveryAttempts"
        }
        require(status != IntentStatus.COMPLETE || report != null) {
            "report must be non-null when status is COMPLETE"
        }
    }
}

// ─── Intent Module Result ─────────────────────────────────────────────────────

/**
 * Terminal or intermediate result returned by [IntentModule.step].
 *
 * The caller inspects the concrete subtype to decide the next action:
 *  - [Complete]         — forward [contractIntent] to ContractSystemOrchestrator.
 *  - [NeedsInteraction] — process [communicationContract] and call step() again
 *                         with the external actor's response.
 *  - [Failed]           — terminal; no further advancement is possible.
 */
sealed class IntentModuleResult {

    /**
     * All mandatory fields collected and validated.
     * The frozen [report] satisfies the MQP requirement.
     * [contractIntent] is ready for the ContractSystemOrchestrator.
     */
    data class Complete(
        val intentMaster:   IntentMaster,
        val report:         IntentReport,
        val contractIntent: ContractIntent
    ) : IntentModuleResult()

    /**
     * A mandatory field is still missing.
     * Route [communicationContract] to the external actor and call step() again
     * with the response.
     *
     * @property communicationContract Typed contract describing exactly what is needed.
     * @property fieldRequired         Name of the mandatory field being collected.
     */
    data class NeedsInteraction(
        val intentMaster:          IntentMaster,
        val communicationContract: CommunicationContract,
        val fieldRequired:         String
    ) : IntentModuleResult()

    /**
     * Unrecoverable failure; the session cannot be advanced further.
     * No further step() calls are valid.
     *
     * @property reason Human-readable description of why the session failed.
     */
    data class Failed(
        val intentMaster: IntentMaster,
        val reason:       String
    ) : IntentModuleResult()
}
