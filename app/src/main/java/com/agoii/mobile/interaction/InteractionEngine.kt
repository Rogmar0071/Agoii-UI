package com.agoii.mobile.interaction

import com.agoii.mobile.core.ReplayState
import com.agoii.mobile.simulation.SimulationView

/**
 * Polymorphic input supplied to [InteractionEngine.execute].
 *
 * Exactly one subtype is active per invocation:
 *  - [LedgerInput]     → state extracted from the event ledger via [ReplayState].
 *  - [SimulationInput] → view produced by the Simulation layer via [SimulationView].
 */
sealed class InteractionInput {
    /** Input sourced from the event ledger. */
    data class LedgerInput(val state: ReplayState) : InteractionInput()

    /** Input sourced from a completed simulation. */
    data class SimulationInput(val view: SimulationView) : InteractionInput()
}

/**
 * Executes an [InteractionContract] against an [InteractionInput] and returns a
 * structured, human-readable [InteractionResult].
 *
 * Laws:
 *  - Reads ONLY from the supplied input — no I/O, no network, no file access.
 *  - MUST NOT write to the event ledger or call Governor / eventStore.
 *  - Output is deterministic: the same contract and input always produce the
 *    same result.
 *  - No caching, no hidden state.
 *  - ONE execution pipeline: a single [execute] entry point handles all input types.
 *
 * The engine delegates scope extraction to [InteractionMapper] and text
 * formatting to [InteractionFormatter] so each responsibility is isolated.
 */
class InteractionEngine(
    private val mapper:    InteractionMapper    = InteractionMapper(),
    private val formatter: InteractionFormatter = InteractionFormatter()
) {

    /**
     * Execute [contract] against [input] and return a fully-formed result.
     *
     * Flow:
     *   LedgerInput     → [InteractionMapper.extract]                → [StateSlice]
     *   SimulationInput → [InteractionMapper.extractFromSimulationView] → [StateSlice]
     *   contract.outputType → [InteractionFormatter.format] → content string
     *
     * @param contract Describes what to query and how to format it.
     * @param input    Immutable source of truth — either ledger state or simulation view.
     * @return         [InteractionResult] whose [InteractionResult.content] is
     *                 ready for direct UI rendering.
     */
    fun execute(contract: InteractionContract, input: InteractionInput): InteractionResult {
        val slice = when (input) {
            is InteractionInput.LedgerInput     -> mapper.extract(contract.scope, input.state)
            is InteractionInput.SimulationInput -> mapper.extractFromSimulationView(input.view)
        }

        val content = formatter.format(contract.outputType, slice)

        return InteractionResult(
            contractId = contract.contractId,
            content    = content,
            references = slice.references
        )
    }
}
