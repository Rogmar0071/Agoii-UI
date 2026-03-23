package com.agoii.mobile.assembly

/**
 * Result produced by [AssemblyValidator] after inspecting the full [ReplayState].
 *
 * @property isValid           true when all validation rules pass; false otherwise.
 * @property completionStatus  "COMPLETE" when valid; "INCOMPLETE" otherwise.
 * @property missingElements   Required state elements that are absent.
 * @property failedChecks      Validation rules that were evaluated and failed.
 */
data class AssemblyResult(
    val isValid: Boolean,
    val completionStatus: String,
    val missingElements: List<String>,
    val failedChecks: List<String>
)
