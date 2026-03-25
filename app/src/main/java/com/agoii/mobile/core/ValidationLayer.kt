package com.agoii.mobile.core

/**
 * ValidationLayer — pre-write event validation gate.
 *
 * Every event must pass this check before [EventLedger] persists it.
 * The rules mirror [LedgerAudit] exactly so that events accepted here
 * will always produce a passing audit on replay.
 *
 * Rules (evaluated in order):
 *  1. The event type must be a member of [EventTypes.ALL].
 *  2. If the ledger is empty, the first event must be [EventTypes.INTENT_SUBMITTED].
 *  3. The transition from the current last event to the new event must be legal
 *     according to the same transition table enforced by [LedgerAudit].
 */
class ValidationLayer {

    sealed class ValidationResult {
        object Accepted : ValidationResult()
        data class Rejected(val reason: String) : ValidationResult()
    }

    /**
     * Validate [newEventType] against the [currentEvents] already in the ledger.
     *
     * @param currentEvents The ordered list of events already persisted.
     * @param newEventType  The type string of the event about to be appended.
     * @return [ValidationResult.Accepted] when the event is legal;
     *         [ValidationResult.Rejected] with a human-readable reason otherwise.
     */
    fun validate(currentEvents: List<Event>, newEventType: String): ValidationResult {
        // Rule 1: type must be known
        if (newEventType !in EventTypes.ALL) {
            return ValidationResult.Rejected("Unknown event type: '$newEventType'")
        }

        // Rule 2: empty ledger — first event must be intent_submitted
        if (currentEvents.isEmpty()) {
            return if (newEventType == EventTypes.INTENT_SUBMITTED) {
                ValidationResult.Accepted
            } else {
                ValidationResult.Rejected(
                    "First event must be '${EventTypes.INTENT_SUBMITTED}', got '$newEventType'"
                )
            }
        }

        // Rule 3: transition from last event must be legal
        val lastType = currentEvents.last().type
        return if (isLegalTransition(lastType, newEventType)) {
            ValidationResult.Accepted
        } else {
            ValidationResult.Rejected(
                "Illegal transition '$lastType' → '$newEventType'"
            )
        }
    }

    /**
     * Returns true when transitioning from [from] to [to] is permitted.
     * Mirrors the full transition table in [LedgerAudit.isLegalTransition].
     */
    private fun isLegalTransition(from: String, to: String): Boolean {
        // Standard single-step governor transitions (includes assembly pipeline)
        val singleStep = mapOf(
            EventTypes.INTENT_SUBMITTED    to EventTypes.CONTRACTS_GENERATED,
            EventTypes.CONTRACTS_GENERATED to EventTypes.CONTRACTS_READY,
            EventTypes.CONTRACTS_APPROVED  to EventTypes.EXECUTION_STARTED,
            EventTypes.EXECUTION_COMPLETED to EventTypes.ASSEMBLY_STARTED,
            EventTypes.ASSEMBLY_STARTED    to EventTypes.ASSEMBLY_VALIDATED,
            EventTypes.ASSEMBLY_VALIDATED  to EventTypes.ASSEMBLY_COMPLETED
        )
        if (singleStep[from] == to) return true
        // User-driven: approval after contracts are ready
        if (from == EventTypes.CONTRACTS_READY && to == EventTypes.CONTRACTS_APPROVED) return true
        // Governor: execution begins — start the first contract
        if (from == EventTypes.EXECUTION_STARTED && to == EventTypes.CONTRACT_STARTED) return true
        // Governor: task lifecycle — contract_started initiates task assignment
        if (from == EventTypes.CONTRACT_STARTED && to == EventTypes.TASK_ASSIGNED) return true
        // Backward-compatible direct path (pre-task-lifecycle ledgers remain auditable)
        if (from == EventTypes.CONTRACT_STARTED && to == EventTypes.CONTRACT_COMPLETED) return true
        // Governor: task lifecycle — assignment leads to execution start
        if (from == EventTypes.TASK_ASSIGNED && to == EventTypes.TASK_STARTED) return true
        // Governor: task lifecycle — execution produces a completion event
        if (from == EventTypes.TASK_STARTED && to == EventTypes.TASK_COMPLETED) return true
        // Governor: task lifecycle — execution failure
        if (from == EventTypes.TASK_STARTED && to == EventTypes.TASK_FAILED) return true
        // Governor: task lifecycle — validation of completed task
        if (from == EventTypes.TASK_COMPLETED && to == EventTypes.TASK_VALIDATED) return true
        // Governor: task lifecycle — validation failure
        if (from == EventTypes.TASK_COMPLETED && to == EventTypes.TASK_FAILED) return true
        // Governor: task validated → contract can now be completed
        if (from == EventTypes.TASK_VALIDATED && to == EventTypes.CONTRACT_COMPLETED) return true
        // Governor: task failed → retry with same or reassigned contractor
        if (from == EventTypes.TASK_FAILED && to == EventTypes.TASK_ASSIGNED) return true
        // Governor: task failed → reassign contractor
        if (from == EventTypes.TASK_FAILED && to == EventTypes.CONTRACTOR_REASSIGNED) return true
        // Governor: task failed → escalate (all retries exhausted)
        if (from == EventTypes.TASK_FAILED && to == EventTypes.CONTRACT_FAILED) return true
        // Governor: reassignment leads to new task assignment
        if (from == EventTypes.CONTRACTOR_REASSIGNED && to == EventTypes.TASK_ASSIGNED) return true
        // Governor: completed contract leads to the next one
        if (from == EventTypes.CONTRACT_COMPLETED && to == EventTypes.CONTRACT_STARTED) return true
        // Governor: all contracts completed — close the execution phase
        if (from == EventTypes.CONTRACT_COMPLETED && to == EventTypes.EXECUTION_COMPLETED) return true
        return false
    }
}
