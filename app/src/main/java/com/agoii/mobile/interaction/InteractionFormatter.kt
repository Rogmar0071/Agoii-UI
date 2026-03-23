package com.agoii.mobile.interaction

/**
 * Transforms a [StateSlice] into a human-readable string using the
 * formatting strategy selected by [OutputType].
 *
 * Responsibility: formatting only — no business logic, no data extraction.
 * Every method is a pure function of its input.
 */
class InteractionFormatter {

    /**
     * Produce a formatted string from [slice] according to [outputType].
     *
     * The output is deterministic: the same [slice] and [outputType] always
     * produce the same string.
     */
    fun format(outputType: OutputType, slice: StateSlice): String = when (outputType) {
        OutputType.SUMMARY     -> buildSummary(slice)
        OutputType.DETAILED    -> buildDetailed(slice)
        OutputType.EXPLANATION -> buildExplanation(slice)
        OutputType.STATUS      -> buildStatus(slice)
    }

    // ── private formatting helpers ────────────────────────────────────────────

    private fun buildSummary(slice: StateSlice): String {
        val objectivePart = slice.objective?.let { " | objective: $it" } ?: ""
        return "phase=${slice.phase}$objectivePart | contracts=${slice.contractsCompleted}/${slice.totalContracts}"
    }

    private fun buildDetailed(slice: StateSlice): String = buildString {
        appendLine("Phase: ${slice.phase}")
        slice.objective?.let { appendLine("Objective: $it") }
        appendLine("Contracts: ${slice.contractsCompleted} of ${slice.totalContracts} completed")
        appendLine("Execution started: ${slice.executionStarted}")
        appendLine("Execution completed: ${slice.executionCompleted}")
        appendLine("Assembly started: ${slice.assemblyStarted}")
        appendLine("Assembly validated: ${slice.assemblyValidated}")
        append("Assembly completed: ${slice.assemblyCompleted}")
    }

    private fun buildExplanation(slice: StateSlice): String = when {
        slice.executionCompleted ->
            "All ${slice.totalContracts} contracts have completed execution. " +
            "Assembly phase is ${if (slice.assemblyValidated) "validated" else "in progress"}."
        slice.executionStarted ->
            "Execution is in progress. ${slice.contractsCompleted} of " +
            "${slice.totalContracts} contracts have been completed."
        else -> {
            val obj = slice.objective?.let { " Objective: $it." } ?: ""
            "System is in phase '${slice.phase}'.$obj"
        }
    }

    private fun buildStatus(slice: StateSlice): String = "status=${slice.phase}"
}
