package com.agoii.mobile.core

// ── Structural-truth state hierarchy (CR 1.4) ────────────────────────────────

/**
 * Top-level structural truth produced by Replay.
 * Governor makes all decisions from this object; no module self-validates.
 */
data class ReplayStructuralState(
    val intent: IntentStructuralState,
    val contracts: ContractStructuralState,
    val execution: ExecutionStructuralState,
    val assembly: AssemblyStructuralState
)

data class IntentStructuralState(
    val exists: Boolean,
    val objectiveDefined: Boolean,
    val structurallyComplete: Boolean
)

data class ContractStructuralState(
    val generated: Boolean,
    val totalContracts: Int,
    val valid: Boolean
)

data class ExecutionStructuralState(
    val totalTasks: Int,
    val assignedTasks: Int,
    val completedTasks: Int,
    val validatedTasks: Int,
    val fullyExecuted: Boolean
)

data class AssemblyStructuralState(
    val contractsClosed: Boolean,
    val executionClosed: Boolean,
    val structurallyComplete: Boolean,
    val assemblyValid: Boolean
)

// ─────────────────────────────────────────────────────────────────────────────

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
    val objective: String?,
    val assemblyCompleted: Boolean = false
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
        var assemblyCompleted = false
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
                    assemblyCompleted = true
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
            objective = objective,
            assemblyCompleted = assemblyCompleted
        )
    }

    // ── Structural-truth API (CR 1.4) ────────────────────────────────────────

    /**
     * Returns the full structural truth for [projectId] by replaying the ledger.
     * This is the authoritative read used by Governor for ALL structural decisions.
     */
    fun replayStructuralState(projectId: String): ReplayStructuralState {
        val events = eventStore.loadEvents(projectId)
        return deriveStructuralState(events)
    }

    /**
     * Pure function: events → [ReplayStructuralState].
     * No I/O, no side effects, fully deterministic.
     */
    fun deriveStructuralState(events: List<Event>): ReplayStructuralState {
        // ── Step 1: Build base state ──────────────────────────────────────────
        var intentExists = false
        var intentObjective: String? = null
        var totalContracts = 0
        var contractsGenerated = false
        var contractsCompleted = 0
        var executionCompleted = false
        var assemblyValidated = false

        // taskId → latest lifecycle state
        val taskStates = mutableMapOf<String, String>()

        for (event in events) {
            when (event.type) {
                EventTypes.INTENT_SUBMITTED -> {
                    intentExists = true
                    intentObjective = event.payload["objective"] as? String
                }
                EventTypes.CONTRACTS_GENERATED -> {
                    contractsGenerated = true
                    totalContracts = resolveInt(event.payload["total"])
                        ?: EventTypes.DEFAULT_TOTAL_CONTRACTS
                }
                EventTypes.CONTRACT_COMPLETED -> {
                    contractsCompleted++
                }
                EventTypes.EXECUTION_COMPLETED -> {
                    executionCompleted = true
                }
                EventTypes.ASSEMBLY_VALIDATED -> {
                    assemblyValidated = true
                }
                EventTypes.TASK_ASSIGNED -> {
                    val taskId = event.payload["task_id"] as? String
                    if (taskId != null) taskStates[taskId] = EventTypes.TASK_ASSIGNED
                }
                EventTypes.TASK_STARTED -> {
                    val taskId = event.payload["task_id"] as? String
                    if (taskId != null) taskStates[taskId] = EventTypes.TASK_STARTED
                }
                EventTypes.TASK_COMPLETED -> {
                    val taskId = event.payload["task_id"] as? String
                    if (taskId != null) taskStates[taskId] = EventTypes.TASK_COMPLETED
                }
                EventTypes.TASK_VALIDATED -> {
                    val taskId = event.payload["task_id"] as? String
                    if (taskId != null) taskStates[taskId] = EventTypes.TASK_VALIDATED
                }
            }
        }

        // ── Step 2: Derive structural sub-states ─────────────────────────────
        val intentState = IntentStructuralState(
            exists = intentExists,
            objectiveDefined = intentObjective != null,
            structurallyComplete = intentExists && intentObjective != null
        )

        val contractState = ContractStructuralState(
            generated = contractsGenerated,
            totalContracts = totalContracts,
            valid = contractsGenerated && totalContracts > 0
        )

        val activeStates = setOf(
            EventTypes.TASK_ASSIGNED,
            EventTypes.TASK_STARTED,
            EventTypes.TASK_COMPLETED,
            EventTypes.TASK_VALIDATED
        )
        val assignedCount   = taskStates.count { it.value in activeStates }
        val completedCount  = taskStates.count {
            it.value == EventTypes.TASK_COMPLETED || it.value == EventTypes.TASK_VALIDATED
        }
        val validatedCount  = taskStates.count { it.value == EventTypes.TASK_VALIDATED }

        val executionState = ExecutionStructuralState(
            totalTasks     = taskStates.size,
            assignedTasks  = assignedCount,
            completedTasks = completedCount,
            validatedTasks = validatedCount,
            fullyExecuted  = executionCompleted
        )

        val contractsClosed = totalContracts > 0 && contractsCompleted >= totalContracts
        val assemblyState = AssemblyStructuralState(
            contractsClosed     = contractsClosed,
            executionClosed     = executionCompleted,
            structurallyComplete = contractsClosed && executionCompleted,
            assemblyValid       = assemblyValidated
        )

        return ReplayStructuralState(
            intent    = intentState,
            contracts = contractState,
            execution = executionState,
            assembly  = assemblyState
        )
    }

    // ── End structural-truth API ──────────────────────────────────────────────

    /** Gson deserialises all numbers as Double; this helper normalises to Int. */
    private fun resolveInt(value: Any?): Int? = when (value) {
        is Int    -> value
        is Double -> value.toInt()
        is Long   -> value.toInt()
        is String -> value.toIntOrNull()
        else      -> null
    }
}
