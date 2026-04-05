package com.agoii.mobile.interaction

/**
 * Transforms a [StateSlice] into a human-readable string using the
 * formatting strategy selected by [OutputType].
 *
 * Responsibility: formatting only — no business logic, no data extraction.
 * Every method is a pure function of its input.
 *
 * Source of truth: [StateSlice] structural boolean fields and optional
 * [StateSlice.simulationSummary] only.
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
        val executionStatus = when {
            slice.executionCompleted -> "completed"
            slice.executionStarted   -> "started"
            else                     -> "idle"
        }
        val assemblyStatus = when {
            slice.assemblyValidated -> "validated"
            slice.assemblyStarted   -> "started"
            else                    -> "idle"
        }
        val base = "execution: $executionStatus | assembly: $assemblyStatus"
        return slice.simulationSummary
            ?.let { "$base | simulation: $it" }
            ?: base
    }

    private fun buildDetailed(slice: StateSlice): String =
        "Execution started: ${slice.executionStarted} | " +
        "Execution completed: ${slice.executionCompleted} | " +
        "Assembly started: ${slice.assemblyStarted} | " +
        "Assembly validated: ${slice.assemblyValidated}"

    private fun buildExplanation(slice: StateSlice): String = when {
        slice.assemblyValidated  -> "Execution complete. Assembly validated."
        slice.executionCompleted -> "All execution tasks complete."
        slice.executionStarted   -> "Execution is in progress."
        slice.assemblyStarted    -> "Assembly in progress. Awaiting execution completion."
        else                     -> "System awaiting input."
    }

    private fun buildStatus(slice: StateSlice): String = when {
        slice.assemblyValidated  -> "status=assembly_validated"
        slice.executionCompleted -> "status=execution_completed"
        slice.executionStarted   -> "status=execution_started"
        slice.assemblyStarted    -> "status=assembly_started"
        else                     -> "status=idle"
    }
}
