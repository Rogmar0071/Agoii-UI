package com.agoii.mobile.core

/**
 * Derived state produced by replaying the full event ledger.
 * This is the ONLY source of truth for the UI — never computed from direct mutations.
 */
data class ReplayState(
    val phase: String,
    val contractsCompleted: Int,
    val totalContracts: Int,
    val executionStarted: Boolean,
    val executionCompleted: Boolean,
    val assemblyStarted: Boolean,
    val assemblyValidated: Boolean,
    val objective: String?
)

/**
 * Derives application state by replaying every event in the ledger from the beginning.
 *
 * Rules:
 *  - State is NEVER stored; it is always freshly derived from events.
 *  - The replay function must be deterministic: same events → same state.
 *  - No side effects are allowed inside this class.
 *
 * Contract progress:
 *  - [contractsCompleted] counts ONLY contract_completed events.
 *  - contract_started advances the phase but does NOT increment the counter.
 *  - execution_completed sets [executionCompleted]; assembly events advance their own flags.
 */
class Replay(private val eventStore: EventRepository) {

    fun replay(projectId: String): ReplayState {
        val events = eventStore.loadEvents(projectId)
        return deriveState(events)
    }

    /** Pure function: events → state. No I/O, no side effects. */
    fun deriveState(events: List<Event>): ReplayState {
        var phase = "idle"
        var contractsCompleted = 0
        var totalContracts = 0
        var executionStarted = false
        var executionCompleted = false
        var assemblyStarted = false
        var assemblyValidated = false
        var objective: String? = null

        for (event in events) {
            when (event.type) {
                EventTypes.INTENT_SUBMITTED -> {
                    phase = EventTypes.INTENT_SUBMITTED
                    objective = event.payload["objective"] as? String
                }
                EventTypes.CONTRACTS_GENERATED -> {
                    phase = EventTypes.CONTRACTS_GENERATED
                    totalContracts = resolveInt(event.payload["total"])
                        ?: EventTypes.DEFAULT_TOTAL_CONTRACTS
                }
                EventTypes.CONTRACTS_READY -> {
                    phase = EventTypes.CONTRACTS_READY
                }
                EventTypes.CONTRACTS_APPROVED -> {
                    phase = EventTypes.CONTRACTS_APPROVED
                }
                EventTypes.EXECUTION_STARTED -> {
                    phase = EventTypes.EXECUTION_STARTED
                    executionStarted = true
                    if (totalContracts == 0) {
                        totalContracts =
                            resolveInt(event.payload["total_contracts"])
                                ?: EventTypes.DEFAULT_TOTAL_CONTRACTS
                    }
                }
                EventTypes.CONTRACT_STARTED -> {
                    // Contract has been opened; not yet counted as completed.
                    phase = EventTypes.CONTRACT_STARTED
                }
                EventTypes.CONTRACT_COMPLETED -> {
                    // Contract has been fully executed; increment the counter.
                    phase = EventTypes.CONTRACT_COMPLETED
                    contractsCompleted++
                }
                EventTypes.EXECUTION_COMPLETED -> {
                    // All contracts done; contract-execution phase is closed.
                    phase = EventTypes.EXECUTION_COMPLETED
                    executionCompleted = true
                }
                EventTypes.ASSEMBLY_STARTED -> {
                    phase = EventTypes.ASSEMBLY_STARTED
                    assemblyStarted = true
                }
                EventTypes.ASSEMBLY_VALIDATED -> {
                    phase = EventTypes.ASSEMBLY_VALIDATED
                    assemblyValidated = true
                }
                EventTypes.ASSEMBLY_COMPLETED -> {
                    phase = EventTypes.ASSEMBLY_COMPLETED
                }
            }
        }

        return ReplayState(
            phase = phase,
            contractsCompleted = contractsCompleted,
            totalContracts = totalContracts,
            executionStarted = executionStarted,
            executionCompleted = executionCompleted,
            assemblyStarted = assemblyStarted,
            assemblyValidated = assemblyValidated,
            objective = objective
        )
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
