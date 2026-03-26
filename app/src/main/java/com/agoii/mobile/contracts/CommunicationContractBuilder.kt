package com.agoii.mobile.contracts

// ─── Communication Contract Builder ──────────────────────────────────────────

/**
 * CommunicationContractBuilder — constructs [ContractDefinition] instances of
 * type [ContractType.COMMUNICATION].
 *
 * A COMMUNICATION contract governs a single AI ↔ User exchange mediated by the
 * ICS / Ingress layer.  It encodes what the exchange must achieve, what the
 * system must not do during the exchange, and what constitutes a successful
 * interaction outcome.
 *
 * Usage:
 * ```
 * val definition = CommunicationContractBuilder()
 *     .objective("Clarify project scope with the user")
 *     .scope("ICS", "INGRESS")
 *     .constraint("Must not persist raw user input to the ledger")
 *     .expectedOutput("Structured IngressContract delivered to CoreBridge")
 *     .completionCriterion("User intent classification confirmed")
 *     .completionCriterion("Normalized payload extracted")
 *     .build()
 * ```
 *
 * Rules:
 *  - Always produces [ContractType.COMMUNICATION].
 *  - Scope is pre-seeded with "COMMUNICATION" if not explicitly set by the caller.
 *  - The resulting [ContractDefinition] must be submitted to [ContractFactory.build]
 *    for id assignment and final validation.
 */
class CommunicationContractBuilder {

    private var objective:          String                 = ""
    private val scope:              MutableList<String>    = mutableListOf("COMMUNICATION")
    private val constraints:        MutableList<String>    = mutableListOf()
    private var expectedOutput:     String                 = ""
    private val completionCriteria: MutableList<String>    = mutableListOf()
    private val metadata:           MutableMap<String, String> = mutableMapOf()

    /** Sets the objective of this communication contract. */
    fun objective(objective: String): CommunicationContractBuilder =
        apply { this.objective = objective }

    /**
     * Appends one or more scope entries (in addition to the default "COMMUNICATION"
     * entry already present).
     */
    fun scope(vararg entries: String): CommunicationContractBuilder =
        apply { scope += entries }

    /** Appends a constraint that execution must respect. */
    fun constraint(constraint: String): CommunicationContractBuilder =
        apply { constraints += constraint }

    /** Sets the expected output description. */
    fun expectedOutput(expectedOutput: String): CommunicationContractBuilder =
        apply { this.expectedOutput = expectedOutput }

    /** Appends a single completion criterion. */
    fun completionCriterion(criterion: String): CommunicationContractBuilder =
        apply { completionCriteria += criterion }

    /** Attaches an optional metadata key/value pair. */
    fun metadata(key: String, value: String): CommunicationContractBuilder =
        apply { metadata[key] = value }

    /**
     * Produces the [ContractDefinition] for submission to [ContractFactory].
     *
     * No validation is performed here; [ContractValidator] runs inside
     * [ContractFactory.build].
     */
    fun build(): ContractDefinition = ContractDefinition(
        type               = ContractType.COMMUNICATION,
        objective          = objective,
        scope              = scope.toList(),
        constraints        = constraints.toList(),
        expectedOutput     = expectedOutput,
        completionCriteria = completionCriteria.toList(),
        metadata           = metadata.toMap()
    )
}
