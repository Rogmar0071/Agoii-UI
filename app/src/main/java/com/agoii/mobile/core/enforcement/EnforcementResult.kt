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
 * Full structural surface of the system, produced by Step 1 of the enforcement pipeline.
 *
 * This is the authoritative description of all real files, data classes, field usages,
 * and dependencies that the contract is bound to.  It replaces graph-only abstractions
 * that do not reflect the real system surface.
 *
 * @property files        Source file paths that form the structural surface.
 * @property dataClasses  Map of data class name → ordered list of field paths belonging to
 *                        that class.  All entries must have a structural source.
 * @property fieldUsage   Map of field path → list of contract or entity identifiers that
 *                        reference that field.  Keys are the canonical field paths validated
 *                        against [ReplayStructuralState].
 * @property dependencies Ordered list of structural dependency identifiers (e.g. type names)
 *                        that this surface depends on.
 */
data class SurfaceMap(
    val files:        List<String>,
    val dataClasses:  Map<String, List<String>>,
    val fieldUsage:   Map<String, List<String>>,
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
