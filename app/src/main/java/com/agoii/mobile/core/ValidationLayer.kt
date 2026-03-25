package com.agoii.mobile.core

/**
 * Thrown when a proposed event fails pre-write validation.
 *
 * System Law 4 — Pre-write Validation: no invalid event reaches storage.
 */
class LedgerValidationException(message: String) : RuntimeException(message)

/**
 * Pre-write validation gate — the last line of defence before any event reaches storage.
 *
 * Rules enforced on every [validate] call:
 *  1. [type] must be a member of [EventTypes.ALL] (known event type).
 *  2. If the ledger is empty, [type] must be [EventTypes.INTENT_SUBMITTED] (first-event rule).
 *  3. If the ledger is non-empty, the transition from the current last event's type to [type]
 *     must be legal according to the shared Agoii transition table
 *     (delegated to [LedgerAudit.isLegalTransition] — single source of truth).
 *
 * System Law 4 — Pre-write Validation.
 */
class ValidationLayer {

    /**
     * Validate that appending an event of [type] to [currentEvents] is legal.
     *
     * @param projectId     Used only in error messages for traceability.
     * @param type          The event type being proposed for append.
     * @param currentEvents The ordered list of events already in the ledger.
     * @throws LedgerValidationException on any violation.
     */
    fun validate(projectId: String, type: String, currentEvents: List<Event>) {
        // Rule 1: type must be a known event type
        if (type !in EventTypes.ALL) {
            throw LedgerValidationException(
                "Unknown event type '$type' rejected for project '$projectId'. " +
                    "Type must be a member of EventTypes.ALL."
            )
        }

        if (currentEvents.isEmpty()) {
            // Rule 2: empty ledger — only intent_submitted is a valid first event
            if (type != EventTypes.INTENT_SUBMITTED) {
                throw LedgerValidationException(
                    "Illegal first event '$type' for project '$projectId'. " +
                        "First event must be '${EventTypes.INTENT_SUBMITTED}'."
                )
            }
        } else {
            // Rule 3: transition from last event must be legal
            val lastType = currentEvents.last().type
            if (!LedgerAudit.isLegalTransition(lastType, type)) {
                throw LedgerValidationException(
                    "Illegal transition '$lastType' → '$type' rejected for project '$projectId'."
                )
            }
        }
    }
}
