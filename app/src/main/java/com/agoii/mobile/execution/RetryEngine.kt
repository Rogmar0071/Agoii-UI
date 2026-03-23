package com.agoii.mobile.execution

import com.agoii.mobile.contractor.ContractorProfile
import com.agoii.mobile.contractor.ContractorRegistry
import com.agoii.mobile.tasks.Task

// ─── RetryEngine ─────────────────────────────────────────────────────────────

/**
 * Outcome of a retry cycle.
 *
 * @property decision           What the engine decided after evaluating failure.
 * @property assignedContractor The new contractor if decision is [RetryDecision.RETRY] or
 *                              [RetryDecision.REASSIGN]; null for [RetryDecision.ESCALATE].
 * @property reason             Human-readable explanation of the decision.
 */
data class RetryOutcome(
    val decision:           RetryDecision,
    val assignedContractor: ContractorProfile? = null,
    val reason:             String
)

/**
 * Decision produced by [RetryEngine.evaluate].
 *
 *  RETRY     — attempt the same contractor again (attempt < maxRetries).
 *  REASSIGN  — switch to a different contractor from the registry.
 *  ESCALATE  — all options exhausted; mark contract as failed.
 */
enum class RetryDecision { RETRY, REASSIGN, ESCALATE }

/**
 * RetryEngine — deterministic failure recovery with a bounded retry chain.
 *
 * Chain (in order, no skipping):
 *  1. Retry same contractor up to [MAX_RETRIES] times.
 *  2. If still failing → find a different contractor from the registry (one reassignment).
 *  3. If still failing (or no replacement found) → escalate (mark contract failure).
 *
 * Rules:
 *  - No randomness.
 *  - No infinite loops — the chain terminates at ESCALATE.
 *  - Caller is responsible for emitting events; RetryEngine is a pure decision engine.
 *
 * @property registry        The contractor registry used for reassignment lookup.
 * @property maxRetries      Maximum same-contractor retry attempts before reassignment.
 */
class RetryEngine(
    private val registry:   ContractorRegistry,
    val maxRetries:         Int = MAX_RETRIES
) {

    companion object {
        /** Default maximum same-contractor retries before reassignment is attempted. */
        const val MAX_RETRIES = 3
    }

    /**
     * Evaluate what should happen next after a task failure.
     *
     * @param task              The task that failed.
     * @param failedContractor  The contractor that just failed.
     * @param attemptCount      How many times the same contractor has already been tried
     *                          (including the attempt that just failed; starts at 1).
     * @return                  [RetryOutcome] describing the next action.
     */
    fun evaluate(
        task:             Task,
        failedContractor: ContractorProfile,
        attemptCount:     Int
    ): RetryOutcome {
        // Step 1: retry same contractor if under the limit.
        if (attemptCount < maxRetries) {
            return RetryOutcome(
                decision           = RetryDecision.RETRY,
                assignedContractor = failedContractor,
                reason             = "Retrying contractor '${failedContractor.id}' " +
                    "(attempt $attemptCount of $maxRetries)"
            )
        }

        // Step 2: attempt to find a different contractor.
        val replacement = findReplacement(task, failedContractor)
        if (replacement != null) {
            return RetryOutcome(
                decision           = RetryDecision.REASSIGN,
                assignedContractor = replacement,
                reason             = "Reassigning task '${task.taskId}' from " +
                    "'${failedContractor.id}' to '${replacement.id}'"
            )
        }

        // Step 3: escalate — no options remain.
        return RetryOutcome(
            decision = RetryDecision.ESCALATE,
            reason   = "All retry attempts exhausted for task '${task.taskId}'. " +
                "Max retries ($maxRetries) reached and no replacement contractor found. " +
                "Marking contract as failed."
        )
    }

    // ─── Internals ────────────────────────────────────────────────────────────

    /**
     * Find the best verified contractor for [task] that is NOT [exclude].
     * Returns null when no alternative exists.
     */
    private fun findReplacement(task: Task, exclude: ContractorProfile): ContractorProfile? =
        registry.allVerified()
            .filter { it.id != exclude.id }
            .filter { meetsRequirements(it, task.requiredCapabilities) }
            .maxWithOrNull(
                compareBy(
                    { it.capabilities.capabilityScore },
                    { it.reliabilityRatio }
                )
            )

    private fun meetsRequirements(
        profile:      ContractorProfile,
        requirements: Map<String, Int>
    ): Boolean {
        val cap = profile.capabilities
        return requirements.all { (dim, score) ->
            when (dim) {
                "constraintObedience" -> cap.constraintObedience >= score
                "structuralAccuracy"  -> cap.structuralAccuracy  >= score
                "complexityCapacity"  -> cap.complexityCapacity  >= score
                "reliability"         -> cap.reliability         >= score
                "driftScore"          -> cap.driftScore          <= score
                else                  -> true
            }
        }
    }
}
