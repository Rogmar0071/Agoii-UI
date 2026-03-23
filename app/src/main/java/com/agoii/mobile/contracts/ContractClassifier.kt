package com.agoii.mobile.contracts

// ─── Contract Classification ──────────────────────────────────────────────────

/**
 * ContractClassifier — maps a [ContractScore] to a [ContractClassification].
 *
 * Classification table (boolean rule; evaluated in priority order):
 *
 *   HIGH   (risky)     — EL > 10 OR RS > 8 OR CCF < 5
 *     → Requires a high-fidelity agent. Adaptation likely needed.
 *
 *   LOW    (safe)      — EL ≤ 6 AND RS ≤ 4 AND CCF ≥ 10
 *     → Safe for execution by any capable agent.
 *
 *   MEDIUM (controlled)— all other cases
 *     → Requires a controlled execution policy.
 *
 * HIGH is evaluated before LOW to ensure that a contract with any high-risk
 * indicator is never silently classified as LOW.
 *
 * Rules:
 *  - Pure function: no state, no side effects.
 *  - Equal inputs always produce equal outputs.
 */
class ContractClassifier {

    /**
     * Classify [score] into [ContractClassification].
     *
     * @param score The mathematical score produced by [ContractScorer].
     * @return [ContractClassification] that determines the execution policy.
     */
    fun classify(score: ContractScore): ContractClassification = when {
        isHigh(score) -> ContractClassification.HIGH
        isLow(score)  -> ContractClassification.LOW
        else          -> ContractClassification.MEDIUM
    }

    // ── thresholds ────────────────────────────────────────────────────────────

    private fun isHigh(score: ContractScore): Boolean =
        score.executionLoad   > HIGH_EL_THRESHOLD    ||
        score.riskScore       > HIGH_RS_THRESHOLD    ||
        score.confidenceIndex < HIGH_CCF_MIN

    private fun isLow(score: ContractScore): Boolean =
        score.executionLoad   <= LOW_EL_MAX   &&
        score.riskScore       <= LOW_RS_MAX   &&
        score.confidenceIndex >= LOW_CCF_MIN

    companion object {
        /** EL above which the contract is classified HIGH. */
        const val HIGH_EL_THRESHOLD = 10

        /** RS above which the contract is classified HIGH. */
        const val HIGH_RS_THRESHOLD = 8

        /** CCF below which the contract is classified HIGH. */
        const val HIGH_CCF_MIN = 5

        /** EL at or below which (combined with RS/CCF) the contract is LOW. */
        const val LOW_EL_MAX = 6

        /** RS at or below which (combined with EL/CCF) the contract is LOW. */
        const val LOW_RS_MAX = 4

        /** CCF at or above which (combined with EL/RS) the contract is LOW. */
        const val LOW_CCF_MIN = 10
    }
}
