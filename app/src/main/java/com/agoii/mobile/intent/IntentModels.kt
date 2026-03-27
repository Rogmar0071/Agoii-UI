package com.agoii.mobile.intent

import com.agoii.mobile.contracts.ContractIntent

// ─── RRID ─────────────────────────────────────────────────────────────────────

/**
 * Report Reference ID — a stable, unique anchor propagated to every output
 * produced by the Intent Module (IntentReport, RecoveryContract).
 *
 * @property value Opaque string identifier; generated once per session.
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
 * Transitions (forward-only):
 *   PENDING → IN_PROGRESS → COMPLETE (success path)
 *   IN_PROGRESS → RECOVERING (stagnation detected)
 *   RECOVERING  → IN_PROGRESS (progress resumed after recovery)
 *   RECOVERING  → FAILED (max recovery attempts exceeded)
 */
enum class IntentStatus {
    /** Constructed from IngressContract; no interaction has occurred yet. */
    PENDING,

    /** Actively collecting missing mandatory fields via interaction. */
    IN_PROGRESS,

    /** All mandatory fields collected and validated; report has been frozen. */
    COMPLETE,

    /** Stagnation detected; a Recovery Contract (RCF-1) has been issued. */
    RECOVERING,

    /** Unrecoverable failure; max recovery attempts exceeded. */
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

// ─── Intent Report ────────────────────────────────────────────────────────────

/**
 * Frozen CONTRACT REPORT produced when the IntentMaster reaches [IntentStatus.COMPLETE].
 *
 * The report is immutable once generated and carries the full resolved state of
 * all mandatory fields along with traceability metadata.
 *
 * MQP requirement: this report MUST be produced before the ContractIntent is
 * forwarded to the ContractSystemOrchestrator.
 *
 * @property intentId          Session identifier.
 * @property rrid              RRID anchor for this report.
 * @property reportReference   String value of the [rrid]; propagated to downstream systems.
 * @property objective         Resolved, validated objective field value.
 * @property constraints       Resolved, validated constraints field value.
 * @property environment       Resolved, validated environment field value.
 * @property resources         Resolved, validated resources field value.
 * @property completenessAt    Completeness ratio at time of report generation (always 1.0).
 * @property interactionCount  Total number of interaction records logged during collection.
 * @property recoveryAttempts  Number of Recovery Contracts issued during the session.
 */
data class IntentReport(
    val intentId:         String,
    val rrid:             Rrid,
    val reportReference:  String,
    val objective:        String,
    val constraints:      String,
    val environment:      String,
    val resources:        String,
    val completenessAt:   Double,
    val interactionCount: Int,
    val recoveryAttempts: Int
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
 * @property rrid             RRID anchor generated at session creation.
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
 *  - [Complete]          — forward [contractIntent] to ContractSystemOrchestrator.
 *  - [NeedsInteraction]  — present [prompt] to the user and call step() again with
 *                          the user's response.
 *  - [Recovering]        — surface [recoveryContract] to the operator; call step()
 *                          again when the operator provides an answer.
 *  - [Failed]            — terminal; no further advancement is possible.
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
     * Present [prompt] to the external actor and call step() with the response.
     *
     * @property fieldRequired Name of the field being collected.
     */
    data class NeedsInteraction(
        val intentMaster:  IntentMaster,
        val prompt:        String,
        val fieldRequired: String
    ) : IntentModuleResult()

    /**
     * Stagnation detected; a Recovery Contract (RCF-1) has been issued.
     * Surface [recoveryContract] to the operator, then call step() again
     * with a corrective response.
     */
    data class Recovering(
        val intentMaster:     IntentMaster,
        val recoveryContract: RecoveryContract
    ) : IntentModuleResult()

    /**
     * Unrecoverable failure; max recovery attempts exceeded or a convergence
     * violation was detected.  No further step() calls are valid.
     *
     * @property reason Human-readable description of why the session failed.
     */
    data class Failed(
        val intentMaster: IntentMaster,
        val reason:       String
    ) : IntentModuleResult()
}
