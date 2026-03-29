package com.agoii.mobile.core

data class ReplayStructuralState(
    val intent: IntentStructuralState,
    val contracts: ContractStructuralState,
    val execution: ExecutionStructuralState,
    val assembly: AssemblyStructuralState,
    val ics: IcsStructuralState
)

data class IntentStructuralState(
    val structurallyComplete: Boolean
)

data class ContractStructuralState(
    val generated: Boolean,
    val valid: Boolean
)

data class ExecutionStructuralState(
    val totalTasks: Int,
    val assignedTasks: Int,
    val completedTasks: Int,
    val validatedTasks: Int,
    val fullyExecuted: Boolean,
    val executionValid: Boolean
)

data class AssemblyStructuralState(
    val assemblyStarted: Boolean,
    val assemblyValidated: Boolean,
    val assemblyCompleted: Boolean,
    val assemblyValid: Boolean
)

data class IcsStructuralState(
    val icsStarted: Boolean,
    val icsCompleted: Boolean,
    val icsValid: Boolean
)

class Replay(private val eventStore: EventRepository) {

    fun replayStructuralState(projectId: String): ReplayStructuralState {
        val events = eventStore.loadEvents(projectId)
        return deriveStructuralState(events)
    }

    fun deriveStructuralState(events: List<Event>): ReplayStructuralState {
        var intentSubmitted = false
        var contractsGenerated = false
        var assemblyStarted = false
        var assemblyValidated = false
        var assemblyCompleted = false
        var icsStarted = false
        var icsCompleted = false

        var assignedTasks = 0
        var completedTasks = 0
        var validatedTasks = 0
        var executedSuccessCount = 0

        for (event in events) {
            when (event.type) {
                EventTypes.INTENT_SUBMITTED    -> intentSubmitted = true
                EventTypes.CONTRACTS_GENERATED -> contractsGenerated = true
                EventTypes.TASK_ASSIGNED       -> assignedTasks++
                EventTypes.TASK_COMPLETED      -> completedTasks++
                EventTypes.TASK_VALIDATED      -> validatedTasks++
                EventTypes.TASK_EXECUTED       -> {
                    if (event.payload["executionStatus"]?.toString() == "SUCCESS") {
                        executedSuccessCount++
                    }
                }
                EventTypes.ASSEMBLY_STARTED    -> assemblyStarted = true
                EventTypes.ASSEMBLY_VALIDATED  -> assemblyValidated = true
                EventTypes.ASSEMBLY_COMPLETED  -> assemblyCompleted = true
                EventTypes.ICS_STARTED         -> icsStarted = true
                EventTypes.ICS_COMPLETED       -> icsCompleted = true
            }
        }

        val totalTasks = assignedTasks
        val fullyExecuted = totalTasks > 0 && executedSuccessCount == totalTasks
        val executionValid = fullyExecuted

        val assemblyValid = assemblyStarted &&
            assemblyCompleted &&
            executionValid

        val icsValid = icsStarted && icsCompleted && assemblyValid

        return ReplayStructuralState(
            intent = IntentStructuralState(
                structurallyComplete = intentSubmitted
            ),
            contracts = ContractStructuralState(
                generated = contractsGenerated,
                valid = contractsGenerated
            ),
            execution = ExecutionStructuralState(
                totalTasks = totalTasks,
                assignedTasks = assignedTasks,
                completedTasks = completedTasks,
                validatedTasks = validatedTasks,
                fullyExecuted = fullyExecuted,
                executionValid = executionValid
            ),
            assembly = AssemblyStructuralState(
                assemblyStarted = assemblyStarted,
                assemblyValidated = assemblyValidated,
                assemblyCompleted = assemblyCompleted,
                assemblyValid = assemblyValid
            ),
            ics = IcsStructuralState(
                icsStarted = icsStarted,
                icsCompleted = icsCompleted,
                icsValid = icsValid
            )
        )
    }
}
