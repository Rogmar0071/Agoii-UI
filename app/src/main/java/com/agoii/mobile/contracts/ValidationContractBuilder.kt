package com.agoii.mobile.contracts

// ─── Validation Contract Builder ─────────────────────────────────────────────

/**
 * ValidationContractBuilder — constructs [ContractDefinition] instances of type
 * [ContractType.VALIDATION].
 *
 * A VALIDATION contract governs an internal completeness or structural check.
 * It records what is being validated, what constitutes a pass, and what the
 * expected output of the validation pass is.  Validation contracts are internal
 * to the system and do not produce authoritative ledger events.
 *
 * Usage:
 * ```
 * val definition = ValidationContractBuilder()
 *     .objective("Verify ContractDefinition completeness before factory submission")
 *     .scope("CONTRACTS", "VALIDATION")
 *     .subject("ContractDefinition")
 *     .constraint("Validation must not modify the subject")
 *     .expectedOutput("ContractDefinitionValidationResult with all rules evaluated")
 *     .completionCriterion("All completeness rules applied")
 *     .completionCriterion("Result returned to ContractFactory")
 *     .build()
 * ```
 *
 * Rules:
 *  - Always produces [ContractType.VALIDATION].
 *  - Scope is pre-seeded with "VALIDATION".
 *  - A [subject] may be provided to record what entity is under validation;
 *    it is stored in metadata.
 *  - The resulting [ContractDefinition] must be submitted to [ContractFactory.build]
 *    for id assignment and final validation.
 */
class ValidationContractBuilder {

    private var objective:          String                     = ""
    private val scope:              MutableList<String>        = mutableListOf("VALIDATION")
    private val constraints:        MutableList<String>        = mutableListOf()
    private var expectedOutput:     String                     = ""
    private val completionCriteria: MutableList<String>        = mutableListOf()
    private val metadata:           MutableMap<String, String> = mutableMapOf()

    /** Sets the objective of this validation contract. */
    fun objective(objective: String): ValidationContractBuilder =
        apply { this.objective = objective }

    /** Appends one or more scope entries. */
    fun scope(vararg entries: String): ValidationContractBuilder =
        apply { scope += entries }

    /**
     * Records the entity or artifact being validated.
     * Stored in metadata under "subject".
     */
    fun subject(subject: String): ValidationContractBuilder =
        apply { metadata["subject"] = subject }

    /** Appends a constraint that the validation pass must respect. */
    fun constraint(constraint: String): ValidationContractBuilder =
        apply { constraints += constraint }

    /** Sets the expected output description. */
    fun expectedOutput(expectedOutput: String): ValidationContractBuilder =
        apply { this.expectedOutput = expectedOutput }

    /** Appends a single completion criterion. */
    fun completionCriterion(criterion: String): ValidationContractBuilder =
        apply { completionCriteria += criterion }

    /** Attaches an optional metadata key/value pair. */
    fun metadata(key: String, value: String): ValidationContractBuilder =
        apply { metadata[key] = value }

    /**
     * Produces the [ContractDefinition] for submission to [ContractFactory].
     *
     * No validation is performed here; [ContractValidator] runs inside
     * [ContractFactory.build].
     */
    fun build(): ContractDefinition = ContractDefinition(
        type               = ContractType.VALIDATION,
        objective          = objective,
        scope              = scope.toList(),
        constraints        = constraints.toList(),
        expectedOutput     = expectedOutput,
        completionCriteria = completionCriteria.toList(),
        metadata           = metadata.toMap()
    )
}
