package com.agoii.mobile.core

/**
 * Audit result returned by [LedgerAudit.auditLedger].
 */
data class AuditResult(
    val valid: Boolean,
    val errors: List<String>,
    val checkedEvents: Int
)

/**
 * Validates that every event in the ledger follows [TransitionLaw].
 * No state is mutated; only the ledger file is read.
 *
 * Full legal lifecycle enforced by [TransitionLaw.ALLOWED]:
 *   execution_started → contract_started (position=1)
 *   contract_started  → task_assigned → task_started → task_completed
 *                     → task_validated → contract_completed (same position)
 *   contract_completed → contract_started (next) OR execution_completed (when all done)
 *   execution_completed → assembly_started → assembly_validated → assembly_completed
 */
class LedgerAudit(private val eventStore: EventRepository) {

    fun auditLedger(projectId: String): AuditResult {
        val events = eventStore.loadEvents(projectId)
        if (events.isEmpty()) {
            return AuditResult(valid = true, errors = emptyList(), checkedEvents = 0)
        }

        val errors = mutableListOf<String>()

        // Rule 1: first event must be intent_submitted
        if (events.first().type != EventTypes.INTENT_SUBMITTED) {
            errors.add(
                "First event must be '${EventTypes.INTENT_SUBMITTED}', got '${events.first().type}'"
            )
        }

        // Rule 2: all event types must be from the known set
        events.forEachIndexed { idx, event ->
            if (event.type !in EventTypes.ALL) {
                errors.add("Event[$idx]: Unknown event type '${event.type}'")
            }
        }

        // Rule 3: every consecutive pair must represent a legal transition per TransitionLaw
        for (i in 1 until events.size) {
            val prev = events[i - 1].type
            val curr = events[i].type
            if (!TransitionLaw.isAllowed(prev, curr)) {
                errors.add("Event[$i]: Illegal transition '$prev' → '$curr'")
            }
        }

        return AuditResult(
            valid = errors.isEmpty(),
            errors = errors,
            checkedEvents = events.size
        )
    }
}
