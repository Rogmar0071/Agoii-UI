package com.agoii.mobile.interaction

import com.agoii.mobile.core.ReplayState
import com.agoii.mobile.simulation.SimulationResult

/**
 * Extracts the relevant subset of [ReplayState] (or a [SimulationResult] via
 * [SimulationInteractionBridge]) for a given [InteractionScope].
 *
 * Responsibility: mapping only — no formatting, no business logic.
 * Every field in the returned [StateSlice] is copied verbatim from its source.
 */
class InteractionMapper {

    private val bridge = SimulationInteractionBridge()

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

    /**
     * Extract a [StateSlice] from a [SimulationResult] via [SimulationInteractionBridge].
     *
     * This extends mapper capability to cover simulation-sourced interactions.
     * The [scope] parameter is accepted for API symmetry with [extract] but the
     * simulation result always provides a fixed, simulation-specific slice — scope
     * filtering is not applied because simulation data does not map to ledger scopes.
     *
     * Field mapping:
     *  - [StateSlice.phase]              ← "simulation_" + mode name (lower-cased)
     *  - [StateSlice.objective]          ← feasibility + confidence + findings summary
     *  - [StateSlice.contractsCompleted] ← 0 (not applicable for simulation)
     *  - [StateSlice.totalContracts]     ← 0 (not applicable for simulation)
     *  - [StateSlice.executionStarted]   ← [SimulationResult.feasible]
     *  - [StateSlice.executionCompleted] ← true when no failure points were detected
     *  - [StateSlice.assemblyStarted]    ← false (not applicable for simulation)
     *  - [StateSlice.assemblyValidated]  ← false (not applicable for simulation)
     *  - [StateSlice.references]         ← all simulation field names
     *
     * Existing [extract] behaviour is unchanged.
     */
    fun extractFromSimulation(scope: InteractionScope, result: SimulationResult): StateSlice {
        val fields  = bridge.map(result)
        val phase   = "simulation_${fields["mode"]?.lowercase() ?: "unknown"}"
        val objective = buildString {
            append("feasible=${fields["feasible"]}, confidence=${fields["confidence"]}")
            val findings = fields["findings"].orEmpty()
            if (findings.isNotBlank()) append("; findings: $findings")
        }
        return StateSlice(
            phase              = phase,
            objective          = objective,
            contractsCompleted = 0,
            totalContracts     = 0,
            executionStarted   = result.feasible,
            executionCompleted = result.failurePoints.isEmpty(),
            assemblyStarted    = false,
            assemblyValidated  = false,
            references         = listOf("contractId", "mode", "feasible", "confidence",
                                        "findings", "failurePoints", "scenarios")
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
