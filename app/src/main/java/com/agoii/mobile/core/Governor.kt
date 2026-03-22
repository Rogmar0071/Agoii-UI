package com.agoii.mobile.core

import com.agoii.mobile.governance.ContractDescriptor
import com.agoii.mobile.governance.ContractSurfaceLayer
import com.agoii.mobile.governance.Outcome
import com.agoii.mobile.governance.SurfaceType

/**
 * Governor — the ONLY execution authority in the system.
 *
 * Rules:
 *  - Reads the current ledger state.
 *  - Determines the next valid event using VALID_TRANSITIONS.
 *  - Appends EXACTLY ONE event per invocation (or zero if waiting/completed).
 *  - Never mutates state directly.
 *  - Stops and returns WAITING_FOR_APPROVAL when the last event is contracts_ready.
 *
 * Full execution lifecycle (one governor call per arrow):
 *   execution_started
 *     → contract_started  (position=1, total=N)
 *     → contract_completed (position=1, total=N)
 *     → contract_started  (position=2, total=N)
 *     → contract_completed (position=2, total=N)
 *     …
 *     → contract_completed (position=N, total=N)
 *     → execution_completed
 *     → assembly_started
 *     → assembly_validated
 *     → assembly_completed
 */
class Governor(private val eventStore: EventRepository) {

    private val csl = ContractSurfaceLayer()

    companion object {
        /**
         * Defines every legal automatic single-step transition driven by the governor.
         * User-driven transitions (intent_submitted, contracts_approved) are NOT listed here
         * because they are triggered by UI actions, not the governor.
         *
         * Contract lifecycle steps (contract_started ↔ contract_completed) repeat N times
         * and depend on ledger-derived state; they are handled in dedicated branches below.
         * The full assembly pipeline is driven entirely by this map after all contracts complete.
         */
        val VALID_TRANSITIONS: Map<String, String> = mapOf(
            EventTypes.INTENT_SUBMITTED    to EventTypes.CONTRACTS_GENERATED,
            EventTypes.CONTRACTS_GENERATED to EventTypes.CONTRACTS_READY,
            EventTypes.CONTRACTS_APPROVED  to EventTypes.EXECUTION_STARTED,
            EventTypes.EXECUTION_COMPLETED to EventTypes.ASSEMBLY_STARTED,
            EventTypes.ASSEMBLY_STARTED    to EventTypes.ASSEMBLY_VALIDATED,
            EventTypes.ASSEMBLY_VALIDATED  to EventTypes.ASSEMBLY_COMPLETED
        )
    }

    enum class GovernorResult {
        /** Governor appended one event and advanced the ledger. */
        ADVANCED,
        /** CSL evaluation rejected the contract; issuance was blocked. */
        CSL_REJECTED,
        /** Governor is paused; waiting for explicit user approval. */
        WAITING_FOR_APPROVAL,
        /** Execution is fully complete (assembly_completed reached). */
        COMPLETED,
        /** Ledger is empty or in an unknown terminal state. */
        NO_EVENT
    }

    fun runGovernor(projectId: String): GovernorResult {
        val events = eventStore.loadEvents(projectId)
        if (events.isEmpty()) return GovernorResult.NO_EVENT

        val lastEvent = events.last()
        val lastType  = lastEvent.type

        return when {
            // ── Approval gate — MUST stop; do not append anything ─────────────────
            lastType == EventTypes.CONTRACTS_READY    -> GovernorResult.WAITING_FOR_APPROVAL

            // ── Terminal state ────────────────────────────────────────────────────
            lastType == EventTypes.ASSEMBLY_COMPLETED -> GovernorResult.COMPLETED

            // ── execution_started → start the first contract ──────────────────────
            // Reads total from replay so the value is ledger-derived, not inferred.
            lastType == EventTypes.EXECUTION_STARTED -> {
                val total = Replay(eventStore).replay(projectId).totalContracts
                if (cslRejected(position = 1, total = total)) return GovernorResult.CSL_REJECTED
                eventStore.appendEvent(
                    projectId, EventTypes.CONTRACT_STARTED,
                    mapOf(
                        "contract_id" to "contract_1",
                        "position"    to 1,
                        "total"       to total
                    )
                )
                GovernorResult.ADVANCED
            }

            // ── contract_started → complete the same contract ─────────────────────
            // Reads position/total from the last event payload — no inference needed.
            lastType == EventTypes.CONTRACT_STARTED -> {
                val position = resolveInt(lastEvent.payload["position"]) ?: 1
                val total    = resolveInt(lastEvent.payload["total"])    ?: EventTypes.DEFAULT_TOTAL_CONTRACTS
                eventStore.appendEvent(
                    projectId, EventTypes.CONTRACT_COMPLETED,
                    mapOf(
                        "contract_id" to (lastEvent.payload["contract_id"] ?: "unknown"),
                        "position"    to position,
                        "total"       to total
                    )
                )
                GovernorResult.ADVANCED
            }

            // ── contract_completed → start next contract OR emit execution_completed
            // Reads position/total from the last event payload — fully explicit, no replay.
            lastType == EventTypes.CONTRACT_COMPLETED -> {
                val position = resolveInt(lastEvent.payload["position"]) ?: 1
                val total    = resolveInt(lastEvent.payload["total"])    ?: EventTypes.DEFAULT_TOTAL_CONTRACTS
                if (position < total) {
                    val nextPosition = position + 1
                    if (cslRejected(position = nextPosition, total = total)) return GovernorResult.CSL_REJECTED
                    eventStore.appendEvent(
                        projectId, EventTypes.CONTRACT_STARTED,
                        mapOf(
                            "contract_id" to "contract_$nextPosition",
                            "position"    to nextPosition,
                            "total"       to total
                        )
                    )
                } else {
                    eventStore.appendEvent(
                        projectId, EventTypes.EXECUTION_COMPLETED,
                        mapOf("contracts_completed" to total)
                    )
                }
                GovernorResult.ADVANCED
            }

            // ── Standard single-step governor transition (covers assembly pipeline) ─
            VALID_TRANSITIONS.containsKey(lastType) -> {
                val nextType = VALID_TRANSITIONS[lastType]!!
                val payload  = buildPayload(nextType, lastEvent)
                eventStore.appendEvent(projectId, nextType, payload)
                GovernorResult.ADVANCED
            }

            else -> GovernorResult.NO_EVENT
        }
    }

    private fun buildPayload(nextType: String, triggerEvent: Event): Map<String, Any> =
        when (nextType) {
            EventTypes.CONTRACTS_GENERATED -> {
                val intent = triggerEvent.payload["objective"] as? String ?: "unknown"
                mapOf(
                    "total"         to EventTypes.DEFAULT_TOTAL_CONTRACTS,
                    "source_intent" to intent,
                    "contracts"     to listOf(
                        mapOf("id" to "contract_1", "name" to "Core Setup"),
                        mapOf("id" to "contract_2", "name" to "Integration"),
                        mapOf("id" to "contract_3", "name" to "Validation")
                    )
                )
            }
            EventTypes.CONTRACTS_READY   -> mapOf("total_contracts" to EventTypes.DEFAULT_TOTAL_CONTRACTS)
            EventTypes.EXECUTION_STARTED -> mapOf("total_contracts" to EventTypes.DEFAULT_TOTAL_CONTRACTS)
            else                         -> emptyMap()
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
     * Returns `true` if CSL rejects the contract at the given [position] within
     * a run of [total] contracts.  Uses surface LG with no conditional branches;
     * validation capacity is set to twice the total so all valid sequences pass.
     */
    private fun cslRejected(position: Int, total: Int): Boolean =
        csl.evaluate(
            ContractDescriptor(
                surface = SurfaceType.LG,
                executionCount = position,
                conditionCount = 0,
                validationCapacity = total * 2
            )
        ).outcome == Outcome.REJECTED
}
