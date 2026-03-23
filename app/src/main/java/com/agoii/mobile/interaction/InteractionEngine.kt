package com.agoii.mobile.interaction

import com.agoii.mobile.core.ReplayState
import com.agoii.mobile.simulation.SimulationResult

/**
 * Executes an [InteractionContract] against a [ReplayState] (or a [SimulationResult])
 * and returns a structured, human-readable [InteractionResult].
 *
 * Laws:
 *  - Reads ONLY from [ReplayState] or [SimulationResult] — no I/O, no network, no file access.
 *  - MUST NOT write to the event ledger or call Governor / eventStore.
 *  - Output is deterministic: the same contract and source always produce the same result.
 *  - No caching, no hidden state.
 *  - ALL user-visible output goes through this engine; simulation NEVER outputs directly to UI.
 *
 * Source exclusivity rule:
 *  - [execute]           operates on [ReplayState]    (ledger-based path).
 *  - [executeSimulation] operates on [SimulationResult] (simulation-based path).
 *  - The two paths are mutually exclusive; never combine them for a single contract.
 *
 * The engine delegates scope extraction to [InteractionMapper] and text formatting to
 * [InteractionFormatter] so each responsibility is isolated.
 */
class InteractionEngine(
    private val mapper:    InteractionMapper    = InteractionMapper(),
    private val formatter: InteractionFormatter = InteractionFormatter()
) {

    /**
     * Execute [contract] against [state] and return a fully-formed result.
     *
     * Flow:
     *   contract.scope     → [InteractionMapper.extract]  → [StateSlice]
     *   contract.outputType → [InteractionFormatter.format] → content string
     *
     * @param contract Describes what to query and how to format it.
     * @param state    Immutable replay-derived state — the single source of truth.
     * @return         [InteractionResult] whose [InteractionResult.content] is
     *                 ready for direct UI rendering.
     */
    fun execute(contract: InteractionContract, state: ReplayState): InteractionResult {
        val slice   = mapper.extract(contract.scope, state)
        val content = formatter.format(contract.outputType, slice)
        return InteractionResult(
            contractId = contract.contractId,
            content    = content,
            references = slice.references
        )
    }

    /**
     * Execute [contract] against a [SimulationResult] and return a fully-formed result.
     *
     * This overload routes simulation output through the Interaction pipeline, preserving
     * the invariant that ALL user-visible output flows through [InteractionEngine].
     *
     * Flow:
     *   result             → [InteractionMapper.extractFromSimulation] → [StateSlice]
     *   contract.outputType → [InteractionFormatter.format]            → content string
     *
     * The existing [execute] method is unchanged; this adds a parallel path for
     * simulation-sourced interactions without modifying any existing logic.
     *
     * @param contract Describes what to query and how to format it.
     * @param result   Immutable simulation result — the source of truth for this path.
     * @return         [InteractionResult] whose content is ready for direct UI rendering.
     */
    fun executeSimulation(contract: InteractionContract, result: SimulationResult): InteractionResult {
        val slice   = mapper.extractFromSimulation(contract.scope, result)
        val content = formatter.format(contract.outputType, slice)
        return InteractionResult(
            contractId = contract.contractId,
            content    = content,
            references = slice.references
        )
    }
}
