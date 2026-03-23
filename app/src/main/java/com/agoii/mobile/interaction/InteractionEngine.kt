package com.agoii.mobile.interaction

import com.agoii.mobile.core.ReplayState

/**
 * Executes an [InteractionContract] against a [ReplayState] and returns a
 * structured, human-readable [InteractionResult].
 *
 * Laws:
 *  - Reads ONLY from [ReplayState] — no I/O, no network, no file access.
 *  - MUST NOT write to the event ledger or call Governor / eventStore.
 *  - Output is deterministic: the same contract and state always produce the
 *    same result.
 *  - No caching, no hidden state.
 *
 * The engine delegates scope extraction to [InteractionMapper] and text
 * formatting to [InteractionFormatter] so each responsibility is isolated.
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
}
