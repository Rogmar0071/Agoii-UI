package com.agoii.mobile.intent

/**
 * Terminal output of [IntentModule.processIntent].
 *
 * Maps one-to-one with [com.agoii.mobile.irs.OrchestratorResult] but lives in the
 * intent boundary so that callers (e.g. CoreBridge) never depend directly on IRS internals.
 *
 * CONTRACT: INTENT_MODULE_V1_TIGHT
 *   - NO ledger references
 *   - NO execution references
 *   - NO IRS internal leakage
 *   - Output-only; immutable
 */
sealed class IntentResult {

    /**
     * IRS certified the intent.  The caller MAY forward [master] to CoreBridge for ledger entry.
     */
    data class Accepted(val master: IntentMaster) : IntentResult()

    /**
     * One or more mandatory fields lack sufficient evidence.
     *
     * @property gaps Field names that require additional evidence before the intent can progress.
     */
    data class NeedsClarification(val gaps: List<String>) : IntentResult()

    /**
     * IRS rejected the intent.
     *
     * @property reason  Machine-readable rejection code (mirrors [com.agoii.mobile.irs.FailureType]).
     * @property details Human-readable explanations.
     */
    data class Rejected(
        val reason: String,
        val details: List<String> = emptyList()
    ) : IntentResult()
}
