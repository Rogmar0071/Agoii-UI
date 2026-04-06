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

        // Rule 1: first event must be intent_submitted or user_message_submitted.
        // MQP-PHASE-3-FIX-02: USER_MESSAGE_SUBMITTED is the true ledger origin event for
        // the conversational flow. INTENT_SUBMITTED remains valid for non-conversational
        // (batch/programmatic) flows to preserve backward compatibility.
        val firstType = events.first().type
        if (firstType != EventTypes.INTENT_SUBMITTED &&
            firstType != EventTypes.USER_MESSAGE_SUBMITTED) {
            errors.add(
                "First event must be '${EventTypes.INTENT_SUBMITTED}' or " +
                "'${EventTypes.USER_MESSAGE_SUBMITTED}', got '$firstType'"
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

    companion object {
        /**
         * Fixed single-step transitions driven automatically by the governor.
         * Defined using core-only [EventTypes] constants so that [LedgerAudit]
         * and [ValidationLayer] remain independent of the governor package.
         */
        private val validTransitions: Map<String, String> = mapOf(
            EventTypes.INTENT_SUBMITTED    to EventTypes.CONTRACTS_GENERATED,
            EventTypes.CONTRACTS_GENERATED to EventTypes.CONTRACTS_READY,
            EventTypes.CONTRACTS_APPROVED  to EventTypes.EXECUTION_STARTED,
            EventTypes.EXECUTION_COMPLETED to EventTypes.ASSEMBLY_STARTED,
            EventTypes.ASSEMBLY_STARTED    to EventTypes.ASSEMBLY_COMPLETED,
            EventTypes.ASSEMBLY_COMPLETED  to EventTypes.ICS_STARTED,
            EventTypes.ICS_STARTED         to EventTypes.ICS_COMPLETED
        )

        /**
         * Returns true when transitioning [from] → [to] is a legal step in the Agoii
         * lifecycle.  Shared by [LedgerAudit] (post-hoc audit) and [ValidationLayer]
         * (pre-write gate) to guarantee a single source of truth for the transition table.
         */
        internal fun isLegalTransition(from: String, to: String): Boolean {
            // Standard governor-driven single-step transitions (includes assembly pipeline)
            if (validTransitions[from] == to) return true
            // User-driven: approval after contracts are ready
            if (from == EventTypes.CONTRACTS_READY && to == EventTypes.CONTRACTS_APPROVED) return true
            // New lifecycle: contracts_ready advances directly to contract_started (no approval gate)
            if (from == EventTypes.CONTRACTS_READY && to == EventTypes.CONTRACT_STARTED) return true
            // Governor: execution begins — start the first contract
            if (from == EventTypes.EXECUTION_STARTED && to == EventTypes.CONTRACT_STARTED) return true
            // Governor: task lifecycle — contract_started initiates task assignment
            if (from == EventTypes.CONTRACT_STARTED && to == EventTypes.TASK_ASSIGNED) return true
            // Governor: ASSEMBLY_FAILED triggers the Governor-only recovery flow
            if (from == EventTypes.EXECUTION_COMPLETED && to == EventTypes.ASSEMBLY_FAILED) return true
            if (from == EventTypes.ASSEMBLY_FAILED && to == EventTypes.RECOVERY_CONTRACT) return true
            // Backward-compatible direct path (pre-task-lifecycle ledgers remain auditable)
            if (from == EventTypes.CONTRACT_STARTED && to == EventTypes.CONTRACT_COMPLETED) return true
            // Governor: task lifecycle — assignment leads to execution start
            if (from == EventTypes.TASK_ASSIGNED && to == EventTypes.TASK_STARTED) return true
            // ExecutionAuthority: task lifecycle — execution authority writes TASK_EXECUTED after TASK_STARTED
            if (from == EventTypes.TASK_STARTED && to == EventTypes.TASK_EXECUTED) return true
            // Governor: task lifecycle — TASK_EXECUTED (success+validated) advances to completion
            if (from == EventTypes.TASK_EXECUTED && to == EventTypes.TASK_COMPLETED) return true
            // Governor: task lifecycle — TASK_EXECUTED (failure) advances to task_failed
            if (from == EventTypes.TASK_EXECUTED && to == EventTypes.TASK_FAILED) return true
            // Governor: task lifecycle — execution failure (legacy: TASK_STARTED → TASK_FAILED)
            if (from == EventTypes.TASK_STARTED && to == EventTypes.TASK_FAILED) return true
            // ExecutionAuthority: recovery contract written immediately after TASK_EXECUTED (FAILURE)
            if (from == EventTypes.TASK_EXECUTED && to == EventTypes.RECOVERY_CONTRACT) return true
            // Governor (MQP-RECOVERY-CONVERGENCE-BOUND-v1): convergence ceiling — after 3 recovery
            // attempts TASK_EXECUTED(FAILURE) terminates cleanly with EXECUTION_COMPLETED.
            if (from == EventTypes.TASK_EXECUTED && to == EventTypes.EXECUTION_COMPLETED) return true
            // CLC-1 delta loop: Governor responds to recovery by creating a delta contract
            if (from == EventTypes.RECOVERY_CONTRACT && to == EventTypes.DELTA_CONTRACT_CREATED) return true
            // CLC-1 delta loop: Governor emits TASK_ASSIGNED from DELTA_CONTRACT_CREATED
            if (from == EventTypes.DELTA_CONTRACT_CREATED && to == EventTypes.TASK_ASSIGNED) return true
            // Also allow RECOVERY_CONTRACT directly after TASK_FAILED (for pre-EA recovery paths)
            if (from == EventTypes.TASK_FAILED && to == EventTypes.RECOVERY_CONTRACT) return true
            // Governor: task lifecycle — validation of completed task
            if (from == EventTypes.TASK_COMPLETED && to == EventTypes.TASK_VALIDATED) return true
            // Governor: task lifecycle — validation failure
            if (from == EventTypes.TASK_COMPLETED && to == EventTypes.TASK_FAILED) return true
            // New lifecycle: task_completed advances directly to contract_completed (no task_validated step)
            if (from == EventTypes.TASK_COMPLETED && to == EventTypes.CONTRACT_COMPLETED) return true
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
            // UCS-1 ingestion: INTENT_SUBMITTED leads to CONTRACT_CREATED (ingestion path)
            if (from == EventTypes.INTENT_SUBMITTED && to == EventTypes.CONTRACT_CREATED) return true
            // UCS-1 ingestion: validation passed
            if (from == EventTypes.CONTRACT_CREATED && to == EventTypes.CONTRACT_VALIDATED) return true
            // UCS-1 ingestion: validation failed → immediate recovery
            if (from == EventTypes.CONTRACT_CREATED && to == EventTypes.RECOVERY_CONTRACT) return true
            // UCS-1 ingestion: enforcement passed
            if (from == EventTypes.CONTRACT_VALIDATED && to == EventTypes.CONTRACT_APPROVED) return true
            // UCS-1 ingestion: enforcement failed → immediate recovery
            if (from == EventTypes.CONTRACT_VALIDATED && to == EventTypes.RECOVERY_CONTRACT) return true
            // UCS-1 ingestion: approved contract enters execution spine
            if (from == EventTypes.CONTRACT_APPROVED && to == EventTypes.CONTRACTS_GENERATED) return true
            // Commit layer: ICS_COMPLETED triggers COMMIT_CONTRACT gate
            if (from == EventTypes.ICS_COMPLETED && to == EventTypes.COMMIT_CONTRACT) return true
            // Commit layer: user approves → real-world execution
            if (from == EventTypes.COMMIT_CONTRACT && to == EventTypes.COMMIT_EXECUTED) return true
            // Commit layer: user rejects → execution aborted
            if (from == EventTypes.COMMIT_CONTRACT && to == EventTypes.COMMIT_ABORTED) return true
            // ICS loop: re-issued interaction contract (CLOSURE-04)
            if (from == EventTypes.CONTRACTS_GENERATED && to == EventTypes.CONTRACTS_GENERATED) return true
            // Conversational layer (MQP-PHASE-3): turn-1 legacy path (backward compat for batch flows)
            if (from == EventTypes.INTENT_SUBMITTED && to == EventTypes.USER_MESSAGE_SUBMITTED) return true
            // Conversational layer (MQP-PHASE-3 FIX-02): user message is true ledger origin;
            // intent is derived from (and always follows) user input
            if (from == EventTypes.USER_MESSAGE_SUBMITTED && to == EventTypes.INTENT_SUBMITTED) return true
            // Conversational layer (MQP-PHASE-3): backward-compat direct path without re-issuing intent
            if (from == EventTypes.USER_MESSAGE_SUBMITTED && to == EventTypes.CONTRACTS_GENERATED) return true
            // Conversational layer (MQP-PHASE-3 FIX-01): system response anchored to EXECUTION_COMPLETED
            if (from == EventTypes.EXECUTION_COMPLETED && to == EventTypes.SYSTEM_MESSAGE_EMITTED) return true
            // Conversational layer (MQP-PHASE-3): commit layer may follow system message
            if (from == EventTypes.SYSTEM_MESSAGE_EMITTED && to == EventTypes.COMMIT_CONTRACT) return true
            // Conversational layer (MQP-PHASE-3): multi-turn — next user message follows system response
            if (from == EventTypes.SYSTEM_MESSAGE_EMITTED && to == EventTypes.USER_MESSAGE_SUBMITTED) return true
            return false
        }
    }
}
