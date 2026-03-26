package com.agoii.mobile.governor

import com.agoii.mobile.contracts.AgentProfile
import com.agoii.mobile.contracts.ContractIntent
import com.agoii.mobile.contracts.ContractSystemOrchestrator
import com.agoii.mobile.contractor.ContractorRegistry
import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventRepository
import com.agoii.mobile.core.EventTypes

/**
 * Governor — deterministic state machine and execution driver.
 *
 * [nextEvent] is pure: no I/O, no side effects. Same input always produces same output.
 * [runGovernor] is the stateful driver: reads from [store], advances one step via [nextEvent],
 * applies the CSL gate, writes the result back, and returns a [GovernorResult].
 *
 * Contract derivation (V4):
 *  - [nextEvent] derives contracts via [ContractSystemOrchestrator] for INTENT_SUBMITTED.
 *  - No contracts are hardcoded anywhere in this class.
 *  - Mapping: ExecutionPlan.steps → [{id, name, position}] per the architecture contract.
 *
 * CSL gate (in [runGovernor]):
 *  - Blocks CONTRACT_STARTED when CONTRACT_BASE_LOAD + position > [VC].
 *  - Returns [GovernorResult.DRIFT] on block; ledger is NOT modified.
 *
 * Execution authority:
 *  - All writes flow through [store] (which must be [com.agoii.mobile.core.EventLedger]).
 *  - ValidationLayer + EventLedger together constitute the execution authority.
 *
 * Lifecycle (each arrow = one [runGovernor] call; [wait] = null/external; [terminal] = COMPLETED):
 *   intent_submitted → contracts_generated → contracts_ready [wait — contracts_approved external]
 *     → execution_started → contract_started(1)
 *     → task_assigned → task_started → task_completed → task_validated
 *     → contract_completed → contract_started(2) → … → contract_started(N)
 *     → contract_completed(N) → execution_completed
 *     → assembly_started → assembly_validated → assembly_completed [terminal]
 */
class Governor(
    private val store:    EventRepository,
    private val registry: ContractorRegistry? = null
) {

    private val contractSystem = ContractSystemOrchestrator()

    /** Outcome of a single [runGovernor] invocation. */
    enum class GovernorResult {
        /** Ledger was empty; no transition possible. */
        NO_EVENT,
        /** One event was successfully derived and written. */
        ADVANCED,
        /** Ledger is at CONTRACTS_READY — waiting for explicit external approval. */
        WAITING_FOR_APPROVAL,
        /** ASSEMBLY_COMPLETED reached — system is terminal. */
        COMPLETED,
        /** CSL blocked a CONTRACT_STARTED issuance (EL > VC). */
        DRIFT
    }

    companion object {

        /** Validation Capacity — maximum execution load (EL) allowed per contract issuance. */
        const val VC = 5

        /** Base load applied to every contract issuance in the CSL calculation. */
        private const val CONTRACT_BASE_LOAD = 2

        /** Deterministic contractor identifier used when no registry is present. */
        const val DEFAULT_CONTRACTOR = "default-contractor"

        /**
         * Frozen single-step transitions owned exclusively by the Governor.
         * Exactly 5 entries — non-overridable.
         */
        val VALID_TRANSITIONS: Map<String, String> = mapOf(
            EventTypes.INTENT_SUBMITTED    to EventTypes.CONTRACTS_GENERATED,
            EventTypes.CONTRACTS_GENERATED to EventTypes.CONTRACTS_READY,
            EventTypes.CONTRACTS_APPROVED  to EventTypes.EXECUTION_STARTED,
            EventTypes.EXECUTION_COMPLETED to EventTypes.ASSEMBLY_STARTED,
            EventTypes.ASSEMBLY_VALIDATED  to EventTypes.ASSEMBLY_COMPLETED
        )

        /** Standard capability profile used for deterministic contract derivation. */
        private val DEFAULT_AGENT_PROFILE = AgentProfile(
            agentId             = "default-agent",
            constraintObedience = 3,
            structuralAccuracy  = 3,
            driftTendency       = 0,
            complexityHandling  = 3,
            outputReliability   = 3
        )
    }

    // ─── Stateful driver ───────────────────────────────────────────────────────

    /**
     * Advance the ledger by exactly one step.
     *
     * Reads the current ledger state, delegates transition logic to [nextEvent], applies
     * the CSL gate for contract issuance, and writes the result to [store].
     *
     * @param projectId The project whose ledger to advance.
     * @return [GovernorResult] describing the outcome without exposing internal state.
     */
    fun runGovernor(projectId: String): GovernorResult {
        val events = store.loadEvents(projectId)
        if (events.isEmpty()) return GovernorResult.NO_EVENT

        val last = events.last()

        if (last.type == EventTypes.ASSEMBLY_COMPLETED) return GovernorResult.COMPLETED
        if (last.type == EventTypes.CONTRACTS_READY)    return GovernorResult.WAITING_FOR_APPROVAL

        val next = nextEvent(events) ?: return GovernorResult.NO_EVENT

        // CSL gate: block contract issuance when execution load exceeds capacity.
        if (next.type == EventTypes.CONTRACT_STARTED) {
            val position = resolveInt(next.payload["position"]) ?: 0
            if (CONTRACT_BASE_LOAD + position > VC) return GovernorResult.DRIFT
        }

        store.appendEvent(projectId, next.type, next.payload)
        return GovernorResult.ADVANCED
    }

    // ─── Pure state machine ────────────────────────────────────────────────────

    /**
     * Given the full ordered ledger [events], returns the next [Event] to append,
     * or null for terminal state, wait states, or unrecognised type (drift).
     *
     * Pure: no I/O, no side effects. Same input always produces same output.
     * The caller ([runGovernor]) is responsible for persisting the returned event.
     *
     * Contract derivation (INTENT_SUBMITTED → CONTRACTS_GENERATED) is performed here
     * via [ContractSystemOrchestrator] — no hardcoded contracts (V4 compliance).
     */
    fun nextEvent(events: List<Event>): Event? {
        if (events.isEmpty()) return null

        val last = events.last()

        return when (last.type) {

            // ── Pre-execution pipeline ────────────────────────────────────────────

            // V4: contracts derived deterministically — NOT hardcoded.
            EventTypes.INTENT_SUBMITTED -> {
                val objective = last.payload["objective"] as? String ?: return null
                val contracts = deriveContracts(objective) ?: return null
                Event(
                    type = EventTypes.CONTRACTS_GENERATED,
                    payload = mapOf(
                        "source_intent" to objective,
                        "contracts"     to contracts,
                        "total"         to contracts.size
                    )
                )
            }

            EventTypes.CONTRACTS_GENERATED ->
                Event(type = EventTypes.CONTRACTS_READY, payload = emptyMap())

            // Approval gate — external governance action required; no Governor transition.
            EventTypes.CONTRACTS_READY -> null

            EventTypes.CONTRACTS_APPROVED ->
                Event(type = EventTypes.EXECUTION_STARTED, payload = emptyMap())

            // ── Execution spine ───────────────────────────────────────────────────

            EventTypes.EXECUTION_STARTED -> {
                val total = deriveTotal(events) ?: return null
                Event(
                    type    = EventTypes.CONTRACT_STARTED,
                    payload = mapOf("position" to 1, "total" to total, "contract_id" to "contract_1")
                )
            }

            EventTypes.CONTRACT_STARTED -> {
                val contractId = last.payload["contract_id"] as? String ?: return null
                val position   = resolveInt(last.payload["position"]) ?: return null
                val total      = resolveInt(last.payload["total"]) ?: deriveTotal(events) ?: return null
                Event(
                    type    = EventTypes.TASK_ASSIGNED,
                    payload = mapOf(
                        "taskId"       to "$contractId-step1",
                        "contractorId" to DEFAULT_CONTRACTOR,
                        "position"     to position,
                        "total"        to total
                    )
                )
            }

            EventTypes.TASK_ASSIGNED -> {
                val taskId = last.payload["taskId"] as? String ?: return null
                Event(type = EventTypes.TASK_STARTED, payload = mapOf("taskId" to taskId))
            }

            // Governor owns the full task lifecycle — auto-advance to TASK_COMPLETED.
            EventTypes.TASK_STARTED -> {
                val taskId = last.payload["taskId"] as? String ?: return null
                Event(type = EventTypes.TASK_COMPLETED, payload = mapOf("taskId" to taskId))
            }

            EventTypes.TASK_COMPLETED -> {
                val taskId = last.payload["taskId"] as? String ?: return null
                Event(type = EventTypes.TASK_VALIDATED, payload = mapOf("taskId" to taskId))
            }

            EventTypes.TASK_VALIDATED -> {
                val position = events.lastOrNull { it.type == EventTypes.CONTRACT_STARTED }
                    ?.payload?.let { resolveInt(it["position"]) } ?: return null
                val total = deriveTotal(events) ?: return null
                Event(
                    type    = EventTypes.CONTRACT_COMPLETED,
                    payload = mapOf("position" to position, "total" to total)
                )
            }

            // No auto-retry — external intervention required.
            EventTypes.TASK_FAILED -> null

            EventTypes.CONTRACTOR_REASSIGNED -> {
                val taskId          = last.payload["taskId"]          as? String ?: return null
                val newContractorId = last.payload["newContractorId"] as? String ?: return null
                Event(
                    type    = EventTypes.TASK_ASSIGNED,
                    payload = mapOf("taskId" to taskId, "contractorId" to newContractorId)
                )
            }

            EventTypes.CONTRACT_COMPLETED -> {
                val position = resolveInt(last.payload["position"]) ?: return null
                val total    = deriveTotal(events) ?: return null
                if (position < total) {
                    val next = position + 1
                    Event(
                        type    = EventTypes.CONTRACT_STARTED,
                        payload = mapOf(
                            "position"    to next,
                            "total"       to total,
                            "contract_id" to "contract_$next"
                        )
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

    // ─── Contract derivation (V4) ─────────────────────────────────────────────

    /**
     * Derives the contract list for [objective] via [ContractSystemOrchestrator].
     *
     * Mapping (per architecture contract):
     *   ExecutionPlan.steps → [{id: "contract_{position}", name: step.description, position: step.position}]
     *
     * @return Derived contract list, or null when derivation fails or is rejected.
     */
    private fun deriveContracts(objective: String): List<Map<String, Any>>? {
        return try {
            val intent = ContractIntent(
                objective   = objective,
                constraints = "standard",
                environment = "mobile",
                resources   = "available"
            )
            val result = contractSystem.evaluate(intent, DEFAULT_AGENT_PROFILE)
            if (!result.readyForExecution) return null
            val plan = result.adaptedContract?.adaptedPlan
                ?: result.scoredContract?.derivation?.executionPlan
                ?: return null
            plan.steps.map { step ->
                mapOf<String, Any>(
                    "id"       to "contract_${step.position}",
                    "name"     to step.description,
                    "position" to step.position
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Derives the total contract count from the first [EventTypes.CONTRACTS_GENERATED] event.
     *
     * Reads the "total" field first (backward compatibility with test data that omits the
     * "contracts" list), then falls back to the size of the "contracts" list.
     */
    private fun deriveTotal(events: List<Event>): Int? {
        val contractsGen = events.firstOrNull { it.type == EventTypes.CONTRACTS_GENERATED }
            ?: return null
        // Prefer the explicit "total" field (present in both legacy and new payloads).
        val totalRaw = contractsGen.payload["total"]
        if (totalRaw != null) {
            val n = resolveInt(totalRaw)
            if (n != null && n > 0) return n
        }
        // Fall back to the derived "contracts" list size.
        val contracts = contractsGen.payload["contracts"] as? List<*> ?: return null
        return contracts.size.takeIf { it > 0 }
    }

    /** Gson deserialises all numbers as Double; normalises to Int. */
    private fun resolveInt(value: Any?): Int? = when (value) {
        is Int    -> value
        is Double -> value.toInt()
        is Long   -> value.toInt()
        is String -> value.toIntOrNull()
        else      -> null
    }
}
