package com.agoii.mobile.simulation

import com.agoii.mobile.core.ReplayStructuralState

/**
 * Lightweight read-only snapshot derived from [ReplayStructuralState] for use inside the
 * simulation pipeline. Internal to this package only.
 *
 * @property assemblyStarted    true once the assembly phase has begun.
 * @property assemblyValidated  true once assembly validation has passed.
 * @property assemblyCompleted  true once the full lifecycle has closed.
 * @property executionCompleted true once all tasks have completed execution.
 *
 * Fields with no structural equivalent carry their data class defaults.
 */
internal data class SimulationSnapshot(
    val phase:              String = "",
    val objective:          String? = null,
    val contractProgress:   Double = 0.0,
    val executionStarted:   Boolean = false,
    val executionCompleted: Boolean,
    val assemblyStarted:    Boolean,
    val assemblyValidated:  Boolean,
    val assemblyCompleted:  Boolean
)

/**
 * Maps a [ReplayStructuralState] into a [SimulationSnapshot] consumed by [SimulationAnalyzer].
 *
 * Rules:
 *  - Read-only: the input [ReplayStructuralState] is never modified.
 *  - Pure: identical input always produces identical output.
 *  - No I/O and no side effects.
 *  - Only structural fields are mapped; absent fields resolve to data class defaults.
 */
internal class SimulationMapper {

    /**
     * Derive a [SimulationSnapshot] from the given [state].
     */
    fun map(state: ReplayStructuralState): SimulationSnapshot {
        return SimulationSnapshot(
            executionCompleted = state.execution.fullyExecuted,
            assemblyStarted    = state.assembly.assemblyStarted,
            assemblyValidated  = state.assembly.assemblyValidated,
            assemblyCompleted  = state.assembly.assemblyCompleted
        )
    }
}
