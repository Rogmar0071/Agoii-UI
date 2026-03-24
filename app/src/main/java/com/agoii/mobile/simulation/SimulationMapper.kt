package com.agoii.mobile.simulation

import com.agoii.mobile.core.ReplayStructuralState

/**
 * Lightweight read-only snapshot derived from [ReplayStructuralState] for use inside the
 * simulation pipeline. Internal to this package only.
 *
 * @property phase              Current lifecycle phase string.
 * @property objective          User-stated objective, or null when not yet submitted.
 * @property contractProgress   Ratio of completed contracts to total (0.0 when total == 0).
 * @property executionStarted   true once the execution phase has been initiated.
 * @property executionCompleted true once all contracts have completed execution.
 * @property assemblyStarted    true once the assembly phase has begun.
 * @property assemblyValidated  true once assembly validation has passed.
 * @property assemblyCompleted  true once the full lifecycle has closed.
 */
internal data class SimulationSnapshot(
    val phase:             String,
    val objective:         String?,
    val contractProgress:  Double,
    val executionStarted:  Boolean,
    val executionCompleted: Boolean,
    val assemblyStarted:   Boolean,
    val assemblyValidated: Boolean,
    val assemblyCompleted: Boolean
)

/**
 * Maps a [ReplayStructuralState] into a [SimulationSnapshot] consumed by [SimulationAnalyzer].
 *
 * Rules:
 *  - Read-only: the input [ReplayStructuralState] is never modified.
 *  - Pure: identical input always produces identical output.
 *  - No I/O and no side effects.
 */
internal class SimulationMapper {

    /**
     * Derive a [SimulationSnapshot] from the given [state].
     */
    fun map(state: ReplayStructuralState): SimulationSnapshot {
        return SimulationSnapshot(
            phase              = "",
            objective          = null,
            contractProgress   = 0.0,
            executionStarted   = false,
            executionCompleted = false,
            assemblyStarted    = state.assembly.assemblyStarted,
            assemblyValidated  = state.assembly.assemblyValidated,
            assemblyCompleted  = state.assembly.assemblyCompleted
        )
    }
}
