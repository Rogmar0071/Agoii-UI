package com.agoii.mobile.interaction

import com.agoii.mobile.core.ReplayState

/**
 * Extracts the relevant subset of [ReplayState] for a given [InteractionScope].
 *
 * Responsibility: mapping only — no formatting, no business logic.
 * Every field in the returned [StateSlice] is copied verbatim from [ReplayState].
 */
class InteractionMapper {

    /**
     * Extract the state fields that belong to [scope] from [state].
     *
     * Fields not relevant to a given scope are omitted (set to their zero value)
     * so that downstream formatting is always working with a clean, bounded slice.
     */
    fun extract(scope: InteractionScope, state: ReplayState): StateSlice = when (scope) {

        InteractionScope.FULL_SYSTEM -> StateSlice(
            phase              = state.phase,
            objective          = state.objective,
            contractsCompleted = state.contractsCompleted,
            totalContracts     = state.totalContracts,
            executionStarted   = state.executionStarted,
            executionCompleted = state.executionCompleted,
            assemblyStarted    = state.assemblyStarted,
            assemblyValidated  = state.assemblyValidated,
            references         = listOf("phase", "objective", "contractsCompleted",
                                        "totalContracts", "executionStarted",
                                        "executionCompleted", "assemblyStarted",
                                        "assemblyValidated")
        )

        InteractionScope.CONTRACT -> StateSlice(
            phase              = state.phase,
            objective          = state.objective,
            contractsCompleted = state.contractsCompleted,
            totalContracts     = state.totalContracts,
            executionStarted   = false,
            executionCompleted = false,
            assemblyStarted    = false,
            assemblyValidated  = false,
            references         = listOf("phase", "objective", "contractsCompleted",
                                        "totalContracts")
        )

        InteractionScope.TASK -> StateSlice(
            phase              = state.phase,
            objective          = null,
            contractsCompleted = state.contractsCompleted,
            totalContracts     = state.totalContracts,
            executionStarted   = state.executionStarted,
            executionCompleted = false,
            assemblyStarted    = false,
            assemblyValidated  = false,
            references         = listOf("phase", "contractsCompleted", "totalContracts",
                                        "executionStarted")
        )

        InteractionScope.EXECUTION -> StateSlice(
            phase              = state.phase,
            objective          = null,
            contractsCompleted = state.contractsCompleted,
            totalContracts     = state.totalContracts,
            executionStarted   = state.executionStarted,
            executionCompleted = state.executionCompleted,
            assemblyStarted    = false,
            assemblyValidated  = false,
            references         = listOf("phase", "contractsCompleted", "totalContracts",
                                        "executionStarted", "executionCompleted")
        )

        InteractionScope.SIMULATION -> StateSlice(
            phase              = state.phase,
            objective          = state.objective,
            contractsCompleted = 0,
            totalContracts     = 0,
            executionStarted   = false,
            executionCompleted = false,
            assemblyStarted    = false,
            assemblyValidated  = false,
            references         = listOf("phase", "objective")
        )
    }
}

/**
 * A bounded, immutable subset of [ReplayState] scoped to a single interaction.
 *
 * Used as the intermediate value between [InteractionMapper] and
 * [InteractionFormatter].  Contains no methods and performs no computation.
 */
data class StateSlice(
    val phase: String,
    val objective: String?,
    val contractsCompleted: Int,
    val totalContracts: Int,
    val executionStarted: Boolean,
    val executionCompleted: Boolean,
    val assemblyStarted: Boolean,
    val assemblyValidated: Boolean,
    val references: List<String>
)
