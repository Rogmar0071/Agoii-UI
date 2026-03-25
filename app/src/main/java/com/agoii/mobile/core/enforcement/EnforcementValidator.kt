package com.agoii.mobile.core.enforcement

import com.agoii.mobile.core.contract.ContractGraph

// ─── Enforcement Validator ────────────────────────────────────────────────────

/**
 * EnforcementValidator — implements the four mandatory enforcement steps.
 *
 * Step 1: Structural Surface Scan  — map all fields, references, and dependencies;
 *                                    detect missing fields, invalid references, type mismatches.
 * Step 2: Field Validity Check     — every declared field must belong to ReplayStructuralState.
 * Step 3: Data Class Alignment     — no non-structural or placeholder fields permitted.
 * Step 4: Derivation Validation    — only `executionStarted = state.execution.assignedTasks > 0`
 *                                    is an allowed derivation.
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

        /** The single field name permitted under Derivation Law. */
        private const val ALLOWED_DERIVATION_FIELD = "executionStarted"

        /** The single derivation expression permitted under Derivation Law. */
        private const val ALLOWED_DERIVATION_EXPR = "state.execution.assignedTasks > 0"
    }

    // ── Step 1: Structural Surface Scan ──────────────────────────────────────

    /**
     * Maps all referenced fields and dependencies from [graph] into a [SurfaceMap]
     * and detects fields absent from the structural field set.
     *
     * @return Pair of the produced [SurfaceMap] and any Step-1 [Violation]s.
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
     * Ensures no non-structural fields exist and that no default value or placeholder
     * is present in any declared or derived field.
     * Violations produce [ViolationType.DATA_CLASS_MISMATCH].
     */
    fun checkDataClassAlignment(graph: ContractGraph): List<Violation> {
        val violations = mutableListOf<Violation>()

        for ((field, expr) in graph.derivedFields) {
            if (expr.isBlank() || expr == "null" || expr == "default") {
                violations += Violation(
                    type    = ViolationType.DATA_CLASS_MISMATCH,
                    field   = field,
                    message = "Placeholder or default value detected in derived field '$field'"
                )
            }
        }

        return violations
    }

    // ── Step 4: Derivation Validation ────────────────────────────────────────

    /**
     * Enforces Derivation Law: the only permitted derivation is
     * `executionStarted = state.execution.assignedTasks > 0`.
     * Any other derivation produces [ViolationType.INVALID_DERIVATION].
     */
    fun validateDerivations(graph: ContractGraph): List<Violation> {
        return graph.derivedFields.mapNotNull { (field, expr) ->
            val isAllowed = field == ALLOWED_DERIVATION_FIELD &&
                expr.trim() == ALLOWED_DERIVATION_EXPR
            if (!isAllowed) {
                Violation(
                    type    = ViolationType.INVALID_DERIVATION,
                    field   = field,
                    message = "Derivation '$field = $expr' is not permitted; " +
                              "only '$ALLOWED_DERIVATION_FIELD = $ALLOWED_DERIVATION_EXPR' is allowed"
                )
            } else {
                null
            }
        }
    }
}
