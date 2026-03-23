package com.agoii.mobile.simulation

import com.agoii.mobile.core.ReplayState

/**
 * Lightweight read-only snapshot derived from [ReplayState] for use inside the
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
 * Maps a [ReplayState] into a [SimulationSnapshot] consumed by [SimulationAnalyzer].
 *
 * Rules:
 *  - Read-only: the input [ReplayState] is never modified.
 *  - Pure: identical input always produces identical output.
 *  - No I/O and no side effects.
 */
internal class SimulationMapper {

    /**
     * Derive a [SimulationSnapshot] from the given [state].
     *
     * Contract progress is computed as [ReplayState.contractsCompleted] /
     * [ReplayState.totalContracts], clamped to 0.0 when [ReplayState.totalContracts] is zero.
     */
    fun map(state: ReplayState): SimulationSnapshot {
        val progress = if (state.totalContracts > 0)
            state.contractsCompleted.toDouble() / state.totalContracts
        else 0.0

        return SimulationSnapshot(
            phase              = state.phase,
            objective          = state.objective,
            contractProgress   = progress,
            executionStarted   = state.executionStarted,
            executionCompleted = state.executionCompleted,
            assemblyStarted    = state.assemblyStarted,
            assemblyValidated  = state.assemblyValidated,
            assemblyCompleted  = state.assemblyCompleted
        )
    }
}
