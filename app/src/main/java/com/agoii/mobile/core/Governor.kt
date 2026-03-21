package com.agoii.mobile.core

/**
 * Governor — the ONLY execution authority in the system.
 *
 * Rules:
 *  - Reads the current ledger state.
 *  - Determines the next valid event using VALID_TRANSITIONS.
 *  - Appends exactly ONE event per invocation (or zero if waiting/completed).
 *  - Never mutates state directly.
 *  - Stops and returns WAITING_FOR_APPROVAL when phase == contracts_ready.
 */
class Governor(private val eventStore: EventRepository) {

    companion object {
        /**
         * Defines every legal automatic transition.
         * User-driven transitions (intent_submitted, contracts_approved) are NOT listed here
         * because they are triggered by UI actions, not by the governor.
         *
         * contract_executed is handled separately because it repeats N times.
         */
        val VALID_TRANSITIONS: Map<String, String> = mapOf(
            EventTypes.INTENT_SUBMITTED    to EventTypes.CONTRACTS_GENERATED,
            EventTypes.CONTRACTS_GENERATED to EventTypes.CONTRACTS_READY,
            EventTypes.CONTRACTS_APPROVED  to EventTypes.EXECUTION_STARTED
        )
    }

    enum class GovernorResult {
        /** Governor appended one event and advanced the ledger. */
        ADVANCED,
        /** Governor is paused waiting for user approval. */
        WAITING_FOR_APPROVAL,
        /** Execution is complete (assembly_completed reached). */
        COMPLETED,
        /** Ledger is empty or in an unknown terminal state. */
        NO_EVENT
    }

    fun runGovernor(projectId: String): GovernorResult {
        val events = eventStore.loadEvents(projectId)
        if (events.isEmpty()) return GovernorResult.NO_EVENT

        val lastType = events.last().type

        return when {
            lastType == EventTypes.CONTRACTS_READY    -> GovernorResult.WAITING_FOR_APPROVAL
            lastType == EventTypes.ASSEMBLY_COMPLETED -> GovernorResult.COMPLETED

            // execution_started or contract_executed → execute the next contract or assemble
            lastType == EventTypes.EXECUTION_STARTED || lastType == EventTypes.CONTRACT_EXECUTED -> {
                val state = Replay(eventStore).replay(projectId)
                if (state.contractsCompleted < state.totalContracts) {
                    val idx = state.contractsCompleted
                    eventStore.appendEvent(
                        projectId, EventTypes.CONTRACT_EXECUTED,
                        mapOf(
                            "contract_index" to idx,
                            "contract_id"    to "contract_${idx + 1}"
                        )
                    )
                    GovernorResult.ADVANCED
                } else {
                    eventStore.appendEvent(
                        projectId, EventTypes.ASSEMBLY_COMPLETED,
                        mapOf("contracts_executed" to state.totalContracts)
                    )
                    GovernorResult.COMPLETED
                }
            }

            // Standard single-step transition
            VALID_TRANSITIONS.containsKey(lastType) -> {
                val nextType = VALID_TRANSITIONS[lastType]!!
                val payload = buildPayload(nextType, events.last())
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
                    "total" to EventTypes.DEFAULT_TOTAL_CONTRACTS,
                    "source_intent" to intent,
                    "contracts" to listOf(
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
}
