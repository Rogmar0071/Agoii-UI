package com.agoii.mobile.core.enforcement

// ─── Enforcement Models ───────────────────────────────────────────────────────

/** Classification of a structural enforcement violation. */
enum class ViolationType {
    INVALID_REFERENCE,
    TYPE_MISMATCH,
    INVALID_FIELD,
    DATA_CLASS_MISMATCH,
    ILLEGAL_DERIVATION,
    SUBSTITUTION_DETECTED,
    FLOW_BREAK,
    SCOPE_DRIFT
}

/**
 * Structural surface produced by Step 1 of the enforcement pipeline.
 *
 * @property fields       All field paths found in the contract graph.
 * @property references   All external references (derived field names) found.
 * @property dependencies All declared structural dependencies.
 */
data class SurfaceMap(
    val fields:       List<String>,
    val references:   List<String>,
    val dependencies: List<String>
)

/**
 * A single structural enforcement violation.
 *
 * @property type    Category of violation.
 * @property field   The field or reference that triggered the violation.
 * @property message Human-readable explanation.
 */
data class Violation(
    val type:    ViolationType,
    val field:   String,
    val message: String
)

/**
 * Terminal result of the [EnforcementPipeline].
 *
 * @property approved        true only when all 8 enforcement steps pass with zero violations.
 * @property violations      All detected violations across all steps (empty when approved).
 * @property validatedSurface Structural surface produced in Step 1.
 */
data class EnforcementResult(
    val approved:         Boolean,
    val violations:       List<Violation>,
    val validatedSurface: SurfaceMap
)
