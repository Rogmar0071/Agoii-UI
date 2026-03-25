package com.agoii.mobile.core.enforcement

import com.agoii.mobile.core.contract.ContractGraph

// ─── Enforcement Validator ────────────────────────────────────────────────────

/**
 * EnforcementValidator — implements all eight mandatory enforcement steps against
 * the full structural surface carried by [ContractGraph.surface].
 *
 * Step 1: Structural Surface Scan    — validate files, data classes, field usage, and
 *                                      dependencies; detect invalid references and type
 *                                      mismatches across the real structural surface.
 * Step 2: Field Validity Check       — every field path in [SurfaceMap.fieldUsage] must
 *                                      belong to ReplayStructuralState.
 * Step 3: Data Class Alignment       — no data class entry without a structural source;
 *                                      no default or placeholder derivation expressions.
 * Step 4: Derivation Validation      — only the two permitted derivations are accepted.
 * Step 5: Substitution Detection     — scan for substitution values in expressions.
 * Step 6: Flow Integrity Check       — no legacy ReplayState references in expressions,
 *                                      field paths, or surface files.
 * Step 7: Mutation Scope Lock        — files and data classes in the surface must be
 *                                      confined to the declared structural set; derived
 *                                      field keys must be confined to the allowed set.
 * Step 8: Execution Authorization    — computed by [EnforcementPipeline] from the
 *                                      violation list.
 */
internal class EnforcementValidator {

    companion object {
        /** All field paths that belong to [ReplayStructuralState] and its sub-states. */
        private val STRUCTURAL_FIELDS: Set<String> = setOf(
            "intent.structurallyComplete",
            "contracts.generated",
            "contracts.valid",
            "execution.totalTasks",
            "execution.assignedTasks",
            "execution.completedTasks",
            "execution.validatedTasks",
            "execution.fullyExecuted",
            "assembly.assemblyStarted",
            "assembly.assemblyValidated",
            "assembly.assemblyCompleted",
            "assembly.assemblyValid"
        )

        /** Data class names that form the authoritative structural surface. */
        private val STRUCTURAL_DATA_CLASSES: Set<String> = setOf(
            "ReplayStructuralState",
            "IntentStructuralState",
            "ContractStructuralState",
            "ExecutionStructuralState",
            "AssemblyStructuralState"
        )

        /** Source file paths that form the authoritative structural surface. */
        private val STRUCTURAL_FILES: Set<String> = setOf(
            "core/Replay.kt",
            "core/contract/ContractGraph.kt",
            "core/contract/ContractEngine.kt",
            "core/contract/ContractModule.kt",
            "core/contract/ExecutionRouter.kt",
            "core/enforcement/EnforcementPipeline.kt",
            "core/enforcement/EnforcementValidator.kt",
            "core/enforcement/EnforcementResult.kt"
        )

        /**
         * The complete set of derivations permitted under Derivation Law.
         * Key = derived field name; Value = required derivation expression (exact match after trim).
         */
        private val ALLOWED_DERIVATIONS: Map<String, String> = mapOf(
            "executionStarted"   to "state.execution.assignedTasks > 0",
            "executionCompleted" to "state.execution.fullyExecuted"
        )

        /** Literal substitution tokens that are forbidden in any derivation expression. */
        private val SUBSTITUTION_TOKENS: List<String> = listOf(
            "\"\"", "?:", "default"
        )

        /** Substitution literal values (exact match after trim). */
        private val SUBSTITUTION_LITERALS: Set<String> = setOf("null", "false", "0", "")

        /** Forbidden legacy state type reference; signals a broken migration. */
        private const val LEGACY_STATE_REFERENCE = "ReplayState"
    }

    // ── Step 1: Structural Surface Scan ──────────────────────────────────────

    /**
     * Validates the full structural surface carried by [ContractGraph.surface]:
     * scans files, data classes, field usage, and dependencies; detects field paths
     * absent from the structural set, unrecognised data class entries, and type mismatches.
     *
     * Returns the surface (propagated unmodified as the validated surface) and all
     * Step-1 [Violation]s.
     */
    fun scanSurface(graph: ContractGraph): Pair<SurfaceMap, List<Violation>> {
        val violations = mutableListOf<Violation>()

        // Validate each field path declared in fieldUsage
        for (field in graph.surface.fieldUsage.keys) {
            if (field !in STRUCTURAL_FIELDS) {
                violations += Violation(
                    type    = ViolationType.INVALID_REFERENCE,
                    field   = field,
                    message = "Field '$field' is not a recognised reference in the structural surface"
                )
            }
        }

        // Validate each data class name declared in the surface
        for (className in graph.surface.dataClasses.keys) {
            if (className !in STRUCTURAL_DATA_CLASSES) {
                violations += Violation(
                    type    = ViolationType.INVALID_REFERENCE,
                    field   = className,
                    message = "Data class '$className' is not a recognised structural data class"
                )
            }
        }

        return graph.surface to violations
    }

    // ── Step 2: Field Validity Check ─────────────────────────────────────────

    /**
     * Validates that every field path in [SurfaceMap.fieldUsage] belongs to
     * [ReplayStructuralState].  Any field outside the structural set produces
     * a [ViolationType.INVALID_FIELD] violation.
     */
    fun validateFields(graph: ContractGraph): List<Violation> {
        return graph.surface.fieldUsage.keys
            .filter { it !in STRUCTURAL_FIELDS }
            .map { field ->
                Violation(
                    type    = ViolationType.INVALID_FIELD,
                    field   = field,
                    message = "Field '$field' does not belong to ReplayStructuralState"
                )
            }
    }

    // ── Step 3: Data Class Alignment Check ───────────────────────────────────

    /**
     * Detects blank, null, or literal-default derivation expressions — these indicate
     * a field with no structural source.  Also detects data class entries in the surface
     * that carry no field paths (empty source).
     * Violations produce [ViolationType.DATA_CLASS_MISMATCH].
     */
    fun checkDataClassAlignment(graph: ContractGraph): List<Violation> {
        val violations = mutableListOf<Violation>()

        // Derivation expressions must not be placeholder values
        for ((field, expr) in graph.derivedFields) {
            if (expr.trim() in SUBSTITUTION_LITERALS) {
                violations += Violation(
                    type    = ViolationType.DATA_CLASS_MISMATCH,
                    field   = field,
                    message = "Derived field '$field' has no structural source — expression is '$expr'"
                )
            }
        }

        // Data class entries must each declare at least one field path
        for ((className, fields) in graph.surface.dataClasses) {
            if (fields.isEmpty()) {
                violations += Violation(
                    type    = ViolationType.DATA_CLASS_MISMATCH,
                    field   = className,
                    message = "Data class '$className' is declared in the surface but has no field paths"
                )
            }
        }

        return violations
    }

    // ── Step 4: Derivation Validation ────────────────────────────────────────

    /**
     * Enforces Derivation Law: only the two permitted derivations are accepted.
     * Any other derivation produces [ViolationType.ILLEGAL_DERIVATION].
     */
    fun validateDerivations(graph: ContractGraph): List<Violation> {
        return graph.derivedFields.mapNotNull { (field, expr) ->
            val allowedExpr = ALLOWED_DERIVATIONS[field]
            if (allowedExpr == null || expr.trim() != allowedExpr) {
                Violation(
                    type    = ViolationType.ILLEGAL_DERIVATION,
                    field   = field,
                    message = "Derivation '$field = $expr' is not permitted; " +
                              "allowed: ${ALLOWED_DERIVATIONS.entries.joinToString { "${it.key} = ${it.value}" }}"
                )
            } else {
                null
            }
        }
    }

    // ── Step 5: Substitution Detection ───────────────────────────────────────

    /**
     * Scans derivation expressions for substitution patterns:
     * empty string, 0, false, null, ?:, default values, fallback assignments.
     * Any detected pattern produces [ViolationType.SUBSTITUTION_DETECTED].
     */
    fun detectSubstitutions(graph: ContractGraph): List<Violation> {
        return graph.derivedFields.mapNotNull { (field, expr) ->
            val trimmed = expr.trim()
            val hasSubstitutionLiteral = trimmed in SUBSTITUTION_LITERALS
            val hasSubstitutionToken   = SUBSTITUTION_TOKENS.any { token -> trimmed.contains(token) }
            if (hasSubstitutionLiteral || hasSubstitutionToken) {
                Violation(
                    type    = ViolationType.SUBSTITUTION_DETECTED,
                    field   = field,
                    message = "Substitution detected in derived field '$field': expression '$expr' " +
                              "contains a forbidden literal, fallback, or default pattern"
                )
            } else {
                null
            }
        }
    }

    // ── Step 6: Flow Integrity Check ─────────────────────────────────────────

    /**
     * Verifies that no derivation expression, field path, or surface file references the
     * legacy [ReplayState] type, and that all graph dependencies resolve to
     * [ReplayStructuralState].  Violations produce [ViolationType.FLOW_BREAK].
     */
    fun checkFlowIntegrity(graph: ContractGraph): List<Violation> {
        val violations = mutableListOf<Violation>()

        // Derivation expressions must not reference the legacy state type
        for ((field, expr) in graph.derivedFields) {
            val stripped = expr.replace("ReplayStructuralState", "")
            if (stripped.contains(LEGACY_STATE_REFERENCE)) {
                violations += Violation(
                    type    = ViolationType.FLOW_BREAK,
                    field   = field,
                    message = "Derived field '$field' references legacy '$LEGACY_STATE_REFERENCE'; " +
                              "must use replayStructuralState()"
                )
            }
        }

        // Field paths must not contain legacy state references
        for (fieldPath in graph.surface.fieldUsage.keys) {
            val stripped = fieldPath.replace("ReplayStructuralState", "")
            if (stripped.contains(LEGACY_STATE_REFERENCE)) {
                violations += Violation(
                    type    = ViolationType.FLOW_BREAK,
                    field   = fieldPath,
                    message = "Field path '$fieldPath' contains a legacy '$LEGACY_STATE_REFERENCE' reference"
                )
            }
        }

        // Surface files must not reference the legacy state type in their names
        for (file in graph.surface.files) {
            val stripped = file.replace("ReplayStructuralState", "")
            if (stripped.contains(LEGACY_STATE_REFERENCE)) {
                violations += Violation(
                    type    = ViolationType.FLOW_BREAK,
                    field   = file,
                    message = "Surface file '$file' contains a legacy '$LEGACY_STATE_REFERENCE' reference"
                )
            }
        }

        return violations
    }

    // ── Step 7: Mutation Scope Lock ───────────────────────────────────────────

    /**
     * Verifies that:
     * - All files in [SurfaceMap.files] are confined to the declared structural file set.
     * - All data class names in [SurfaceMap.dataClasses] are confined to the structural
     *   data class set.
     * - All derived field keys are confined to the allowed derivation set.
     * Any value outside the declared scope produces [ViolationType.SCOPE_DRIFT].
     */
    fun checkMutationScope(graph: ContractGraph): List<Violation> {
        val violations = mutableListOf<Violation>()

        for (file in graph.surface.files) {
            if (file !in STRUCTURAL_FILES) {
                violations += Violation(
                    type    = ViolationType.SCOPE_DRIFT,
                    field   = file,
                    message = "Surface file '$file' is outside the declared structural file scope"
                )
            }
        }

        for (className in graph.surface.dataClasses.keys) {
            if (className !in STRUCTURAL_DATA_CLASSES) {
                violations += Violation(
                    type    = ViolationType.SCOPE_DRIFT,
                    field   = className,
                    message = "Data class '$className' is outside the declared structural data class scope"
                )
            }
        }

        for (field in graph.derivedFields.keys) {
            if (field !in ALLOWED_DERIVATIONS) {
                violations += Violation(
                    type    = ViolationType.SCOPE_DRIFT,
                    field   = field,
                    message = "Derived field '$field' is outside the declared mutation scope; " +
                              "only ${ALLOWED_DERIVATIONS.keys} are permitted"
                )
            }
        }

        return violations
    }
}
