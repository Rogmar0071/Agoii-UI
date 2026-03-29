package com.agoii.mobile.core

data class ReplayStructuralState(
    val intent: IntentStructuralState,
    val contracts: ContractStructuralState,
    val execution: ExecutionStructuralState,
    val assembly: AssemblyStructuralState,
    // ICS lifecycle booleans — flattened; no intermediate domain object (AERP-1)
    val icsStarted: Boolean = false,
    val icsCompleted: Boolean = false,
    // Commit lifecycle booleans — flattened; no intermediate domain object (AERP-1)
    val commitContractExists: Boolean = false,
    val commitExecuted: Boolean = false,
    val commitAborted: Boolean = false,
    /** derived: commitContractExists && !commitExecuted && !commitAborted */
    val commitPending: Boolean = false,
    // AERP-1 truth layer — top-level validity fields
    val executionValid: Boolean = false,
    val assemblyValid: Boolean = false,
    val icsValid: Boolean = false,
    /** V3: commitContractExists && (commitExecuted || commitAborted) */
    val commitValid: Boolean = false
)

data class IntentStructuralState(
    val structurallyComplete: Boolean
)

data class ContractStructuralState(
    val generated: Boolean,
    val valid: Boolean,
    val totalContracts: Int = 0
)

data class ExecutionStructuralState(
    val totalTasks: Int,
    val assignedTasks: Int,
    val completedTasks: Int,
    val validatedTasks: Int,
    val fullyExecuted: Boolean,
    val successfulTasks: Int = 0
)

data class AssemblyStructuralState(
    val assemblyStarted: Boolean,
    val assemblyValidated: Boolean,
    val assemblyCompleted: Boolean,
    val assemblyValid: Boolean
)

class Replay(private val eventStore: EventRepository) {

    fun replayStructuralState(projectId: String): ReplayStructuralState {
        val events = eventStore.loadEvents(projectId)
        return deriveStructuralState(events)
    }

    fun deriveStructuralState(events: List<Event>): ReplayStructuralState {
        var intentSubmitted = false
        var contractsGenerated = false
        var totalContractsFromLedger = 0
        var assemblyStarted = false
        var assemblyValidated = false
        var assemblyCompleted = false
        var icsStarted = false
        var icsCompleted = false
        var commitContractExists = false
        var commitExecuted = false
        var commitAborted = false

        var assignedTasks = 0
        var completedTasks = 0
        var validatedTasks = 0
        // Count TASK_EXECUTED(SUCCESS) events for executionValid (FS-2)
        var successfulTaskExecutions = 0

        for (event in events) {
            when (event.type) {
                EventTypes.INTENT_SUBMITTED    -> intentSubmitted = true
                EventTypes.CONTRACTS_GENERATED -> {
                    contractsGenerated = true
                    totalContractsFromLedger = resolveInt(event.payload["total"]) ?: 0
                }
                EventTypes.TASK_ASSIGNED       -> assignedTasks++
                EventTypes.TASK_COMPLETED      -> completedTasks++
                EventTypes.TASK_VALIDATED      -> validatedTasks++
                EventTypes.TASK_EXECUTED       -> {
                    if (event.payload["executionStatus"]?.toString() == "SUCCESS") {
                        successfulTaskExecutions++
                    }
                }
                EventTypes.ASSEMBLY_STARTED    -> assemblyStarted = true
                EventTypes.ASSEMBLY_VALIDATED  -> assemblyValidated = true
                EventTypes.ASSEMBLY_COMPLETED  -> assemblyCompleted = true
                EventTypes.ICS_STARTED         -> icsStarted = true
                EventTypes.ICS_COMPLETED       -> icsCompleted = true
                EventTypes.COMMIT_CONTRACT     -> commitContractExists = true
                EventTypes.COMMIT_EXECUTED     -> commitExecuted = true
                EventTypes.COMMIT_ABORTED      -> commitAborted = true
            }
        }

        val totalTasks = assignedTasks

        // Legacy fullyExecuted: uses validatedTasks count (backward compat for pre-TASK_EXECUTED ledgers)
        val fullyExecuted = totalTasks > 0 && validatedTasks == totalTasks

        // executionValid = count(TASK_EXECUTED SUCCESS) == totalContracts
        val totalContracts = if (totalContractsFromLedger > 0) totalContractsFromLedger else totalTasks
        val executionValid = totalContracts > 0 && successfulTaskExecutions == totalContracts

        // Legacy assemblyValid (backward compat): uses old fullyExecuted gate
        val legacyAssemblyValid = assemblyStarted && assemblyCompleted && fullyExecuted

        // assemblyValid uses executionValid gate
        val assemblyValidNew = assemblyStarted && assemblyCompleted && executionValid

        // icsValid = icsStarted && icsCompleted && assemblyValid
        val icsValid = icsStarted && icsCompleted && assemblyValidNew

        // commitPending: COMMIT_CONTRACT seen but no resolution yet
        val commitPending = commitContractExists && !commitExecuted && !commitAborted

        // commitValid (V3): COMMIT_CONTRACT seen AND resolved (approved or aborted)
        val commitValid = commitContractExists && (commitExecuted || commitAborted)

        return ReplayStructuralState(
            intent = IntentStructuralState(
                structurallyComplete = intentSubmitted
            ),
            contracts = ContractStructuralState(
                generated = contractsGenerated,
                valid = contractsGenerated,
                totalContracts = totalContracts
            ),
            execution = ExecutionStructuralState(
                totalTasks = totalTasks,
                assignedTasks = assignedTasks,
                completedTasks = completedTasks,
                validatedTasks = validatedTasks,
                fullyExecuted = fullyExecuted,
                successfulTasks = successfulTaskExecutions
            ),
            assembly = AssemblyStructuralState(
                assemblyStarted = assemblyStarted,
                assemblyValidated = assemblyValidated,
                assemblyCompleted = assemblyCompleted,
                assemblyValid = legacyAssemblyValid
            ),
            icsStarted = icsStarted,
            icsCompleted = icsCompleted,
            commitContractExists = commitContractExists,
            commitExecuted = commitExecuted,
            commitAborted = commitAborted,
            commitPending = commitPending,
            executionValid = executionValid,
            assemblyValid = assemblyValidNew,
            icsValid = icsValid,
            commitValid = commitValid
        )
    }

    private fun resolveInt(value: Any?): Int? = when (value) {
        is Int    -> value
        is Double -> value.toInt()
        is Long   -> value.toInt()
        is String -> value.toIntOrNull()
        else      -> null
    }
}
