package com.agoii.mobile.interaction

import com.agoii.mobile.core.ReplayStructuralState

/**
 * Structural input supplied to [InteractionEngine.execute].
 *
 * Wraps [ReplayStructuralState] — the ONLY legal source of truth for the
 * interaction pipeline. Simulation input is NOT accepted.
 */
data class InteractionInput(val state: ReplayStructuralState)

/**
 * Executes an [InteractionContract] against a structural [InteractionInput] and
 * returns a deterministic [InteractionResult].
 *
 * Laws:
 *  - Reads ONLY from the supplied [InteractionInput.state] — no I/O, no network, no file access.
 *  - MUST NOT write to the event ledger or call Governor / eventStore.
 *  - Output is deterministic: the same contract and input always produce the same result.
 *  - No caching, no hidden state.
 *  - Simulation domain MUST NOT reach this engine.
 *
 * The engine delegates state extraction to [InteractionMapper] and text
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
     *   [InteractionInput.state] → [InteractionMapper.extract] → [StateSlice]
     *                           → [InteractionFormatter.format] → content string
     *
     * @param contract Describes what to query and how to format it.
     * @param input    Immutable structural source of truth — wraps [ReplayStructuralState].
     * @return         [InteractionResult] whose [InteractionResult.content] is
     *                 derived exclusively from [StateSlice] via [InteractionFormatter].
     */
    fun execute(contract: InteractionContract, input: InteractionInput): InteractionResult {
        val slice   = mapper.extract(input.state)
        val content = formatter.format(contract.outputType, slice)
        return InteractionResult(
            contractId = contract.contractId,
            content    = content,
            references = listOf(
                "executionStarted",
                "executionCompleted",
                "assemblyStarted",
                "assemblyValidated",
                "assemblyCompleted"
            )
        )
    }
}
