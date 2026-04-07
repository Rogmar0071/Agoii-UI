package com.agoii.mobile.interaction

import com.agoii.mobile.contractor.registry.HumanCommunicationContractor
import com.agoii.mobile.core.ReplayStructuralState
import java.util.UUID

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
 *  - Single execution path.
 *
 * The engine delegates state extraction to [InteractionMapper] and text
 * formatting to [InteractionFormatter] so each responsibility is isolated.
 *
 * [processInput] is a TOTAL SAFETY BOUNDARY: it MUST NEVER throw.
 */
class InteractionEngine(
    private val mapper:    InteractionMapper    = InteractionMapper(),
    private val formatter: InteractionFormatter = InteractionFormatter()
) {

    /**
     * Interpret raw human-language [input] into a structured intent payload.
     *
     * TOTAL SAFETY BOUNDARY — this function MUST NEVER throw or propagate an
     * exception to the caller.  All LLM uncertainty, network failures, and
     * JSON parse errors are fully contained here.
     *
     * Delegates to [HumanCommunicationContractor] which uses the LLM exclusively
     * as a language interpreter — NOT as an executor.
     *
     * This is the ONLY sanctioned entry point for LLM-based interpretation.
     * CoreBridge MUST NOT call any LLM contractor directly.
     *
     * @param input  Raw user text captured by the UI.
     * @return       Structured map:
     *               `{"objective": "...", "intentId": "...", "interpretedMeaning": "...", "keyConstraints": [...]}`.
     *               Always returns a valid, well-formed map — never throws.
     */
    fun processInput(input: String): Map<String, Any> {
        if (input.isBlank()) return safetyFallback("unspecified")
        return try {
            HumanCommunicationContractor.parse(input)
        } catch (_: Throwable) {
            safetyFallback(input)
        }
    }

    /**
     * Interpret human approval language into a structured [ApprovalIntent].
     *
     * Pure interpretation only: no execution, no ledger writes, no side effects.
     * Returns null when the text does not map to a governed approval action.
     */
    fun processApprovalInput(input: String, intentId: String): ApprovalIntent? {
        if (intentId.isBlank()) return null
        return when (input.trim().lowercase()) {
            "approve", "confirm" -> ApprovalIntent(intentId, ApprovalAction.APPROVE)
            "reject", "change" -> ApprovalIntent(intentId, ApprovalAction.REJECT)
            else -> null
        }
    }

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
        val slice   = mapper.extract(input.state)
        val content = formatter.format(contract.outputType, slice)

        return InteractionResult(
            contractId = contract.contractId,
            content    = content,
            references = slice.references
        )
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private fun safetyFallback(rawInput: String): Map<String, Any> = mapOf(
        "objective" to rawInput,
        "intentId"  to UUID.randomUUID().toString(),
        "interpretedMeaning" to rawInput,
        "keyConstraints" to emptyList<String>()
    )
}
