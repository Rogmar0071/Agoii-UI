package com.agoii.mobile.core

data class ReplayStructuralState(
    val intent: IntentStructuralState,
    val contracts: ContractStructuralState,
    val execution: ExecutionStructuralState,
    val assembly: AssemblyStructuralState,
    val ics: IcsStructuralState = IcsStructuralState(false, false, ""),
    val commit: CommitStructuralState = CommitStructuralState(false, false, false, emptyList(), "", ""),
    // AERP-1 truth layer — top-level validity fields (FS-2)
    val executionValid: Boolean = false,
    val assemblyValid: Boolean = false,
    val icsValid: Boolean = false
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

data class IcsStructuralState(
    val icsStarted: Boolean,
    val icsCompleted: Boolean,
    val icsOutputReference: String
)

data class CommitStructuralState(
    val commitPending: Boolean,
    val commitApproved: Boolean,
    val commitRejected: Boolean,
    val proposedActions: List<String>,
    val finalArtifactReference: String,
    val reportReference: String
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
        var icsOutputReference = ""
        var commitPending = false
        var commitApproved = false
        var commitRejected = false
        var commitProposedActions = emptyList<String>()
        var commitFinalArtifactReference = ""
        var commitReportReference = ""

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
                EventTypes.ICS_COMPLETED       -> {
                    icsCompleted = true
                    icsOutputReference = event.payload["icsOutputReference"]?.toString() ?: ""
                }
                EventTypes.COMMIT_CONTRACT     -> {
                    commitPending = true
                    commitApproved = false
                    commitRejected = false
                    @Suppress("UNCHECKED_CAST")
                    commitProposedActions = (event.payload["proposedActions"] as? List<String>) ?: emptyList()
                    commitFinalArtifactReference = event.payload["finalArtifactReference"]?.toString() ?: ""
                    commitReportReference = event.payload["report_reference"]?.toString() ?: ""
                }
                EventTypes.COMMIT_EXECUTED     -> {
                    commitPending = false
                    commitApproved = true
                }
                EventTypes.COMMIT_ABORTED      -> {
                    commitPending = false
                    commitRejected = true
                }
            }
        }

        val totalTasks = assignedTasks

        // Legacy fullyExecuted: uses validatedTasks count (backward compat for pre-TASK_EXECUTED ledgers)
        val fullyExecuted = totalTasks > 0 && validatedTasks == totalTasks

        // FS-2: executionValid = count(TASK_EXECUTED SUCCESS) == totalContracts
        val totalContracts = if (totalContractsFromLedger > 0) totalContractsFromLedger else totalTasks
        val executionValid = totalContracts > 0 && successfulTaskExecutions == totalContracts

        // Legacy assemblyValid (backward compat): uses old fullyExecuted gate
        val legacyAssemblyValid = assemblyStarted && assemblyCompleted && fullyExecuted

        // FS-2: top-level assemblyValid uses executionValid gate
        val assemblyValidNew = assemblyStarted && assemblyCompleted && executionValid

        // FS-2: icsValid = icsStarted && icsCompleted && assemblyValid(new)
        val icsValid = icsStarted && icsCompleted && assemblyValidNew

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
            ics = IcsStructuralState(
                icsStarted = icsStarted,
                icsCompleted = icsCompleted,
                icsOutputReference = icsOutputReference
            ),
            commit = CommitStructuralState(
                commitPending = commitPending,
                commitApproved = commitApproved,
                commitRejected = commitRejected,
                proposedActions = commitProposedActions,
                finalArtifactReference = commitFinalArtifactReference,
                reportReference = commitReportReference
            ),
            executionValid = executionValid,
            assemblyValid = assemblyValidNew,
            icsValid = icsValid
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
