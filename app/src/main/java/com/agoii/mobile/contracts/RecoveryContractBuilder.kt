package com.agoii.mobile.contracts

// ─── Recovery Contract Builder ────────────────────────────────────────────────

/**
 * RecoveryContractBuilder — constructs [ContractDefinition] instances of type
 * [ContractType.RECOVERY].
 *
 * A RECOVERY contract defines the authoritative resolution path for a detected
 * system failure.  It must name the failure it addresses, constrain the recovery
 * action to safe system boundaries, and state the criteria that confirm the
 * system has returned to a healthy state.
 *
 * Usage:
 * ```
 * val definition = RecoveryContractBuilder()
 *     .objective("Recover from EXECUTION_FAILED on contract contract_3")
 *     .scope("EXECUTION", "BRIDGE")
 *     .failureReference("contract_3")
 *     .constraint("Recovery must not re-run completed steps")
 *     .expectedOutput("Failed contract retried and EXECUTION_COMPLETED emitted")
 *     .completionCriterion("CONTRACT_STARTED re-emitted for the failed position")
 *     .completionCriterion("EXECUTION_COMPLETED recorded in ledger")
 *     .build()
 * ```
 *
 * Rules:
 *  - Always produces [ContractType.RECOVERY].
 *  - A failure reference ([failureReference]) must be set; it is stored in metadata
 *    and appended as a scope entry.
 *  - The resulting [ContractDefinition] must be submitted to [ContractFactory.build]
 *    for id assignment and final validation.
 */
class RecoveryContractBuilder {

    private var objective:          String                     = ""
    private val scope:              MutableList<String>        = mutableListOf("RECOVERY")
    private val constraints:        MutableList<String>        = mutableListOf()
    private var expectedOutput:     String                     = ""
    private val completionCriteria: MutableList<String>        = mutableListOf()
    private val metadata:           MutableMap<String, String> = mutableMapOf()

    /** Sets the objective of this recovery contract. */
    fun objective(objective: String): RecoveryContractBuilder =
        apply { this.objective = objective }

    /** Appends one or more scope entries. */
    fun scope(vararg entries: String): RecoveryContractBuilder =
        apply { scope += entries }

    /**
     * Records the id of the artifact (contract, step, etc.) that triggered the
     * recovery.  Stored in metadata under "failureReference" and added to scope.
     */
    fun failureReference(reference: String): RecoveryContractBuilder = apply {
        metadata["failureReference"] = reference
        if (reference.isNotBlank()) scope += reference
    }

    /** Appends a constraint that the recovery action must respect. */
    fun constraint(constraint: String): RecoveryContractBuilder =
        apply { constraints += constraint }

    /** Sets the expected output description. */
    fun expectedOutput(expectedOutput: String): RecoveryContractBuilder =
        apply { this.expectedOutput = expectedOutput }

    /** Appends a single completion criterion. */
    fun completionCriterion(criterion: String): RecoveryContractBuilder =
        apply { completionCriteria += criterion }

    /** Attaches an optional metadata key/value pair. */
    fun metadata(key: String, value: String): RecoveryContractBuilder =
        apply { metadata[key] = value }

    /**
     * Produces the [ContractDefinition] for submission to [ContractFactory].
     *
     * No validation is performed here; [ContractValidator] runs inside
     * [ContractFactory.build].
     */
    fun build(): ContractDefinition = ContractDefinition(
        type               = ContractType.RECOVERY,
        objective          = objective,
        scope              = scope.toList(),
        constraints        = constraints.toList(),
        expectedOutput     = expectedOutput,
        completionCriteria = completionCriteria.toList(),
        metadata           = metadata.toMap()
    )
}
