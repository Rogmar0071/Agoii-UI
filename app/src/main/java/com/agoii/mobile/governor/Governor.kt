package com.agoii.mobile.governor

import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventTypes

/**
 * Governor — pure deterministic state machine.
 *
 * Invariants:
 *  - [nextEvent] is the ONLY public function.
 *  - It is pure: no side effects, no I/O, no external dependencies.
 *  - All decisions are derived exclusively from the supplied [events] list
 *    (last event + ledger history).
 *  - Returns exactly one [Event] per call, or null when paused or terminal.
 *  - The caller is responsible for persisting the returned event.
 *
 * Lifecycle (each arrow = one nextEvent call; [pause] = returns null):
 *   intent_submitted → contracts_generated → contracts_ready [pause]
 *   contracts_approved → execution_started
 *     → contract_started(1)
 *     → task_assigned → task_started [pause — external work]
 *     → task_completed → task_validated
 *     → contract_completed → contract_started(2) → … → contract_started(N)
 *     → contract_completed(N) → execution_completed
 *     → assembly_started → assembly_validated → assembly_completed [terminal]
 */
class Governor {

    companion object {
        /** Deterministic contractor identifier — derived from system constants, no registry. */
        const val DEFAULT_CONTRACTOR = "default-contractor"
    }

    /**
     * Given the full ordered ledger [events], returns the next [Event] to append,
     * or null if the lifecycle is paused waiting for external input or has reached a
     * terminal state.
     *
     * This function has no side effects. The caller is responsible for persisting
     * the returned event via [com.agoii.mobile.core.EventRepository].
     */
    fun nextEvent(events: List<Event>): Event? {
        if (events.isEmpty()) return null

        val last = events.last()

        return when (last.type) {

            // ── Pre-execution pipeline ────────────────────────────────────────────

            EventTypes.INTENT_SUBMITTED -> {
                val intent = last.payload["objective"] as? String ?: return null
                Event(
                    type = EventTypes.CONTRACTS_GENERATED,
                    payload = mapOf(
                        "source_intent" to intent,
                        "contracts" to listOf(
                            mapOf("id" to "contract_1", "name" to "Core Setup"),
                            mapOf("id" to "contract_2", "name" to "Integration"),
                            mapOf("id" to "contract_3", "name" to "Validation")
                        )
                    )
                )
            }

            EventTypes.CONTRACTS_GENERATED ->
                Event(type = EventTypes.CONTRACTS_READY, payload = emptyMap())

            // Pause: waiting for explicit user approval.
            EventTypes.CONTRACTS_READY -> null

            EventTypes.CONTRACTS_APPROVED ->
                Event(type = EventTypes.EXECUTION_STARTED, payload = emptyMap())

            // ── Execution spine ───────────────────────────────────────────────────

            EventTypes.EXECUTION_STARTED ->
                Event(
                    type = EventTypes.CONTRACT_STARTED,
                    payload = mapOf("contract_id" to "contract_1", "position" to 1)
                )

            EventTypes.CONTRACT_STARTED -> {
                val contractId = last.payload["contract_id"] as? String ?: return null
                val position   = resolveInt(last.payload["position"])   ?: return null
                Event(
                    type = EventTypes.TASK_ASSIGNED,
                    payload = mapOf(
                        "taskId"       to "$contractId-task",
                        "contractorId" to DEFAULT_CONTRACTOR,
                        "contract_id"  to contractId,
                        "position"     to position
                    )
                )
            }

            EventTypes.TASK_ASSIGNED -> {
                val taskId       = last.payload["taskId"]       as? String ?: return null
                val contractorId = last.payload["contractorId"] as? String ?: return null
                val contractId   = last.payload["contract_id"]  as? String ?: return null
                val position     = resolveInt(last.payload["position"])    ?: return null
                Event(
                    type = EventTypes.TASK_STARTED,
                    payload = mapOf(
                        "taskId"       to taskId,
                        "contractorId" to contractorId,
                        "contract_id"  to contractId,
                        "position"     to position
                    )
                )
            }

            // Pause: external system performs the work and writes task_completed or task_failed.
            EventTypes.TASK_STARTED -> null

            EventTypes.TASK_COMPLETED -> {
                val taskId     = last.payload["taskId"]      as? String ?: return null
                val contractId = last.payload["contract_id"] as? String ?: return null
                val position   = resolveInt(last.payload["position"])   ?: return null
                Event(
                    type = EventTypes.TASK_VALIDATED,
                    payload = mapOf(
                        "taskId"      to taskId,
                        "contract_id" to contractId,
                        "position"    to position
                    )
                )
            }

            EventTypes.TASK_VALIDATED -> {
                val contractId = last.payload["contract_id"] as? String ?: return null
                val position   = resolveInt(last.payload["position"])   ?: return null
                Event(
                    type = EventTypes.CONTRACT_COMPLETED,
                    payload = mapOf("contract_id" to contractId, "position" to position)
                )
            }

            // Pause: no retry/reassignment/escalation — external handling required.
            EventTypes.TASK_FAILED -> null

            EventTypes.CONTRACTOR_REASSIGNED -> {
                val taskId          = last.payload["taskId"]          as? String ?: return null
                val newContractorId = last.payload["newContractorId"] as? String ?: return null
                val contractId      = last.payload["contract_id"]     as? String ?: return null
                val position        = resolveInt(last.payload["position"])        ?: return null
                Event(
                    type = EventTypes.TASK_ASSIGNED,
                    payload = mapOf(
                        "taskId"       to taskId,
                        "contractorId" to newContractorId,
                        "contract_id"  to contractId,
                        "position"     to position
                    )
                )
            }

            EventTypes.CONTRACT_COMPLETED -> {
                val position = resolveInt(last.payload["position"]) ?: return null
                if (position < EventTypes.DEFAULT_TOTAL_CONTRACTS) {
                    val next = position + 1
                    Event(
                        type = EventTypes.CONTRACT_STARTED,
                        payload = mapOf("contract_id" to "contract_$next", "position" to next)
                    )
                } else {
                    Event(type = EventTypes.EXECUTION_COMPLETED, payload = emptyMap())
                }
            }

            // ── Assembly pipeline ─────────────────────────────────────────────────

            EventTypes.EXECUTION_COMPLETED ->
                Event(type = EventTypes.ASSEMBLY_STARTED, payload = emptyMap())

            EventTypes.ASSEMBLY_STARTED ->
                Event(type = EventTypes.ASSEMBLY_VALIDATED, payload = emptyMap())

            EventTypes.ASSEMBLY_VALIDATED ->
                Event(type = EventTypes.ASSEMBLY_COMPLETED, payload = emptyMap())

            // Terminal state.
            EventTypes.ASSEMBLY_COMPLETED -> null

            else -> null
        }
    }

    /** Gson deserialises all numbers as Double; this helper normalises to Int. */
    private fun resolveInt(value: Any?): Int? = when (value) {
        is Int    -> value
        is Double -> value.toInt()
        is Long   -> value.toInt()
        is String -> value.toIntOrNull()
        else      -> null
    }
}
