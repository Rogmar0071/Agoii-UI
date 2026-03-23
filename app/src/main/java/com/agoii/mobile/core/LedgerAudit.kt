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
 * Validates that every event in the ledger follows the legal transition table.
 * No state is mutated; only the ledger file is read.
 *
 * Full legal lifecycle enforced:
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

        // Rule 3: every consecutive pair must represent a legal transition
        for (i in 1 until events.size) {
            val prev = events[i - 1].type
            val curr = events[i].type
            if (!isLegalTransition(prev, curr)) {
                errors.add("Event[$i]: Illegal transition '$prev' → '$curr'")
            }
        }

        return AuditResult(
            valid = errors.isEmpty(),
            errors = errors,
            checkedEvents = events.size
        )
    }

    private fun isLegalTransition(from: String, to: String): Boolean {
        // Standard governor-driven single-step transitions (includes assembly pipeline)
        if (Governor.VALID_TRANSITIONS[from] == to) return true
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
        // Governor: task validated → contract can now be completed (critical rule)
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
