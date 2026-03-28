package com.agoii.mobile.contracts

// AGOII CONTRACT — CONTRACT LIFECYCLE GOVERNOR (UCS-1)
// SURFACE 5: CONTRACT LIFECYCLE GOVERNANCE
// CLASSIFICATION:
// - Class: Structural
// - Reversibility: Forward-only
// - Execution Scope: Both
// - Mutation Authority: Tier C
//
// GOVERNANCE NOTICE (AGOII-UCS1-SPINE-ALIGN-001):
// In-memory lifecycle tracking by [ExecutionAuthority] has been REPLACED by ledger events:
//   CONTRACT_CREATED   (EventTypes.CONTRACT_CREATED)
//   CONTRACT_VALIDATED (EventTypes.CONTRACT_VALIDATED)
//   CONTRACT_APPROVED  (EventTypes.CONTRACT_APPROVED)
//
// [ContractLifecycleGovernor] remains available as a PURE utility for callers that need
// the canonical (state, event) → nextState function independent of the ledger.
// ExecutionAuthority MUST NOT use this class for internal lifecycle tracking.
//
// PURPOSE:
// Deterministic lifecycle state machine for [UniversalContract] instances.
//
// LIFECYCLE (forward-only transitions):
//   PENDING     → VALIDATED    (VALIDATION_PASSED)
//   PENDING     → FAILED       (VALIDATION_FAILED)
//   VALIDATED   → NORMALIZED   (NORMALIZATION_APPLIED)
//   NORMALIZED  → ROUTED       (ROUTE_DETERMINED)
//   ROUTED      → EXECUTING    (EXECUTION_STARTED)
//   EXECUTING   → EXECUTED     (EXECUTION_SUCCEEDED)
//   EXECUTING   → FAILED       (EXECUTION_FAILED)
//   EXECUTED    → COMPLETED    (REPORT_GENERATED)
//   EXECUTED    → FAILED       (EXECUTION_FAILED — post-execution validation failure)
//   FAILED      → RECOVERING   (RECOVERY_ISSUED)
//   RECOVERING  → FAILED       (EXECUTION_FAILED — retry)
//   RECOVERING  → COMPLETED    (RECOVERY_RESOLVED)
//
// RULES:
//   - All transitions are forward-only; backward transitions are prohibited.
//   - COMPLETED and FAILED are terminal (no automatic further transition).
//   - The governor is stateless: the caller owns lifecycle state persistence.

/**
 * Lifecycle phase of a [UniversalContract] as it moves through the UCS-1 pipeline.
 *
 *  PENDING     — Contract submitted; no validation performed yet.
 *  VALIDATED   — Passed structural + semantic validation (Surface 2).
 *  NORMALIZED  — Canonical form produced (Surface 3).
 *  ROUTED      — Execution route determined (Surface 4).
 *  EXECUTING   — Actively executing in the chosen route.
 *  EXECUTED    — Execution phase complete (AERP-1 report generated).
 *  COMPLETED   — Lifecycle closed (report persisted, no further transitions).
 *  FAILED      — Validation, enforcement, or execution failure.
 *  RECOVERING  — RCF-1 recovery contracts issued; awaiting re-entry.
 */
enum class ContractLifecyclePhase {
    PENDING,
    VALIDATED,
    NORMALIZED,
    ROUTED,
    EXECUTING,
    EXECUTED,
    COMPLETED,
    FAILED,
    RECOVERING
}

/**
 * Event that drives a [ContractLifecyclePhase] transition in [ContractLifecycleGovernor].
 */
enum class ContractLifecycleEvent {
    VALIDATION_PASSED,
    VALIDATION_FAILED,
    NORMALIZATION_APPLIED,
    ROUTE_DETERMINED,
    EXECUTION_STARTED,
    EXECUTION_SUCCEEDED,
    EXECUTION_FAILED,
    REPORT_GENERATED,
    RECOVERY_ISSUED,
    RECOVERY_RESOLVED
}

/**
 * ContractLifecycleGovernor — deterministic lifecycle state machine for [UniversalContract].
 *
 * Entry points:
 *  [transition]  — pure transition function: (phase, event) → nextPhase.
 *  [isTerminal]  — returns true when the phase admits no further transitions.
 *
 * The governor is stateless; it performs no I/O and has no side effects.
 * The caller is solely responsible for persisting lifecycle state between calls.
 */
class ContractLifecycleGovernor {

    /**
     * Compute the next [ContractLifecyclePhase] for a contract in [current] phase
     * when [event] occurs.
     *
     * @param current The contract's current lifecycle phase.
     * @param event   The event driving the transition.
     * @return The resulting [ContractLifecyclePhase], or [current] when no legal
     *         transition exists for this (phase, event) pair (no backward transitions,
     *         no unknown event handling).
     */
    fun transition(current: ContractLifecyclePhase, event: ContractLifecycleEvent): ContractLifecyclePhase =
        TRANSITIONS[current to event] ?: current

    /**
     * Returns true when [phase] is a terminal lifecycle phase.
     *
     * A terminal phase admits no further automatic transitions; re-entry requires
     * an explicit external decision (e.g., a new execution attempt issued via RCF-1).
     */
    fun isTerminal(phase: ContractLifecyclePhase): Boolean = phase in TERMINAL_PHASES

    companion object {
        /** Terminal phases — no further automatic transitions. */
        private val TERMINAL_PHASES = setOf(
            ContractLifecyclePhase.COMPLETED,
            ContractLifecyclePhase.FAILED
        )

        /**
         * Legal (phase, event) → nextPhase transitions.
         *
         * Only forward transitions are permitted.
         * Any (phase, event) pair not listed here is a no-op
         * ([transition] returns [current]).
         */
        private val TRANSITIONS: Map<Pair<ContractLifecyclePhase, ContractLifecycleEvent>, ContractLifecyclePhase> =
            mapOf(
                // PENDING → VALIDATED via successful validation (Surface 2)
                (ContractLifecyclePhase.PENDING to ContractLifecycleEvent.VALIDATION_PASSED)       to ContractLifecyclePhase.VALIDATED,
                // PENDING → FAILED via validation failure
                (ContractLifecyclePhase.PENDING to ContractLifecycleEvent.VALIDATION_FAILED)       to ContractLifecyclePhase.FAILED,
                // VALIDATED → NORMALIZED via normalization (Surface 3)
                (ContractLifecyclePhase.VALIDATED to ContractLifecycleEvent.NORMALIZATION_APPLIED) to ContractLifecyclePhase.NORMALIZED,
                // NORMALIZED → ROUTED via route determination (Surface 4)
                (ContractLifecyclePhase.NORMALIZED to ContractLifecycleEvent.ROUTE_DETERMINED)     to ContractLifecyclePhase.ROUTED,
                // ROUTED → EXECUTING via execution start
                (ContractLifecyclePhase.ROUTED to ContractLifecycleEvent.EXECUTION_STARTED)        to ContractLifecyclePhase.EXECUTING,
                // EXECUTING → EXECUTED on success
                (ContractLifecyclePhase.EXECUTING to ContractLifecycleEvent.EXECUTION_SUCCEEDED)   to ContractLifecyclePhase.EXECUTED,
                // EXECUTING → FAILED on execution failure
                (ContractLifecyclePhase.EXECUTING to ContractLifecycleEvent.EXECUTION_FAILED)      to ContractLifecyclePhase.FAILED,
                // EXECUTED → COMPLETED when AERP-1 report is persisted
                (ContractLifecyclePhase.EXECUTED to ContractLifecycleEvent.REPORT_GENERATED)       to ContractLifecyclePhase.COMPLETED,
                // EXECUTED → FAILED when post-execution validation fails
                (ContractLifecyclePhase.EXECUTED to ContractLifecycleEvent.EXECUTION_FAILED)       to ContractLifecyclePhase.FAILED,
                // FAILED → RECOVERING when RCF-1 recovery contracts are issued (Surface 8)
                (ContractLifecyclePhase.FAILED to ContractLifecycleEvent.RECOVERY_ISSUED)          to ContractLifecyclePhase.RECOVERING,
                // RECOVERING → FAILED on another retry failure
                (ContractLifecyclePhase.RECOVERING to ContractLifecycleEvent.EXECUTION_FAILED)     to ContractLifecyclePhase.FAILED,
                // RECOVERING → COMPLETED when recovery is resolved
                (ContractLifecyclePhase.RECOVERING to ContractLifecycleEvent.RECOVERY_RESOLVED)    to ContractLifecyclePhase.COMPLETED
            )
    }
}
