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
        val execStatus = when {
            slice.executionCompleted -> "completed"
            slice.executionStarted   -> "in_progress"
            else                     -> "pending"
        }
        val assemblyStatus = when {
            slice.assemblyCompleted  -> "completed"
            slice.assemblyValidated  -> "validated"
            slice.assemblyStarted    -> "started"
            else                     -> "pending"
        }
        return "execution=$execStatus | assembly=$assemblyStatus"
    }

    private fun buildDetailed(slice: StateSlice): String = buildString {
        appendLine("Execution started: ${slice.executionStarted}")
        appendLine("Execution completed: ${slice.executionCompleted}")
        appendLine("Assembly started: ${slice.assemblyStarted}")
        appendLine("Assembly validated: ${slice.assemblyValidated}")
        append("Assembly completed: ${slice.assemblyCompleted}")
    }

    private fun buildExplanation(slice: StateSlice): String = when {
        slice.executionCompleted ->
            "Execution is complete. " +
            "Assembly phase is ${if (slice.assemblyValidated) "validated" else "in progress"}."
        slice.executionStarted ->
            "Execution is in progress."
        else ->
            "System is awaiting execution."
    }

    private fun buildStatus(slice: StateSlice): String {
        val status = when {
            slice.assemblyCompleted  -> "assembly_completed"
            slice.assemblyValidated  -> "assembly_validated"
            slice.assemblyStarted    -> "assembly_started"
            slice.executionCompleted -> "execution_completed"
            slice.executionStarted   -> "execution_started"
            else                     -> "pending"
        }
        return "status=$status"
    }
}
