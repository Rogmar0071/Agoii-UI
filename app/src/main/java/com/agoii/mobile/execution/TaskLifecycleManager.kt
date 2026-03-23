package com.agoii.mobile.execution

// ─── TaskLifecycleManager ─────────────────────────────────────────────────────

/**
 * Valid lifecycle states for a task within the execution engine.
 *
 * Allowed transition sequence (no skipping, no backward movement):
 *   TASK_READY → TASK_ASSIGNED → TASK_STARTED → TASK_COMPLETED → TASK_VALIDATED
 *                                                             ↘ TASK_FAILED
 *
 * TASK_COMPLETED branches to either TASK_VALIDATED (success) or TASK_FAILED (failure).
 * Both TASK_VALIDATED and TASK_FAILED are terminal states.
 */
enum class TaskLifecycleState {
    TASK_READY,
    TASK_ASSIGNED,
    TASK_STARTED,
    TASK_COMPLETED,
    TASK_VALIDATED,
    TASK_FAILED
}

/**
 * TaskLifecycleManager — enforces deterministic, append-only task state transitions.
 *
 * Rules:
 *  - Transitions are only valid in the direction defined by [VALID_TRANSITIONS].
 *  - No state may be skipped.
 *  - No backward transition is permitted.
 *  - TASK_FAILED is the only branching terminal from TASK_COMPLETED.
 *  - TASK_VALIDATED and TASK_FAILED are terminal states; no further transitions.
 */
class TaskLifecycleManager {

    companion object {
        /**
         * Allowed transitions. TASK_COMPLETED can move to either TASK_VALIDATED or
         * TASK_FAILED, so both are mapped.
         */
        val VALID_TRANSITIONS: Map<TaskLifecycleState, Set<TaskLifecycleState>> = mapOf(
            TaskLifecycleState.TASK_READY     to setOf(TaskLifecycleState.TASK_ASSIGNED),
            TaskLifecycleState.TASK_ASSIGNED  to setOf(TaskLifecycleState.TASK_STARTED),
            TaskLifecycleState.TASK_STARTED   to setOf(TaskLifecycleState.TASK_COMPLETED),
            TaskLifecycleState.TASK_COMPLETED to setOf(
                TaskLifecycleState.TASK_VALIDATED,
                TaskLifecycleState.TASK_FAILED
            ),
            TaskLifecycleState.TASK_VALIDATED to emptySet(),
            TaskLifecycleState.TASK_FAILED    to emptySet()
        )
    }

    /**
     * Returns true when [to] is a valid next state from [from].
     * Invalid (skipped, backward, or terminal→any) transitions return false.
     */
    fun canTransition(from: TaskLifecycleState, to: TaskLifecycleState): Boolean =
        VALID_TRANSITIONS[from]?.contains(to) == true

    /**
     * Validates the transition and returns the new state, or throws
     * [IllegalStateException] when the transition is not permitted.
     */
    fun transition(from: TaskLifecycleState, to: TaskLifecycleState): TaskLifecycleState {
        check(canTransition(from, to)) {
            "Invalid task lifecycle transition: $from → $to"
        }
        return to
    }

    /** Returns true when [state] has no further valid transitions. */
    fun isTerminal(state: TaskLifecycleState): Boolean =
        VALID_TRANSITIONS[state].isNullOrEmpty()
}
