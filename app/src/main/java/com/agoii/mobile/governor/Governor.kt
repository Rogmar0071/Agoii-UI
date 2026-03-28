package com.agoii.mobile.governor

import com.agoii.mobile.contractor.ContractorRegistry
import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventRepository
import com.agoii.mobile.core.EventTypes

/**
 * Governor — deterministic ledger-driven state machine.
 *
 * Invariants:
 *  - [runGovernor] is the primary public entry point; it reads from and writes to [store].
 *  - [nextEvent] is a pure projection function: no side effects, no I/O.
 *  - All decisions are derived exclusively from the ledger state.
 *  - CONTRACTS_GENERATED is NOT authored here; it is the [com.agoii.mobile.execution.ExecutionAuthority]'s
 *    responsibility to validate, authorize, and persist it before Governor is called.
 *  - Governor NEVER calls ContractSystemOrchestrator or any external system.
 *
 * Lifecycle (each arrow = one [runGovernor] call; [ext] = external event; [terminal] = COMPLETED):
 *   intent_submitted [ext — CONTRACTS_GENERATED written by Execution Authority]
 *     → contracts_generated → contracts_ready
 *     → contract_started(1) → task_assigned → task_started → task_completed
 *     → contract_completed(1) → contract_started(2) → … → contract_started(N)
 *     → task_assigned → task_started → task_completed
 *     → contract_completed(N) → execution_completed [terminal]
 *
 * CSL gate: a contract at position P is only issued when EL = [CONTRACT_BASE_LOAD] + P ≤ [VC].
 */
class Governor(
    private val store: EventRepository,
    private val registry: ContractorRegistry? = null
) {

    companion object {
        /**
         * Velocity Ceiling — maximum execution load (EL) permitted per contract.
         *
         * EL is calculated as [CONTRACT_BASE_LOAD] + position, where position is the
         * 1-based index of the contract in the execution sequence. A contract is only
         * issued when EL ≤ VC (see [canIssue]):
         *   position 1 → EL = 3 ≤ 5 ✓
         *   position 2 → EL = 4 ≤ 5 ✓
         *   position 3 → EL = 5 ≤ 5 ✓
         *   position 4 → EL = 6 > 5 ✗ (DRIFT)
         */
        const val VC = 5

        /** Base execution load contributed by every contract regardless of position. */
        const val CONTRACT_BASE_LOAD = 2

        /**
         * Simple passthrough transitions driven automatically by the Governor.
         * Each entry maps a terminal event type to the single event type that follows it,
         * with an empty payload. Transitions requiring payload logic are handled
         * explicitly in [nextEvent].
         *
         * Note: intent_submitted → contracts_generated is NOT listed here because
         * CONTRACTS_GENERATED is authored by [com.agoii.mobile.execution.ExecutionAuthority],
         * not by the Governor.
         * Note: contracts_ready → contract_started is handled explicitly in [nextEvent]
         * because it requires payload (position, total, contract_id).
         */
        val VALID_TRANSITIONS: Map<String, String> = mapOf(
            EventTypes.CONTRACTS_GENERATED to EventTypes.CONTRACTS_READY
        )
    }

    /** Result of a single [runGovernor] invocation. */
    enum class GovernorResult {
        /** An event was derived and appended to the ledger. */
        ADVANCED,
        /** The ledger is empty; no action taken. */
        NO_EVENT,
        /** Terminal state (EXECUTION_COMPLETED); lifecycle is closed. */
        COMPLETED,
        /** No valid transition could be derived (CSL gate or unknown state). */
        DRIFT
    }

    /**
     * Advance the ledger by exactly one step and return the outcome.
     *
     * Reads the current ledger state from [store], computes the next event via
     * [nextEvent], appends it to [store], and returns [GovernorResult.ADVANCED].
     * Returns a non-ADVANCED result for known wait states, the terminal state, or
     * when no valid transition exists (CSL gate or drift).
     */
    fun runGovernor(projectId: String): GovernorResult {
        val events = store.loadEvents(projectId)
        if (events.isEmpty()) return GovernorResult.NO_EVENT

        val last = events.last()

        return when (last.type) {
            // CONTRACTS_GENERATED is authored by ExecutionAuthority — not by Governor.
            // Governor reads from the ledger only; if it has not yet been written, wait.
            EventTypes.INTENT_SUBMITTED    -> GovernorResult.NO_EVENT

            // Terminal: EXECUTION_COMPLETED closes the lifecycle — no further events.
            EventTypes.EXECUTION_COMPLETED -> GovernorResult.COMPLETED

            else -> {
                val next = nextEvent(events)
                if (next != null) {
                    store.appendEvent(projectId, next.type, next.payload)
                    GovernorResult.ADVANCED
                } else {
                    GovernorResult.DRIFT
                }
            }
        }
    }

    /**
     * Pure projection: given the full ordered ledger [events], returns the next [Event]
     * to append, or null when no Governor-owned transition exists (wait state, CSL gate,
     * terminal, or drift).
     *
     * This function has no side effects. The caller is responsible for persisting
     * the returned event via [com.agoii.mobile.core.EventRepository].
     */
    fun nextEvent(events: List<Event>): Event? {
        if (events.isEmpty()) return null
        val last = events.last()

        return when (last.type) {

            // CONTRACTS_GENERATED is the Execution Authority's responsibility.
            // Governor does not derive contracts; it waits.
            EventTypes.INTENT_SUBMITTED -> null

            // ── Simple passthrough transitions (empty payload) ────────────────────

            in VALID_TRANSITIONS.keys -> {
                val target = VALID_TRANSITIONS.getValue(last.type)
                Event(type = target, payload = emptyMap())
            }

            // ── Execution spine ───────────────────────────────────────────────────

            EventTypes.CONTRACTS_READY -> {
                val total = deriveTotal(events) ?: return null
                canIssue(1) ?: return null
                Event(
                    type    = EventTypes.CONTRACT_STARTED,
                    payload = mapOf("position" to 1, "total" to total, "contract_id" to "contract_1")
                )
            }

            EventTypes.CONTRACT_STARTED -> {
                val contractId = last.payload["contract_id"] as? String ?: return null
                val position   = resolveInt(last.payload["position"])    ?: return null
                val total      = deriveTotal(events)                      ?: return null
                val reportRef  = deriveReportReference(events)
                Event(
                    type    = EventTypes.TASK_ASSIGNED,
                    payload = mapOf(
                        "taskId"           to "$contractId-step1",
                        "contractId"       to contractId,
                        "position"         to position,
                        "total"            to total,
                        "report_reference" to reportRef,
                        "requirements"     to emptyList<Any>(),
                        "constraints"      to emptyList<Any>()
                    )
                )
            }

            EventTypes.TASK_ASSIGNED -> {
                val taskId   = last.payload["taskId"] as? String ?: return null
                val position = resolveInt(last.payload["position"])
                    ?: events.lastOrNull { it.type == EventTypes.CONTRACT_STARTED }
                        ?.payload?.let { resolveInt(it["position"]) }
                    ?: return null
                val total = resolveInt(last.payload["total"])
                    ?: deriveTotal(events)
                    ?: return null
                Event(
                    type    = EventTypes.TASK_STARTED,
                    payload = mapOf("taskId" to taskId, "position" to position, "total" to total)
                )
            }

            // ExecutionAuthority writes TASK_EXECUTED; Governor waits for it.
            EventTypes.TASK_STARTED -> null

            EventTypes.TASK_EXECUTED -> {
                val taskId      = last.payload["taskId"]          as? String ?: return null
                val position    = resolveInt(last.payload["position"])       ?: return null
                val total       = resolveInt(last.payload["total"])          ?: return null
                val execStatus  = last.payload["executionStatus"] as? String ?: return null
                val validStatus = last.payload["validationStatus"] as? String ?: return null
                if (execStatus == "SUCCESS" && validStatus == "VALIDATED") {
                    Event(
                        type    = EventTypes.TASK_COMPLETED,
                        payload = mapOf("taskId" to taskId, "position" to position, "total" to total)
                    )
                } else {
                    Event(
                        type    = EventTypes.TASK_FAILED,
                        payload = mapOf("taskId" to taskId, "position" to position, "total" to total)
                    )
                }
            }

            EventTypes.TASK_COMPLETED -> {
                val position = resolveInt(last.payload["position"]) ?: return null
                val total    = resolveInt(last.payload["total"])    ?: return null
                Event(
                    type    = EventTypes.CONTRACT_COMPLETED,
                    payload = mapOf("position" to position, "total" to total)
                )
            }

            // No auto-retry: task failure requires external escalation.
            EventTypes.TASK_FAILED -> null

            EventTypes.CONTRACTOR_REASSIGNED -> {
                val taskId          = last.payload["taskId"]          as? String ?: return null
                val newContractorId = last.payload["newContractorId"] as? String ?: return null
                Event(
                    type    = EventTypes.TASK_ASSIGNED,
                    payload = mapOf("taskId" to taskId)
                )
            }

            EventTypes.CONTRACT_COMPLETED -> {
                val position = resolveInt(last.payload["position"]) ?: return null
                val total    = deriveTotal(events)                   ?: return null
                if (position < total) {
                    val next = position + 1
                    canIssue(next) ?: return null
                    Event(
                        type    = EventTypes.CONTRACT_STARTED,
                        payload = mapOf(
                            "position"    to next,
                            "total"       to total,
                            "contract_id" to "contract_$next"
                        )
                    )
                } else {
                    Event(type = EventTypes.EXECUTION_COMPLETED, payload = mapOf("total" to total))
                }
            }

            // Terminal: EXECUTION_COMPLETED is handled in runGovernor; null here ensures
            // nextEvent stays pure and side-effect-free.
            EventTypes.EXECUTION_COMPLETED -> null

            else -> null
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * ContractSurfaceLayer (CSL) gate.
     *
     * Returns [Unit] when [position] is within the Velocity Ceiling ([VC]),
     * or null to signal that issuance must be blocked (DRIFT).
     *
     * EL (Execution Load) = [CONTRACT_BASE_LOAD] + position.
     */
    private fun canIssue(position: Int): Unit? {
        val el = CONTRACT_BASE_LOAD + position
        return if (el <= VC) Unit else null
    }

    /** Gson deserialises all numbers as Double; this helper normalises to Int. */
    private fun resolveInt(value: Any?): Int? = when (value) {
        is Int    -> value
        is Double -> value.toInt()
        is Long   -> value.toInt()
        is String -> value.toIntOrNull()
        else      -> null
    }

    /**
     * Derives the report reference (RRID) from the first [EventTypes.CONTRACTS_GENERATED]
     * event in the ledger for RRIL-1 propagation into TASK_ASSIGNED payload.
     *
     * Reads "report_id" (written by ExecutionEntryPoint) with "report_reference" as fallback.
     * Returns empty string when no CONTRACTS_GENERATED event or field is found.
     */
    private fun deriveReportReference(events: List<Event>): String {
        val contractsGen = events.firstOrNull { it.type == EventTypes.CONTRACTS_GENERATED }
            ?: return ""
        return contractsGen.payload["report_id"]?.toString()
            ?: contractsGen.payload["report_reference"]?.toString()
            ?: ""
    }

    /**
     * Derives the total contract count from the first [EventTypes.CONTRACTS_GENERATED]
     * event in the ledger.
     *
     * Supports two payload formats:
     *  1. `"contracts"` list — written by [com.agoii.mobile.execution.ExecutionAuthority]
     *     via ContractSystemOrchestrator.
     *  2. `"total"` numeric key — used in test fixtures and legacy ledgers.
     *
     * Returns null if no CONTRACTS_GENERATED event exists or total cannot be derived.
     */
    private fun deriveTotal(events: List<Event>): Int? {
        val contractsGen = events.firstOrNull { it.type == EventTypes.CONTRACTS_GENERATED }
            ?: return null
        val contractsList = contractsGen.payload["contracts"] as? List<*>
        if (!contractsList.isNullOrEmpty()) return contractsList.size
        return contractsGen.payload["total"]?.let { resolveInt(it) }
    }
}
