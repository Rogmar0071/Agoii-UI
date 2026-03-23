package com.agoii.mobile.decision

import com.agoii.mobile.contractor.ContractorProfile
import com.agoii.mobile.contractor.ContractorRegistry
import com.agoii.mobile.tasks.Task

// ─── DecisionEngine ───────────────────────────────────────────────────────────

/**
 * Action decided by [DecisionEngine] after evaluating a task failure.
 *
 *  RETRY     — attempt the same contractor again (failureCount < maxRetries).
 *  REASSIGN  — switch to a different contractor from the registry.
 *  ESCALATE  — all options exhausted; mark contract as failed.
 */
enum class DecisionAction { RETRY, REASSIGN, ESCALATE }

/**
 * Outcome produced by [DecisionEngine.evaluate].
 *
 * @property action             What the engine decided after evaluating failure.
 * @property assignedContractor The new contractor if action is [DecisionAction.RETRY] or
 *                              [DecisionAction.REASSIGN]; null for [DecisionAction.ESCALATE].
 * @property reason             Human-readable explanation of the decision.
 */
data class DecisionOutcome(
    val action:             DecisionAction,
    val assignedContractor: ContractorProfile? = null,
    val reason:             String
)

/**
 * DecisionEngine — the sole authority for determining the next action after task failure.
 *
 * This engine is the **primary decision source** in the task-failed flow.
 * Governor consults only this engine; RetryEngine plays no role in branching.
 *
 * Decision chain (in order, no skipping):
 *  1. Retry same contractor up to [maxRetries] times.
 *  2. If still failing → find a different contractor from the registry (one reassignment).
 *  3. If still failing (or no replacement found) → escalate (mark contract failure).
 *
 * Rules:
 *  - No randomness.
 *  - No infinite loops — the chain terminates at ESCALATE.
 *  - Caller (Governor) is responsible for emitting events; DecisionEngine is a pure decision engine.
 *
 * @property registry   The contractor registry used for reassignment lookup.
 * @property maxRetries Maximum same-contractor retry attempts before reassignment is attempted.
 */
class DecisionEngine(
    private val registry: ContractorRegistry,
    val maxRetries:       Int = MAX_RETRIES
) {

    companion object {
        /** Default maximum same-contractor retries before reassignment is attempted. */
        const val MAX_RETRIES = 3
    }

    /**
     * Evaluate what should happen next after a task failure.
     *
     * @param task             The task that failed.
     * @param failedContractor The contractor that just failed.
     * @param failureCount     How many times the task has failed so far
     *                         (including the failure that just occurred; starts at 1).
     * @return                 [DecisionOutcome] describing the next action.
     */
    fun evaluate(
        task:             Task,
        failedContractor: ContractorProfile,
        failureCount:     Int
    ): DecisionOutcome {
        // Step 1: retry same contractor if under the limit.
        if (failureCount < maxRetries) {
            return DecisionOutcome(
                action             = DecisionAction.RETRY,
                assignedContractor = failedContractor,
                reason             = "Retrying contractor '${failedContractor.id}' " +
                    "(attempt $failureCount of $maxRetries)"
            )
        }

        // Step 2: attempt to find a different contractor.
        val replacement = findReplacement(task, failedContractor)
        if (replacement != null) {
            return DecisionOutcome(
                action             = DecisionAction.REASSIGN,
                assignedContractor = replacement,
                reason             = "Reassigning task '${task.taskId}' from " +
                    "'${failedContractor.id}' to '${replacement.id}'"
            )
        }

        // Step 3: escalate — no options remain.
        return DecisionOutcome(
            action = DecisionAction.ESCALATE,
            reason = "All retry attempts exhausted for task '${task.taskId}'. " +
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
