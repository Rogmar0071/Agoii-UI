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
    val commitValid: Boolean = false,

    // ── AGOII-REPLAY-STATE-001: Authoritative state surface ──────────────────

    /**
     * Per-task execution status map (taskId → status string).
     *
     * Possible status values (in lifecycle order):
     *   "ASSIGNED", "STARTED", "EXECUTED_SUCCESS", "EXECUTED_FAILURE",
     *   "COMPLETED", "FAILED", "VALIDATED"
     *
     * Last write wins across retries/reassignments for the same taskId.
     * Decision modules MUST read task status from this map rather than
     * filtering raw event lists.
     */
    val taskStatus: Map<String, String> = emptyMap(),

    /**
     * Type of the most recent event in the ledger, or null when the ledger
     * is empty.  Decision modules MUST read this field rather than calling
     * loadEvents() and inspecting the last element.
     */
    val lastEventType: String? = null,

    /**
     * Payload of the most recent event in the ledger.  Empty map when the
     * ledger is empty.  Decision modules MUST read this field rather than
     * calling loadEvents() and extracting the last event's payload.
     */
    val lastEventPayload: Map<String, Any> = emptyMap(),

    /**
     * Report reference (RRID) derived from the first CONTRACTS_GENERATED
     * event.  Used by Governor for RRIL-1 propagation into TASK_ASSIGNED
     * payloads.  Empty string when no CONTRACTS_GENERATED event exists.
     */
    val reportReference: String = "",

    /**
     * Set of recoveryIds that already appear in DELTA_CONTRACT_CREATED
     * events.  Used by Governor for idempotency checks in the CLC-1 delta
     * loop.
     */
    val deltaContractRecoveryIds: Set<String> = emptySet(),

    /**
     * Set of taskIds that already appear in TASK_ASSIGNED events.  Used by
     * Governor for idempotency checks in the delta-loop TASK_ASSIGNED step.
     */
    val taskAssignedTaskIds: Set<String> = emptySet(),

    /**
     * contract_id from the most recent CONTRACT_STARTED event in the ledger.
     * Used by Governor when deriving CONTRACT_COMPLETED payloads.
     * Empty string when no CONTRACT_STARTED event has been seen.
     */
    val lastContractStartedId: String = "",

    /**
     * position value from the most recent CONTRACT_STARTED event.  Used by
     * Governor as a fallback when TASK_ASSIGNED.position is absent.
     * Null when no CONTRACT_STARTED event has been seen.
     */
    val lastContractStartedPosition: Int? = null
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

        // ── AGOII-REPLAY-STATE-001: Authoritative state fields ────────────────
        val taskStatusMutable = mutableMapOf<String, String>()
        var reportReference = ""
        val deltaContractRecoveryIds = mutableSetOf<String>()
        val taskAssignedTaskIds = mutableSetOf<String>()
        var lastContractStartedId = ""
        var lastContractStartedPosition: Int? = null

        for (event in events) {
            when (event.type) {
                EventTypes.INTENT_SUBMITTED    -> intentSubmitted = true
                EventTypes.CONTRACTS_GENERATED -> {
                    contractsGenerated = true
                    val contractsList = event.payload["contracts"] as? List<*>
                    totalContractsFromLedger = when {
                        !contractsList.isNullOrEmpty() -> contractsList.size
                        else -> resolveInt(event.payload["total"]) ?: 0
                    }
                    if (reportReference.isEmpty()) {
                        reportReference = event.payload["report_reference"]?.toString()
                            ?: event.payload["report_id"]?.toString()
                            ?: ""
                    }
                }
                EventTypes.CONTRACT_STARTED -> {
                    val cId = event.payload["contract_id"]?.toString() ?: ""
                    if (cId.isNotEmpty()) lastContractStartedId = cId
                    val pos = resolveInt(event.payload["position"])
                    if (pos != null) lastContractStartedPosition = pos
                }
                EventTypes.TASK_ASSIGNED       -> {
                    assignedTasks++
                    val tid = event.payload["taskId"]?.toString() ?: ""
                    if (tid.isNotEmpty()) {
                        taskStatusMutable[tid] = "ASSIGNED"
                        taskAssignedTaskIds.add(tid)
                    }
                }
                EventTypes.TASK_STARTED        -> {
                    val tid = event.payload["taskId"]?.toString() ?: ""
                    if (tid.isNotEmpty()) taskStatusMutable[tid] = "STARTED"
                }
                EventTypes.TASK_EXECUTED       -> {
                    val tid = event.payload["taskId"]?.toString() ?: ""
                    val execStatus = event.payload["executionStatus"]?.toString()
                    if (execStatus == "SUCCESS") {
                        successfulTaskExecutions++
                        if (tid.isNotEmpty()) taskStatusMutable[tid] = "EXECUTED_SUCCESS"
                    } else {
                        if (tid.isNotEmpty()) taskStatusMutable[tid] = "EXECUTED_FAILURE"
                    }
                }
                EventTypes.TASK_COMPLETED      -> {
                    completedTasks++
                    val tid = event.payload["taskId"]?.toString() ?: ""
                    if (tid.isNotEmpty()) taskStatusMutable[tid] = "COMPLETED"
                }
                EventTypes.TASK_FAILED         -> {
                    val tid = event.payload["taskId"]?.toString() ?: ""
                    if (tid.isNotEmpty()) taskStatusMutable[tid] = "FAILED"
                }
                EventTypes.TASK_VALIDATED      -> {
                    validatedTasks++
                    val tid = event.payload["taskId"]?.toString() ?: ""
                    if (tid.isNotEmpty()) taskStatusMutable[tid] = "VALIDATED"
                }
                EventTypes.DELTA_CONTRACT_CREATED -> {
                    val rid = event.payload["recoveryId"]?.toString() ?: ""
                    if (rid.isNotEmpty()) deltaContractRecoveryIds.add(rid)
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

        val lastEvent = events.lastOrNull()
        val lastEventType = lastEvent?.type
        val lastEventPayload: Map<String, Any> = lastEvent?.payload ?: emptyMap()

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
                // This field uses the legacy fullyExecuted gate for backward compatibility
                // with tests and existing consumers of AssemblyStructuralState.assemblyValid.
                // The canonical truth-layer assemblyValid is the top-level field below.
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
            commitValid = commitValid,
            // ── AGOII-REPLAY-STATE-001: Authoritative state fields ────────────
            taskStatus = taskStatusMutable,
            lastEventType = lastEventType,
            lastEventPayload = lastEventPayload,
            reportReference = reportReference,
            deltaContractRecoveryIds = deltaContractRecoveryIds,
            taskAssignedTaskIds = taskAssignedTaskIds,
            lastContractStartedId = lastContractStartedId,
            lastContractStartedPosition = lastContractStartedPosition
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
