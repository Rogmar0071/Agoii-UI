package com.agoii.mobile.interaction

import com.agoii.mobile.core.ReplayStructuralState

/**
 * Polymorphic input supplied to [InteractionEngine.execute].
 *
 * The sole active subtype is [LedgerInput], sourced from [ReplayStructuralState].
 */
sealed class InteractionInput {
    /** Input sourced from the event ledger. */
    data class LedgerInput(val state: ReplayStructuralState) : InteractionInput()
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
     *   LedgerInput → [InteractionMapper.extract] → [StateSlice]
     *   contract.outputType → [InteractionFormatter.format] → content string
     *
     * @param contract Describes what to query and how to format it.
     * @param input    Immutable source of truth — ledger state.
     * @return         [InteractionResult] whose [InteractionResult.content] is
     *                 ready for direct UI rendering.
     */
    fun execute(contract: InteractionContract, input: InteractionInput): InteractionResult {
        val slice = when (input) {
            is InteractionInput.LedgerInput -> mapper.extract(input.state)
        }

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
