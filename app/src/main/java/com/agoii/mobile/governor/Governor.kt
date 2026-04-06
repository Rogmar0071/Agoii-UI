package com.agoii.mobile.governor

import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventRepository
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.core.ReplayStructuralState
import java.util.UUID

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
    private val store: EventRepository
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
     * Advance the ledger by one evaluation step and return the outcome.
     *
     * AGOII-REPLAY-STATE-001: reads authoritative [ReplayStructuralState] from [store]
     * via [EventRepository.replayState]; raw event lists are NOT used for decision-making.
     *
     * Computes ALL next events via [nextEvents], appends each one to [store] in order,
     * and returns [GovernorResult.ADVANCED].  Returns a non-ADVANCED result for known
     * wait states, the terminal state, or when no valid transition exists.
     */
    fun runGovernor(projectId: String): GovernorResult {
        val state = store.replayState(projectId)
        val gv = state.governanceView
        if (gv.lastEventType == null) return GovernorResult.NO_EVENT

        return when (gv.lastEventType) {
            // CONTRACTS_GENERATED is authored by ExecutionAuthority — not by Governor.
            // Governor reads from the ledger only; if it has not yet been written, wait.
            EventTypes.INTENT_SUBMITTED    -> GovernorResult.NO_EVENT

            // Terminal: EXECUTION_COMPLETED closes the lifecycle — no further events.
            EventTypes.EXECUTION_COMPLETED -> GovernorResult.COMPLETED

            else -> {
                val nextList = nextEvents(state)
                if (nextList.isNotEmpty()) {
                    nextList.forEach { next ->
                        store.appendEvent(projectId, next.type, next.payload)
                    }
                    GovernorResult.ADVANCED
                } else {
                    GovernorResult.DRIFT
                }
            }
        }
    }

    /**
     * Pure projection: given the full ordered ledger [events], returns the next
     * [Event] to append, or null when no Governor-owned transition exists.
     *
     * For transitions that produce multiple events in a single step (e.g.
     * ASSEMBLY_FAILED → all RECOVERY_CONTRACTs), this returns only the first.
     * Use [nextEvents] when all events must be obtained.
     *
     * This function has no side effects.
     *
     * @deprecated Prefer [nextEvents] with a [ReplayStructuralState] obtained from
     * [EventRepository.replayState].  This overload is retained for backward
     * compatibility with existing tests.
     */
    fun nextEvent(events: List<Event>): Event? = nextEvents(events).firstOrNull()

    /**
     * Pure projection: given the full ordered ledger [events], returns ALL events
     * to append in this evaluation step, or an empty list when no Governor-owned
     * transition exists.
     *
     * For most transitions this returns a single event. For ASSEMBLY_FAILED the
     * Governor no longer emits RECOVERY_CONTRACT — recovery is exclusively owned
     * by [com.agoii.mobile.execution.ExecutionAuthority] (AGOII-ALIGN-1 RULE 4).
     *
     * This function has no side effects. The caller is responsible for persisting
     * all returned events via [com.agoii.mobile.core.EventRepository].
     *
     * AGOII-REPLAY-STATE-001: This overload derives a [ReplayStructuralState] from
     * [events] and delegates to [nextEvents(ReplayStructuralState)].  The state-based
     * overload is the canonical implementation; this overload exists solely to
     * maintain backward compatibility with tests that pass raw event lists.
     */
    fun nextEvents(events: List<Event>): List<Event> {
        if (events.isEmpty()) return emptyList()
        // Build state from the given event list using an ephemeral repository so that
        // the state-based transition logic operates on the supplied snapshot rather
        // than the live ledger.  The projectId is irrelevant because the ephemeral
        // repository ignores it.
        val ephemeral = object : EventRepository {
            override fun appendEvent(projectId: String, type: String, payload: Map<String, Any>) = Unit
            override fun loadEvents(projectId: String): List<Event> = events
        }
        return nextEvents(ephemeral.replayState(""))
    }

    /**
     * Pure projection: given an authoritative [ReplayStructuralState], returns ALL
     * events to append in this evaluation step, or an empty list when no Governor-owned
     * transition exists.
     *
     * AGOII-REPLAY-STATE-001: This is the canonical entry point for Governor transition
     * logic.  Decision-making is based exclusively on pre-computed state; no raw event
     * list is consumed.
     *
     * This function has no side effects.
     */
    fun nextEvents(state: ReplayStructuralState): List<Event> {
        if (state.governanceView.lastEventType == null) return emptyList()
        return listOfNotNull(nextEventSingle(state))
    }

    /**
     * Internal single-event projection operating exclusively on [ReplayStructuralState].
     *
     * AGOII-REPLAY-STATE-001: all decision-making reads state fields; no raw event list
     * is consumed inside this function.  Returns null when no transition is applicable.
     */
    private fun nextEventSingle(state: ReplayStructuralState): Event? {
        val gv          = state.governanceView
        val lastType    = gv.lastEventType ?: return null
        val lastPayload = gv.lastEventPayload

        return when (lastType) {

            // User approves contracts → Governor advances to EXECUTION_STARTED
            EventTypes.CONTRACTS_APPROVED -> {
                Event(type = EventTypes.EXECUTION_STARTED, payload = emptyMap())
            }

            // EXECUTION_STARTED → begin first contract
            EventTypes.EXECUTION_STARTED -> {
                val total = gv.totalContracts.takeIf { it > 0 } ?: return null
                canIssue(1) ?: return null
                Event(
                    type    = EventTypes.CONTRACT_STARTED,
                    payload = mapOf("position" to 1, "total" to total, "contract_id" to "contract_1")
                )
            }

            // CONTRACTS_GENERATED is the Execution Authority's responsibility.
            // Governor does not derive contracts; it waits.
            EventTypes.INTENT_SUBMITTED -> null

            // ── Simple passthrough transitions (empty payload) ────────────────────

            in VALID_TRANSITIONS.keys -> {
                val target = VALID_TRANSITIONS.getValue(lastType)
                Event(type = target, payload = emptyMap())
            }

            // ── Execution spine ───────────────────────────────────────────────────

            EventTypes.CONTRACTS_READY -> {
                val total = gv.totalContracts.takeIf { it > 0 } ?: return null
                canIssue(1) ?: return null
                Event(
                    type    = EventTypes.CONTRACT_STARTED,
                    payload = mapOf("position" to 1, "total" to total, "contract_id" to "contract_1")
                )
            }

            EventTypes.CONTRACT_STARTED -> {
                val contractId = lastPayload["contract_id"] as? String ?: return null
                val position   = resolveInt(lastPayload["position"])    ?: return null
                val total      = gv.totalContracts.takeIf { it > 0 }   ?: return null
                Event(
                    type    = EventTypes.TASK_ASSIGNED,
                    payload = mapOf(
                        "taskId"           to "$contractId-step1",
                        "contractId"       to contractId,
                        "position"         to position,
                        "total"            to total,
                        "report_reference" to gv.reportReference,
                        "requirements"     to emptyList<Any>(),
                        "constraints"      to emptyList<Any>()
                    )
                )
            }

            EventTypes.TASK_ASSIGNED -> {
                val taskId     = lastPayload["taskId"] as? String ?: return null
                // contractId is read from the TASK_ASSIGNED payload (set by Governor when emitting
                // TASK_ASSIGNED from CONTRACT_STARTED / DELTA_CONTRACT_CREATED).
                // Falls back to GovernanceView.lastContractStartedId (sourced from the originating
                // CONTRACT_STARTED event via Replay) so the field is always deterministically present.
                val contractId = lastPayload["contractId"] as? String
                    ?: gv.lastContractStartedId.takeIf { it.isNotEmpty() }
                    ?: return null
                val position = resolveInt(lastPayload["position"])
                    ?: gv.lastContractStartedPosition
                    ?: return null
                val total = resolveInt(lastPayload["total"])
                    ?: gv.totalContracts.takeIf { it > 0 }
                    ?: return null
                Event(
                    type    = EventTypes.TASK_STARTED,
                    payload = mapOf(
                        "taskId"     to taskId,
                        "contractId" to contractId,
                        "position"   to position,
                        "total"      to total
                    )
                )
            }

            // ExecutionAuthority writes TASK_EXECUTED; Governor waits for it.
            EventTypes.TASK_STARTED -> null

            EventTypes.TASK_EXECUTED -> {
                val execStatus  = lastPayload["executionStatus"] as? String ?: return null
                val validStatus = lastPayload["validationStatus"] as? String ?: return null
                val contractId  = lastPayload["contractId"]?.toString() ?: return null
                val taskId      = lastPayload["taskId"] as? String ?: return null
                val position    = resolveInt(lastPayload["position"]) ?: return null
                val total       = resolveInt(lastPayload["total"]) ?: return null

                // MQP-FAILURE-CONTINUITY-RECOVERY-v1: FAILURE → RECOVERY_CONTRACT
                // Governor owns the recovery transition; TASK_EXECUTED(FAILURE) must never
                // be a terminal state. Routing through RECOVERY_CONTRACT keeps the ledger
                // valid and allows the next Send to proceed normally.
                //
                // recoveryId is a UUID because this event is written once to the append-only
                // ledger and subsequently read back — the Governor never re-generates it
                // during replay. The downstream idempotency guard (deltaContractRecoveryIds)
                // operates on already-persisted recoveryIds, so uniqueness is safe here.
                //
                // MQP-RECOVERY-CONVERGENCE-BOUND-v1: each RECOVERY_CONTRACT written to the
                // ledger adds its recoveryId to gv.deltaContractRecoveryIds. The size of
                // that set therefore equals the number of recovery attempts already made for
                // this project. When the bound is reached (≥ 3), emit EXECUTION_COMPLETED
                // instead so the system terminates cleanly rather than looping forever.
                if (execStatus == "FAILURE") {
                    val recoveryCount = gv.deltaContractRecoveryIds.size
                    if (recoveryCount >= 3) {
                        Event(
                            type    = EventTypes.EXECUTION_COMPLETED,
                            payload = mapOf("total" to gv.totalContracts)
                        )
                    } else {
                        Event(
                            type    = EventTypes.RECOVERY_CONTRACT,
                            payload = mapOf(
                                "contractId"       to contractId,
                                "taskId"           to taskId,
                                "recoveryId"       to UUID.randomUUID().toString(),
                                "report_reference" to gv.reportReference,
                                "source"           to "EXECUTION_FAILURE"
                            )
                        )
                    }
                } else if (execStatus == "SUCCESS" && validStatus == "VALIDATED") {
                    Event(
                        type    = EventTypes.TASK_COMPLETED,
                        payload = mapOf("taskId" to taskId, "position" to position, "total" to total)
                    )
                } else {
                    null
                }
            }

            EventTypes.TASK_COMPLETED -> {
                val position = resolveInt(lastPayload["position"]) ?: return null
                val total    = resolveInt(lastPayload["total"])    ?: return null

                Event(
                    type    = EventTypes.CONTRACT_COMPLETED,
                    payload = mapOf(
                        "position"         to position,
                        "total"            to total,
                        "contractId"       to gv.lastContractStartedId,
                        "report_reference" to gv.reportReference
                    )
                )
            }

            // No auto-retry: task failure requires external escalation.
            EventTypes.TASK_FAILED -> null

            // ASSEMBLY_FAILED: Governor no longer emits RECOVERY_CONTRACT (AGOII-ALIGN-1 RULE 4).
            // Recovery is owned exclusively by ExecutionAuthority.
            EventTypes.ASSEMBLY_FAILED -> null

            // AGOII-ALIGN-1 RULE 3 — ICS ENTRY LOCK:
            // Governor is the sole emitter of ICS_STARTED; source=GOVERNOR is mandatory.
            EventTypes.ASSEMBLY_COMPLETED -> {
                val reportRef = lastPayload["report_reference"]?.toString()
                    ?.takeIf { it.isNotBlank() } ?: return null
                val finalArtifactReference = lastPayload["finalArtifactReference"]?.toString()
                    ?.takeIf { it.isNotBlank() } ?: return null
                val taskId = "ICS::$reportRef"
                Event(
                    type    = EventTypes.ICS_STARTED,
                    payload = mapOf(
                        "report_reference"       to reportRef,
                        "finalArtifactReference" to finalArtifactReference,
                        "taskId"                 to taskId,
                        "source"                 to "GOVERNOR"
                    )
                )
            }

            // CLC-1 delta loop: RECOVERY_CONTRACT → DELTA_CONTRACT_CREATED
            // Reads recoveryId, contractId, taskId, report_reference from the state's
            // last-event payload; idempotency is checked against the pre-computed
            // deltaContractRecoveryIds set in GovernanceView.
            EventTypes.RECOVERY_CONTRACT -> {
                val recoveryId = lastPayload["recoveryId"]?.toString()?.takeIf { it.isNotBlank() }
                    ?: return null
                val contractId = lastPayload["contractId"]?.toString()?.takeIf { it.isNotBlank() }
                    ?: return null
                val taskId     = lastPayload["taskId"]?.toString()?.takeIf { it.isNotBlank() }
                    ?: return null
                val reportRef  = lastPayload["report_reference"]?.toString()?.takeIf { it.isNotBlank() }
                    ?: return null
                if (gv.deltaContractRecoveryIds.contains(recoveryId)) return null
                Event(
                    type    = EventTypes.DELTA_CONTRACT_CREATED,
                    payload = mapOf(
                        "recoveryId"       to recoveryId,
                        "contractId"       to contractId,
                        "taskId"           to taskId,
                        "report_reference" to reportRef,
                        "source"           to "GOVERNOR"
                    )
                )
            }

            // CLC-1 delta loop: DELTA_CONTRACT_CREATED → TASK_ASSIGNED
            // Reads fields from the state's last-event payload; idempotency is checked
            // against the pre-computed taskAssignedTaskIds set in GovernanceView.
            EventTypes.DELTA_CONTRACT_CREATED -> {
                // recoveryId required in payload (contract enforcement — mirrors ValidationLayer).
                val recoveryId  = lastPayload["recoveryId"]?.toString()?.takeIf { it.isNotBlank() }
                    ?: return null
                val contractId  = lastPayload["contractId"]?.toString()?.takeIf { it.isNotBlank() }
                    ?: return null
                val taskId      = lastPayload["taskId"]?.toString()?.takeIf { it.isNotBlank() }
                    ?: return null
                val reportRef   = lastPayload["report_reference"]?.toString()?.takeIf { it.isNotBlank() }
                    ?: return null
                if (gv.taskAssignedTaskIds.contains(taskId)) return null
                Event(
                    type    = EventTypes.TASK_ASSIGNED,
                    payload = mapOf(
                        "taskId"           to taskId,
                        "contractId"       to contractId,
                        "report_reference" to reportRef,
                        "position"         to 1,
                        "total"            to 1
                    )
                )
            }

            EventTypes.CONTRACTOR_REASSIGNED -> {
                val taskId          = lastPayload["taskId"]          as? String ?: return null
                val newContractorId = lastPayload["newContractorId"] as? String ?: return null
                Event(
                    type    = EventTypes.TASK_ASSIGNED,
                    payload = mapOf("taskId" to taskId)
                )
            }

            EventTypes.CONTRACT_COMPLETED -> {
                val position = resolveInt(lastPayload["position"]) ?: return null
                val total    = gv.totalContracts.takeIf { it > 0 } ?: return null
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

}
