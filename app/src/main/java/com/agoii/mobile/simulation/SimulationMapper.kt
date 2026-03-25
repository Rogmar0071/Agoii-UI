package com.agoii.mobile.simulation

import com.agoii.mobile.core.ReplayStructuralState

/**
 * Lightweight read-only snapshot derived from [ReplayStructuralState] for use inside the
 * simulation pipeline. Internal to this package only.
 *
 * @property executionStarted   true once the execution phase has been initiated.
 * @property executionCompleted true once all tasks have completed execution.
 * @property assemblyStarted    true once the assembly phase has begun.
 * @property assemblyValidated  true once assembly validation has passed.
 * @property assemblyCompleted  true once the full lifecycle has closed.
 */
internal data class SimulationSnapshot(
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
     *
     * executionStarted is derived as [ReplayStructuralState.execution.assignedTasks] > 0.
     * executionCompleted is derived as [ReplayStructuralState.execution.fullyExecuted].
     */
    fun map(state: ReplayStructuralState): SimulationSnapshot {
        return SimulationSnapshot(
            executionStarted   = state.execution.assignedTasks > 0,
            executionCompleted = state.execution.fullyExecuted,
            assemblyStarted    = state.assembly.assemblyStarted,
            assemblyValidated  = state.assembly.assemblyValidated,
            assemblyCompleted  = state.assembly.assemblyCompleted
        )
    }
}
