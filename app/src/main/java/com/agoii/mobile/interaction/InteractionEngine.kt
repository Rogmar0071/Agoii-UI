package com.agoii.mobile.interaction

import com.agoii.mobile.core.ReplayStructuralState

/**
 * Single, non-polymorphic input supplied to [InteractionEngine.execute].
 *
 * [ReplayStructuralState] is the ONLY authority — no alternative input paths exist.
 */
data class InteractionInput(
    val state: ReplayStructuralState
)

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
 *  - Single execution path — no branching on input type.
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
     *   contract.outputType → [InteractionFormatter.format] → content string
     *
     * @param contract Describes what to query and how to format it.
     * @param input    Immutable structural source of truth.
     * @return         [InteractionResult] whose [InteractionResult.content] is
     *                 ready for direct UI rendering.
     */
    fun execute(contract: InteractionContract, input: InteractionInput): InteractionResult {
        val slice = mapper.extract(input.state)

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
