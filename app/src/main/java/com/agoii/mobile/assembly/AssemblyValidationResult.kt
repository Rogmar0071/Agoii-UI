package com.agoii.mobile.assembly

/**
 * Result produced by [AssemblyValidator] after inspecting the full event ledger.
 *
 * @property isValid true when all validation rules pass; false otherwise.
 * @property errors  Human-readable error messages; empty when [isValid] is true.
 */
data class AssemblyValidationResult(
    val isValid: Boolean,
    val errors: List<String>
)
