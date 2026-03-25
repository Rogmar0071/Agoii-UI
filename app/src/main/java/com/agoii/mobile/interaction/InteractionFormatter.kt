package com.agoii.mobile.interaction

/**
 * Transforms a [StateSlice] into a human-readable string using the
 * formatting strategy selected by [OutputType].
 *
 * Responsibility: formatting only — no business logic, no data extraction.
 * Every method is a pure function of its input.
 *
 * Source of truth: [StateSlice] structural boolean fields only.
 * No legacy state or synthetic data is used.
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
        return when {
            slice.assemblyCompleted  -> "assembly completed"
            slice.assemblyValidated  -> "assembly validated"
            slice.assemblyStarted    -> "assembly started"
            slice.executionCompleted -> "execution completed"
            slice.executionStarted   -> "execution started"
            else                     -> throw IllegalStateException("No structural state active")
        }
    }

    private fun buildDetailed(slice: StateSlice): String {
        return when {
            slice.assemblyCompleted  -> "Assembly has been completed."
            slice.assemblyValidated  -> "Assembly has been validated."
            slice.assemblyStarted    -> "Assembly has started."
            slice.executionCompleted -> "Execution has completed."
            slice.executionStarted   -> "Execution is in progress."
            else                     -> throw IllegalStateException("No structural state active")
        }
    }

    private fun buildExplanation(slice: StateSlice): String {
        return when {
            slice.assemblyCompleted  -> "All assembly steps are complete."
            slice.assemblyValidated  -> "Assembly has passed validation."
            slice.assemblyStarted    -> "Assembly process has begun."
            slice.executionCompleted -> "All execution tasks have completed."
            slice.executionStarted   -> "Execution tasks are currently running."
            else                     -> throw IllegalStateException("No structural state active")
        }
    }

    private fun buildStatus(slice: StateSlice): String {
        return when {
            slice.assemblyCompleted  -> "status=assembly_completed"
            slice.assemblyValidated  -> "status=assembly_validated"
            slice.assemblyStarted    -> "status=assembly_started"
            slice.executionCompleted -> "status=execution_completed"
            slice.executionStarted   -> "status=execution_started"
            else                     -> throw IllegalStateException("No structural state active")
        }
    }
}
