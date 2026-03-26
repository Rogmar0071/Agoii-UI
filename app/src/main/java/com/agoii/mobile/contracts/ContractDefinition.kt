package com.agoii.mobile.contracts

// ─── Contract Definition ──────────────────────────────────────────────────────

/**
 * Structural definition of a [Contract] before it is finalised and assigned an id.
 *
 * A [ContractDefinition] is the intermediate, mutable-during-construction
 * representation created by each typed builder.  Once all required fields are
 * supplied, [ContractFactory] converts a [ContractDefinition] into an immutable
 * [Contract].
 *
 * @property type               The [ContractType] this definition describes.
 * @property objective          What the contract aims to achieve.
 * @property scope              System boundaries the contract affects.
 * @property constraints        Boundary conditions execution must respect.
 * @property expectedOutput     Description of the successful result.
 * @property completionCriteria Verifiable criteria that mark completion.
 * @property metadata           Optional annotations.
 */
data class ContractDefinition(
    val type:               ContractType,
    val objective:          String,
    val scope:              List<String>,
    val constraints:        List<String>,
    val expectedOutput:     String,
    val completionCriteria: List<String>,
    val metadata:           Map<String, String> = emptyMap()
)

// ─── Validation Result ────────────────────────────────────────────────────────

/**
 * Result of a [ContractValidator] completeness check on a [ContractDefinition].
 *
 * @property complete true when the definition satisfies all completeness rules.
 * @property reasons  Ordered list of failure reasons (empty when [complete] = true).
 */
data class ContractDefinitionValidationResult(
    val complete: Boolean,
    val reasons:  List<String>
)
