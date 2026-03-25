package com.agoii.mobile.interaction

/**
 * Transforms a [StateSlice] into a human-readable string using the
 * formatting strategy selected by [OutputType].
 *
 * Responsibility: formatting only — no business logic, no data extraction.
 * Every method is a pure function of its input.
 *
 * Source of truth: [StateSlice] structural boolean fields only.
 * No [com.agoii.mobile.core.ReplayState] or synthetic data is used.
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
        val executionLabel = when {
            slice.executionCompleted -> "execution: complete"
            slice.executionStarted   -> "execution: in progress"
            else                     -> "execution: not started"
        }
        val assemblyLabel = when {
            slice.assemblyCompleted  -> "assembly: complete"
            slice.assemblyValidated  -> "assembly: validated"
            slice.assemblyStarted    -> "assembly: started"
            else                     -> "assembly: pending"
        }
        return "$executionLabel | $assemblyLabel"
    }

    private fun buildDetailed(slice: StateSlice): String = buildString {
        appendLine("Execution started:    ${slice.executionStarted}")
        appendLine("Execution completed:  ${slice.executionCompleted}")
        appendLine("Assembly started:     ${slice.assemblyStarted}")
        appendLine("Assembly validated:   ${slice.assemblyValidated}")
        append("Assembly completed:   ${slice.assemblyCompleted}")
    }

    private fun buildExplanation(slice: StateSlice): String = when {
        slice.assemblyCompleted ->
            "The system lifecycle is complete. Assembly has been validated and closed."
        slice.assemblyValidated ->
            "Execution is complete and assembly validation has passed. " +
            "Awaiting assembly closure."
        slice.assemblyStarted ->
            "Execution is complete. Assembly has been initiated and is " +
            "awaiting validation."
        slice.executionCompleted ->
            "Execution is complete. The system is ready to begin assembly."
        slice.executionStarted ->
            "Execution is in progress. Awaiting task completion and validation."
        else ->
            "The system is awaiting execution. No tasks have been started."
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
