package com.agoii.mobile.core

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
 * Contract execution lifecycle (one governor call per step):
 *   execution_started
 *     → contract_started  (contract 1)
 *     → contract_completed (contract 1)
 *     → contract_started  (contract 2)
 *     → contract_completed (contract 2)
 *     …
 *     → assembly_completed
 */
class Governor(private val eventStore: EventRepository) {

    companion object {
        /**
         * Defines every legal automatic single-step transition driven by the governor.
         * User-driven transitions (intent_submitted, contracts_approved) are NOT listed here
         * because they are triggered by UI actions, not the governor.
         *
         * Contract lifecycle steps (contract_started ↔ contract_completed) are handled
         * separately because they repeat N times and depend on ledger-derived state.
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
        /** Governor is paused; waiting for explicit user approval. */
        WAITING_FOR_APPROVAL,
        /** Execution is complete (assembly_completed reached). */
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
            // ── Approval gate — MUST stop; do not append anything ─────────
            lastType == EventTypes.CONTRACTS_READY    -> GovernorResult.WAITING_FOR_APPROVAL

            // ── Terminal state ─────────────────────────────────────────────
            lastType == EventTypes.ASSEMBLY_COMPLETED -> GovernorResult.COMPLETED

            // ── Start first contract after execution_started ───────────────
            lastType == EventTypes.EXECUTION_STARTED -> {
                eventStore.appendEvent(
                    projectId, EventTypes.CONTRACT_STARTED,
                    mapOf("contract_index" to 0, "contract_id" to "contract_1")
                )
                GovernorResult.ADVANCED
            }

            // ── Complete the currently open contract ───────────────────────
            lastType == EventTypes.CONTRACT_STARTED -> {
                eventStore.appendEvent(
                    projectId, EventTypes.CONTRACT_COMPLETED,
                    mapOf(
                        "contract_index" to (lastEvent.payload["contract_index"] ?: 0),
                        "contract_id"    to (lastEvent.payload["contract_id"]    ?: "unknown")
                    )
                )
                GovernorResult.ADVANCED
            }

            // ── After a contract completes: start next or assemble ─────────
            lastType == EventTypes.CONTRACT_COMPLETED -> {
                val state = Replay(eventStore).replay(projectId)
                if (state.contractsCompleted < state.totalContracts) {
                    val idx = state.contractsCompleted          // next contract (0-based)
                    eventStore.appendEvent(
                        projectId, EventTypes.CONTRACT_STARTED,
                        mapOf(
                            "contract_index" to idx,
                            "contract_id"    to "contract_${idx + 1}"
                        )
                    )
                    GovernorResult.ADVANCED
                } else {
                    eventStore.appendEvent(
                        projectId, EventTypes.ASSEMBLY_COMPLETED,
                        mapOf("contracts_completed" to state.totalContracts)
                    )
                    GovernorResult.COMPLETED
                }
            }

            // ── Standard single-step governor transition ───────────────────
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
}
