package com.agoii.mobile.core.enforcement

import com.agoii.mobile.core.contract.ContractGraph

// ─── Enforcement Validator ────────────────────────────────────────────────────

/**
 * EnforcementValidator — implements all eight mandatory enforcement steps.
 *
 * Step 1: Structural Surface Scan    — map files, data classes, field usage, and dependencies;
 *                                      detect missing references, type mismatches, invalid links.
 * Step 2: Field Validity Check       — every declared field must belong to ReplayStructuralState.
 * Step 3: Data Class Alignment       — no field without a structural source; no default or
 *                                      placeholder values in derived fields.
 * Step 4: Derivation Validation      — only the two permitted derivations are accepted.
 * Step 5: Substitution Detection     — scan for substitution values in expressions.
 * Step 6: Flow Integrity Check       — no legacy ReplayState references; all dependencies resolve.
 * Step 7: Mutation Scope Lock        — derived field keys confined to the allowed derivation set.
 * Step 8: Execution Authorization    — computed by [EnforcementPipeline] from the violation list.
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
     * Maps all referenced fields and dependencies from [graph] into a [SurfaceMap]
     * and detects surface-level invalid references (fields absent from the structural set).
     */
    fun scanSurface(graph: ContractGraph): Pair<SurfaceMap, List<Violation>> {
        val violations = mutableListOf<Violation>()

        val surfaceMap = SurfaceMap(
            fields       = graph.declaredFields,
            references   = graph.derivedFields.keys.toList(),
            dependencies = listOf("ReplayStructuralState")
        )

        for (field in graph.declaredFields) {
            if (field !in STRUCTURAL_FIELDS) {
                violations += Violation(
                    type    = ViolationType.INVALID_REFERENCE,
                    field   = field,
                    message = "Field '$field' is not a recognised reference in the structural surface"
                )
            }
        }

        return surfaceMap to violations
    }

    // ── Step 2: Field Validity Check ─────────────────────────────────────────

    /**
     * Validates that every declared field in [graph] belongs to [ReplayStructuralState].
     * Any field outside the structural set produces an [ViolationType.INVALID_FIELD] violation.
     */
    fun validateFields(graph: ContractGraph): List<Violation> {
        return graph.declaredFields
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
     * a field with no structural source.  Violations produce [ViolationType.DATA_CLASS_MISMATCH].
     */
    fun checkDataClassAlignment(graph: ContractGraph): List<Violation> {
        return graph.derivedFields.mapNotNull { (field, expr) ->
            if (expr.trim() in SUBSTITUTION_LITERALS) {
                Violation(
                    type    = ViolationType.DATA_CLASS_MISMATCH,
                    field   = field,
                    message = "Derived field '$field' has no structural source — expression is '$expr'"
                )
            } else {
                null
            }
        }
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
     * Verifies that no derivation expression references the legacy [ReplayState] type,
     * and that all graph dependencies resolve to [ReplayStructuralState].
     * Violations produce [ViolationType.FLOW_BREAK].
     */
    fun checkFlowIntegrity(graph: ContractGraph): List<Violation> {
        val violations = mutableListOf<Violation>()

        for ((field, expr) in graph.derivedFields) {
            // Strip valid "ReplayStructuralState" occurrences first, then check for
            // bare legacy "ReplayState" references in the remainder.
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

        for (dep in graph.declaredFields) {
            val stripped = dep.replace("ReplayStructuralState", "")
            if (stripped.contains(LEGACY_STATE_REFERENCE)) {
                violations += Violation(
                    type    = ViolationType.FLOW_BREAK,
                    field   = dep,
                    message = "Declared field '$dep' contains a legacy '$LEGACY_STATE_REFERENCE' reference"
                )
            }
        }

        return violations
    }

    // ── Step 7: Mutation Scope Lock ───────────────────────────────────────────

    /**
     * Verifies that all derived field keys are confined to the allowed derivation set.
     * Any key outside this set represents scope drift.
     * Violations produce [ViolationType.SCOPE_DRIFT].
     */
    fun checkMutationScope(graph: ContractGraph): List<Violation> {
        return graph.derivedFields.keys
            .filter { it !in ALLOWED_DERIVATIONS }
            .map { field ->
                Violation(
                    type    = ViolationType.SCOPE_DRIFT,
                    field   = field,
                    message = "Derived field '$field' is outside the declared mutation scope; " +
                              "only ${ALLOWED_DERIVATIONS.keys} are permitted"
                )
            }
    }
}
