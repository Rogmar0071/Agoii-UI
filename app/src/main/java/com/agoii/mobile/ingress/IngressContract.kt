package com.agoii.mobile.ingress

/**
 * Immutable contract produced at system entry from raw user input.
 *
 * An [IngressContract] is a deterministic, structured record that represents
 * a single validated request entering the system.  It carries no executable
 * logic and must never be mutated after construction.
 *
 * @property contractId    Unique identifier for this ingress contract.
 * @property intentType    Broad category of the caller's intent.
 * @property scope         System boundary the contract targets.
 * @property references    Optional back-links to related artefacts.
 * @property payload       The normalised input data extracted from raw user input.
 * @property status        Lifecycle state of this contract.
 */
data class IngressContract(
    val contractId: String,
    val intentType: IntentType,
    val scope:      Scope,
    val references: References,
    val payload:    Payload,
    val status:     ContractStatus
)

// ─── Intent Classification ────────────────────────────────────────────────────

/** Broad classification of what the caller intends to do. */
enum class IntentType {
    /** The caller is requesting information or a read-only view of state. */
    QUERY,

    /** The caller wants to trigger a state-changing operation. */
    ACTION,

    /** The caller is asking the system to resolve ambiguity before proceeding. */
    CLARIFICATION
}

// ─── Scope ────────────────────────────────────────────────────────────────────

/** System boundary that the ingress contract is targeting. */
enum class Scope {
    /** Targets the overall system configuration or orchestration layer. */
    SYSTEM,

    /** Targets the contract evaluation pipeline. */
    CONTRACT,

    /** Targets individual task decomposition and allocation. */
    TASK,

    /** Targets a running or queued execution unit. */
    EXECUTION,

    /** Targets the simulation / reality-validation subsystem. */
    SIMULATION
}

// ─── Reference Binding ───────────────────────────────────────────────────────

/**
 * Optional references that bind this contract to existing artifacts.
 *
 * All fields are nullable; omit any that are not relevant to the request.
 *
 * @property contractId    ID of a related [IngressContract] or downstream contract.
 * @property taskId        ID of a task this contract is associated with.
 * @property simulationId  ID of an IRS simulation this contract relates to.
 */
data class References(
    val contractId:   String? = null,
    val taskId:       String? = null,
    val simulationId: String? = null
)

// ─── Payload ─────────────────────────────────────────────────────────────────

/**
 * Normalized input data derived from a raw user-supplied string.
 *
 * @property rawInput          The original, unmodified string provided by the user.
 * @property normalizedIntent  Intent text after whitespace collapsing and casing
 *                             normalization, ready for downstream processing.
 * @property extractedFields   Key/value pairs extracted from [rawInput] by the
 *                             ingress parser (e.g. named entities, slot values).
 */
data class Payload(
    val rawInput:         String,
    val normalizedIntent: String,
    val extractedFields:  Map<String, String>
)

// ─── Contract Lifecycle ───────────────────────────────────────────────────────

/** Lifecycle state of an [IngressContract]. */
enum class ContractStatus {
    /** Contract has been created but not yet validated. */
    PENDING,

    /** Contract passed validation and has been admitted to the system. */
    ACCEPTED,

    /** Contract failed validation and will not be processed further. */
    REJECTED
}
