package com.agoii.mobile.core.enforcement

// ─── Enforcement Models ───────────────────────────────────────────────────────

/** Classification of a structural enforcement violation. */
enum class ViolationType {
    MISSING_FIELD,
    INVALID_REFERENCE,
    TYPE_MISMATCH,
    INVALID_FIELD,
    DATA_CLASS_MISMATCH,
    INVALID_DERIVATION
}

/** Terminal verdict of the enforcement pipeline. */
enum class EnforcementVerdict { APPROVED, REJECTED }

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
 * @property verdict    APPROVED when all steps pass; REJECTED otherwise.
 * @property surfaceMap Structural surface produced in Step 1.
 * @property violations All detected violations across all steps (empty when APPROVED).
 */
data class EnforcementResult(
    val verdict:    EnforcementVerdict,
    val surfaceMap: SurfaceMap,
    val violations: List<Violation>
)
