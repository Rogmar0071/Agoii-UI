package com.agoii.mobile.core

class LedgerValidationException(message: String) : RuntimeException(message)

class ValidationLayer {

    // ── Internal lifecycle enum ────────────────────────────────────────────────

    private enum class TaskLifecycle { ASSIGNED, STARTED, EXECUTED, COMPLETED, VALIDATED }

    // ── Derived validation state (single O(n) pass over simulated) ────────────

    private data class ValidationState(
        /** Type of the candidate event (simulated.last().type). */
        val candidateType: String,
        /** Type of the event immediately before the candidate; null if candidate is first. */
        val priorEventType: String?,
        /** Total number of events in the simulated ledger. */
        val simulatedSize: Int,
        /** True when every event in simulated has sequenceNumber == its index. */
        val isSequenceValid: Boolean,
        /** Index of the first sequence violation; -1 when isSequenceValid is true. */
        val firstSeqViolationAt: Int,
        /** True when ASSEMBLY_COMPLETED appears anywhere in simulated. */
        val isTerminal: Boolean,
        /** Total contracts declared by the first CONTRACT_STARTED; null if none seen. */
        val totalContracts: Int?,
        /** Count of CONTRACT_COMPLETED events in simulated. */
        val completedContracts: Int,
        /** Net open contract count across all positions in simulated. */
        val activeContractCount: Int,
        /** Per-position net: (# CONTRACT_STARTED) − (# CONTRACT_COMPLETED) in simulated. */
        val contractNetByPosition: Map<Int, Int>,
        /** True when EXECUTION_COMPLETED appears in simulated. */
        val hasExecutionCompleted: Boolean,
        /** All task lifecycle levels reached per taskId in simulated. */
        val tasks: Map<String, Set<TaskLifecycle>>
    )

    // ── Public entry point ────────────────────────────────────────────────────

    fun validate(
        projectId: String,
        type: String,
        payload: Map<String, Any>,
        currentEvents: List<Event>
    ) {
        // Phase 0 — type guard: must precede simulation construction
        if (type !in EventTypes.ALL) {
            throw LedgerValidationException("Unknown event type '$type' for '$projectId'")
        }

        // Phase 1 — construct simulated ledger; currentEvents is referenced ONLY here
        val simulated = currentEvents + Event(
            type           = type,
            payload        = payload,
            sequenceNumber = currentEvents.size.toLong()
        )

        // Phase 2 — derive all shared state in a single O(n) pass
        val state = deriveState(simulated)

        // Phase 3 — all checks consume state; no further ledger scans
        checkStructural(projectId, state)
        checkTransition(projectId, state)
        checkPayload(projectId, type, payload, state)
        checkInvariants(projectId, type, payload, state)
    }

    // ── Single-pass state derivation ──────────────────────────────────────────

    private fun deriveState(simulated: List<Event>): ValidationState {
        var isSequenceValid    = true
        var firstSeqViolationAt = -1
        var isTerminal         = false
        var totalContracts: Int? = null
        val contractFreq       = mutableMapOf<Int, Int>()
        var completedContracts = 0
        var hasExecutionCompleted = false
        val taskState          = mutableMapOf<String, MutableSet<TaskLifecycle>>()
        var priorEventType: String? = null
        val lastIndex          = simulated.size - 1

        for (i in simulated.indices) {
            val ev = simulated[i]

            // Capture the type of the event immediately before the candidate
            if (i == lastIndex - 1) priorEventType = ev.type

            // Sequence continuity across the full simulated ledger
            if (isSequenceValid && ev.sequenceNumber != i.toLong()) {
                isSequenceValid      = false
                firstSeqViolationAt  = i
            }

            // Terminal detection — COMMIT_EXECUTED or COMMIT_ABORTED close the full lifecycle.
            // ICS_COMPLETED is no longer terminal (COMMIT_CONTRACT follows it).
            if (ev.type == EventTypes.COMMIT_EXECUTED || ev.type == EventTypes.COMMIT_ABORTED) isTerminal = true

            // Contract tracking
            when (ev.type) {
                EventTypes.CONTRACT_STARTED -> {
                    val pos   = ev.payload["position"]?.let { toInt(it) }
                    val total = ev.payload["total"]?.let { toInt(it) }
                    if (totalContracts == null && total != null) totalContracts = total
                    if (pos != null) contractFreq[pos] = (contractFreq[pos] ?: 0) + 1
                }
                EventTypes.CONTRACT_COMPLETED -> {
                    completedContracts++
                    val pos = ev.payload["position"]?.let { toInt(it) }
                    if (pos != null) contractFreq[pos] = (contractFreq[pos] ?: 0) - 1
                }
                EventTypes.EXECUTION_COMPLETED -> hasExecutionCompleted = true
            }

            // Task lifecycle — accumulate all levels reached per task
            val taskId = ev.payload["taskId"]?.toString()
            if (taskId != null) {
                val level: TaskLifecycle? = when (ev.type) {
                    EventTypes.TASK_ASSIGNED  -> TaskLifecycle.ASSIGNED
                    EventTypes.TASK_STARTED   -> TaskLifecycle.STARTED
                    EventTypes.TASK_EXECUTED  -> TaskLifecycle.EXECUTED
                    EventTypes.TASK_COMPLETED -> TaskLifecycle.COMPLETED
                    EventTypes.TASK_VALIDATED -> TaskLifecycle.VALIDATED
                    else                       -> null
                }
                if (level != null) taskState.getOrPut(taskId) { mutableSetOf() }.add(level)
            }
        }

        return ValidationState(
            candidateType         = simulated[lastIndex].type,
            priorEventType        = priorEventType,
            simulatedSize         = simulated.size,
            isSequenceValid       = isSequenceValid,
            firstSeqViolationAt   = firstSeqViolationAt,
            isTerminal            = isTerminal,
            totalContracts        = totalContracts,
            completedContracts    = completedContracts,
            activeContractCount   = contractFreq.values.filter { it > 0 }.sum(),
            contractNetByPosition = contractFreq.toMap(),
            hasExecutionCompleted = hasExecutionCompleted,
            tasks                 = taskState.mapValues { it.value.toSet() }
        )
    }

    // ── 1. STRUCTURAL ─────────────────────────────────────────────────────────

    private fun checkStructural(projectId: String, state: ValidationState) {
        if (state.simulatedSize == 1 && state.candidateType != EventTypes.INTENT_SUBMITTED) {
            throw LedgerValidationException(
                "First event must be '${EventTypes.INTENT_SUBMITTED}' for '$projectId'"
            )
        }
        if (!state.isSequenceValid) {
            throw LedgerValidationException(
                "Invariant 1: sequence violation in '$projectId' at index ${state.firstSeqViolationAt}"
            )
        }
    }

    // ── 2. TRANSITION ─────────────────────────────────────────────────────────

    private fun checkTransition(projectId: String, state: ValidationState) {
        val prior = state.priorEventType ?: return
        if (!LedgerAudit.isLegalTransition(prior, state.candidateType)) {
            throw LedgerValidationException(
                "Illegal transition '$prior' → '${state.candidateType}' in '$projectId'"
            )
        }
    }

    // ── 3. PAYLOAD ────────────────────────────────────────────────────────────

    private fun checkPayload(
        projectId: String,
        type: String,
        payload: Map<String, Any>,
        state: ValidationState
    ) {
        checkGlobalPayload(projectId, type, payload)
        when (type) {
            EventTypes.CONTRACTS_GENERATED -> checkContractsGenerated(projectId, payload)
            EventTypes.CONTRACT_STARTED    -> checkContractStarted(projectId, payload)
            EventTypes.TASK_ASSIGNED       -> checkTaskAssigned(projectId, payload)
            EventTypes.TASK_STARTED        -> checkTaskStarted(projectId, payload, state)
            EventTypes.TASK_EXECUTED       -> checkTaskExecuted(projectId, payload, state)
            EventTypes.TASK_COMPLETED      -> checkTaskCompleted(projectId, payload, state)
            EventTypes.TASK_VALIDATED      -> checkTaskValidated(projectId, payload, state)
            EventTypes.RECOVERY_CONTRACT   -> checkRecoveryContract(projectId, payload)
            EventTypes.DELTA_CONTRACT_CREATED -> checkDeltaContractCreated(projectId, payload)
            EventTypes.CONTRACT_COMPLETED  -> checkContractCompleted(projectId, payload, state)
            EventTypes.EXECUTION_COMPLETED -> checkExecutionCompleted(projectId, payload, state)
            EventTypes.ASSEMBLY_STARTED    -> checkAssemblyStarted(projectId, payload)
            EventTypes.ASSEMBLY_VALIDATED  -> checkAssemblyValidated(projectId, state)
            EventTypes.ASSEMBLY_COMPLETED  -> checkAssemblyCompleted(projectId, payload)
            EventTypes.ICS_STARTED         -> checkIcsStarted(projectId, payload)
            EventTypes.ICS_COMPLETED       -> checkIcsCompleted(projectId, payload)
            // UCS-1 ingestion lifecycle events
            EventTypes.CONTRACT_CREATED    -> checkContractCreated(projectId, payload)
            EventTypes.CONTRACT_VALIDATED  -> checkContractValidatedEvent(projectId, payload)
            EventTypes.CONTRACT_APPROVED   -> checkContractApproved(projectId, payload)
            // Commit contract lifecycle events
            EventTypes.COMMIT_CONTRACT     -> checkCommitContract(projectId, payload)
            EventTypes.COMMIT_EXECUTED     -> checkCommitResult(projectId, payload, EventTypes.COMMIT_EXECUTED)
            EventTypes.COMMIT_ABORTED      -> checkCommitResult(projectId, payload, EventTypes.COMMIT_ABORTED)
        }
    }

    private fun checkGlobalPayload(projectId: String, type: String, payload: Map<String, Any>) {
        for (key in payload.keys) {
            if (key.isBlank()) {
                throw LedgerValidationException(
                    "Payload contains blank key for '$type' in '$projectId'"
                )
            }
        }
        for ((key, value) in payload) {
            @Suppress("SENSELESS_COMPARISON")
            if (value == null) {
                throw LedgerValidationException(
                    "Payload contains null value for key '$key' in '$type' of '$projectId'"
                )
            }
        }
    }

    /** Rejects any payload key that is not in [allowed]. */
    private fun requireKeys(
        projectId: String,
        type: String,
        payload: Map<String, Any>,
        allowed: Set<String>
    ) {
        val unknown = payload.keys - allowed
        if (unknown.isNotEmpty()) {
            throw LedgerValidationException(
                "Unexpected payload keys $unknown for '$type' in '$projectId'"
            )
        }
    }

    private fun checkContractsGenerated(projectId: String, payload: Map<String, Any>) {
        requireKeys(projectId, EventTypes.CONTRACTS_GENERATED, payload, CONTRACTS_GENERATED_KEYS)
        payload["intentId"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException(
                "CONTRACTS_GENERATED missing or blank 'intentId' in '$projectId'"
            )
        payload["contractSetId"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException(
                "CONTRACTS_GENERATED missing or blank 'contractSetId' in '$projectId'"
            )
        val totalRaw = payload["total"]
            ?: throw LedgerValidationException(
                "CONTRACTS_GENERATED missing 'total' in '$projectId'"
            )
        val total = toInt(totalRaw)
            ?: throw LedgerValidationException(
                "CONTRACTS_GENERATED 'total' must be an integer in '$projectId'"
            )
        if (total < 1) {
            throw LedgerValidationException(
                "CONTRACTS_GENERATED 'total' must be >= 1, got $total in '$projectId'"
            )
        }
        val contracts = payload["contracts"] as? List<*>
            ?: throw LedgerValidationException(
                "CONTRACTS_GENERATED missing 'contracts' list in '$projectId'"
            )
        if (contracts.isEmpty()) {
            throw LedgerValidationException(
                "CONTRACTS_GENERATED 'contracts' list must not be empty in '$projectId'"
            )
        }
        contracts.filterIsInstance<Map<*, *>>().forEachIndexed { i, contract ->
            val contractId = contract["contractId"]?.toString()
            if (contractId.isNullOrBlank()) {
                throw LedgerValidationException(
                    "CONTRACTS_GENERATED contract[$i] missing or blank 'contractId' in '$projectId'"
                )
            }
            if (contract["position"] == null) {
                throw LedgerValidationException(
                    "CONTRACTS_GENERATED contract[$i] missing 'position' in '$projectId'"
                )
            }
        }
    }

    private fun checkContractStarted(projectId: String, payload: Map<String, Any>) {
        requireKeys(projectId, EventTypes.CONTRACT_STARTED, payload, CONTRACT_STARTED_KEYS)
        val posRaw = payload["position"]
            ?: throw LedgerValidationException(
                "CONTRACT_STARTED missing 'position' in '$projectId'"
            )
        val position = toInt(posRaw)
            ?: throw LedgerValidationException(
                "CONTRACT_STARTED 'position' must be an integer in '$projectId'"
            )
        val totalRaw = payload["total"]
            ?: throw LedgerValidationException(
                "CONTRACT_STARTED missing 'total' in '$projectId'"
            )
        val total = toInt(totalRaw)
            ?: throw LedgerValidationException(
                "CONTRACT_STARTED 'total' must be an integer in '$projectId'"
            )
        payload["contract_id"]
            ?: throw LedgerValidationException(
                "CONTRACT_STARTED missing 'contract_id' in '$projectId'"
            )
        if (position < 1) {
            throw LedgerValidationException(
                "CONTRACT_STARTED 'position' must be ≥ 1, got $position in '$projectId'"
            )
        }
        if (total < position) {
            throw LedgerValidationException(
                "CONTRACT_STARTED 'total' ($total) must be ≥ 'position' ($position) in '$projectId'"
            )
        }
    }

    private fun checkTaskAssigned(projectId: String, payload: Map<String, Any>) {
        requireKeys(projectId, EventTypes.TASK_ASSIGNED, payload, TASK_ASSIGNED_KEYS)
        payload["taskId"]
            ?: throw LedgerValidationException(
                "TASK_ASSIGNED missing 'taskId' in '$projectId'"
            )
    }

    private fun checkTaskExecuted(
        projectId: String,
        payload: Map<String, Any>,
        state: ValidationState
    ) {
        requireKeys(projectId, EventTypes.TASK_EXECUTED, payload, TASK_EXECUTED_KEYS)
        val taskId = payload["taskId"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException(
                "TASK_EXECUTED missing or blank 'taskId' in '$projectId'"
            )
        payload["contractId"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException(
                "TASK_EXECUTED missing or blank 'contractId' in '$projectId'"
            )
        val contractorIdValue = payload["contractorId"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException(
                "TASK_EXECUTED missing or blank 'contractorId' in '$projectId'"
            )
        if (contractorIdValue == "NONE") {
            throw LedgerValidationException(
                "TASK_EXECUTED 'contractorId' must not be implicit NONE: use NO_CONTRACTOR_MATCH for resolution failures in '$projectId'"
            )
        }
        payload["report_reference"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException(
                "TASK_EXECUTED missing or blank 'report_reference' (RRIL-1) in '$projectId'"
            )
        val execStatus = payload["executionStatus"]?.toString()
            ?: throw LedgerValidationException(
                "TASK_EXECUTED missing 'executionStatus' in '$projectId'"
            )
        if (execStatus !in setOf("SUCCESS", "FAILURE")) {
            throw LedgerValidationException(
                "TASK_EXECUTED 'executionStatus' must be SUCCESS or FAILURE in '$projectId'"
            )
        }
        val validStatus = payload["validationStatus"]?.toString()
            ?: throw LedgerValidationException(
                "TASK_EXECUTED missing 'validationStatus' in '$projectId'"
            )
        if (validStatus !in setOf("VALIDATED", "FAILED")) {
            throw LedgerValidationException(
                "TASK_EXECUTED 'validationStatus' must be VALIDATED or FAILED in '$projectId'"
            )
        }
        if (TaskLifecycle.STARTED !in (state.tasks[taskId] ?: emptySet())) {
            throw LedgerValidationException(
                "TASK_EXECUTED: taskId '$taskId' has no TASK_STARTED event in '$projectId'"
            )
        }
        checkPositionAndTotal(projectId, EventTypes.TASK_EXECUTED, payload)
    }

    private fun checkTaskStarted(
        projectId: String,
        payload: Map<String, Any>,
        state: ValidationState
    ) {
        requireKeys(projectId, EventTypes.TASK_STARTED, payload, TASK_WITH_POSITION_KEYS)
        val taskId = payload["taskId"]?.toString()
            ?: throw LedgerValidationException(
                "TASK_STARTED missing 'taskId' in '$projectId'"
            )
        if (TaskLifecycle.ASSIGNED !in (state.tasks[taskId] ?: emptySet())) {
            throw LedgerValidationException(
                "TASK_STARTED: taskId '$taskId' not found in TASK_ASSIGNED events in '$projectId'"
            )
        }
        checkPositionAndTotal(projectId, EventTypes.TASK_STARTED, payload)
    }

    private fun checkTaskCompleted(
        projectId: String,
        payload: Map<String, Any>,
        state: ValidationState
    ) {
        requireKeys(projectId, EventTypes.TASK_COMPLETED, payload, TASK_WITH_POSITION_KEYS)
        val taskId = payload["taskId"]?.toString()
            ?: throw LedgerValidationException(
                "TASK_COMPLETED missing 'taskId' in '$projectId'"
            )
        if (TaskLifecycle.STARTED !in (state.tasks[taskId] ?: emptySet())) {
            throw LedgerValidationException(
                "TASK_COMPLETED: taskId '$taskId' not found in TASK_STARTED events in '$projectId'"
            )
        }
        checkPositionAndTotal(projectId, EventTypes.TASK_COMPLETED, payload)
    }

    private fun checkTaskValidated(
        projectId: String,
        payload: Map<String, Any>,
        state: ValidationState
    ) {
        requireKeys(projectId, EventTypes.TASK_VALIDATED, payload, TASK_ID_ONLY)
        val taskId = payload["taskId"]?.toString()
            ?: throw LedgerValidationException(
                "TASK_VALIDATED missing 'taskId' in '$projectId'"
            )
        if (TaskLifecycle.COMPLETED !in (state.tasks[taskId] ?: emptySet())) {
            throw LedgerValidationException(
                "TASK_VALIDATED: taskId '$taskId' not found in TASK_COMPLETED events in '$projectId'"
            )
        }
    }

    private fun checkRecoveryContract(projectId: String, payload: Map<String, Any>) {
        requireKeys(projectId, EventTypes.RECOVERY_CONTRACT, payload, RECOVERY_CONTRACT_KEYS)
        payload["contractId"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException(
                "RECOVERY_CONTRACT missing or blank 'contractId' in '$projectId'"
            )
        payload["failureClass"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException(
                "RECOVERY_CONTRACT missing or blank 'failureClass' in '$projectId'"
            )
        payload["violationField"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException(
                "RECOVERY_CONTRACT missing or blank 'violationField' in '$projectId'"
            )
        payload["artifactReference"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException(
                "RECOVERY_CONTRACT missing or blank 'artifactReference' in '$projectId'"
            )
    }

    private fun checkDeltaContractCreated(projectId: String, payload: Map<String, Any>) {
        requireKeys(projectId, EventTypes.DELTA_CONTRACT_CREATED, payload, DELTA_CONTRACT_CREATED_KEYS)
        payload["contractId"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException(
                "DELTA_CONTRACT_CREATED missing or blank 'contractId' in '$projectId'"
            )
        payload["violationField"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException(
                "DELTA_CONTRACT_CREATED missing or blank 'violationField' in '$projectId'"
            )
        payload["report_reference"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException(
                "DELTA_CONTRACT_CREATED missing or blank 'report_reference' in '$projectId'"
            )
        val countRaw = payload["delta_iteration_count"]
            ?: throw LedgerValidationException(
                "DELTA_CONTRACT_CREATED missing 'delta_iteration_count' in '$projectId'"
            )
        val count = toInt(countRaw)
            ?: throw LedgerValidationException(
                "DELTA_CONTRACT_CREATED 'delta_iteration_count' must be an integer in '$projectId'"
            )
        if (count < 1) {
            throw LedgerValidationException(
                "DELTA_CONTRACT_CREATED 'delta_iteration_count' must be >= 1, got $count in '$projectId'"
            )
        }
    }

    private fun checkContractCompleted(
        projectId: String,
        payload: Map<String, Any>,
        state: ValidationState
    ) {
        requireKeys(projectId, EventTypes.CONTRACT_COMPLETED, payload, CONTRACT_COMPLETED_KEYS)
        val posRaw = payload["position"]
            ?: throw LedgerValidationException(
                "CONTRACT_COMPLETED missing 'position' in '$projectId'"
            )
        val position = toInt(posRaw)
            ?: throw LedgerValidationException(
                "CONTRACT_COMPLETED 'position' must be an integer in '$projectId'"
            )
        val totalRaw = payload["total"]
            ?: throw LedgerValidationException(
                "CONTRACT_COMPLETED missing 'total' in '$projectId'"
            )
        val total = toInt(totalRaw)
            ?: throw LedgerValidationException(
                "CONTRACT_COMPLETED 'total' must be an integer in '$projectId'"
            )
        if (position > total) {
            throw LedgerValidationException(
                "CONTRACT_COMPLETED 'position' ($position) exceeds 'total' ($total) in '$projectId'"
            )
        }
        // In simulated, the net for this position = started - completed (including candidate).
        // A valid close leaves net == 0: the contract was open (net=1 before candidate) and
        // is now balanced (net=0 after candidate CONTRACT_COMPLETED is applied).
        val net = state.contractNetByPosition[position] ?: -1
        if (net != 0) {
            throw LedgerValidationException(
                "CONTRACT_COMPLETED position $position does not match active contract in '$projectId'"
            )
        }
    }

    private fun checkExecutionCompleted(
        projectId: String,
        payload: Map<String, Any>,
        state: ValidationState
    ) {
        requireKeys(projectId, EventTypes.EXECUTION_COMPLETED, payload, EXECUTION_COMPLETED_KEYS)
        val totalRaw = payload["total"]
            ?: throw LedgerValidationException(
                "EXECUTION_COMPLETED missing 'total' in '$projectId'"
            )
        val total = toInt(totalRaw)
            ?: throw LedgerValidationException(
                "EXECUTION_COMPLETED 'total' must be an integer in '$projectId'"
            )
        if (total < 1) {
            throw LedgerValidationException(
                "EXECUTION_COMPLETED 'total' must be >= 1, got $total in '$projectId'"
            )
        }
        val stateTotal = state.totalContracts
            ?: throw LedgerValidationException(
                "EXECUTION_COMPLETED: no CONTRACT_STARTED found in '$projectId'"
            )
        if (total != stateTotal) {
            throw LedgerValidationException(
                "EXECUTION_COMPLETED 'total' ($total) does not match CONTRACT_STARTED count " +
                    "($stateTotal) in '$projectId'"
            )
        }
        if (state.completedContracts != stateTotal) {
            throw LedgerValidationException(
                "EXECUTION_COMPLETED: expected $stateTotal CONTRACT_COMPLETED, " +
                    "found ${state.completedContracts} in '$projectId'"
            )
        }
    }

    private fun checkIcsStarted(projectId: String, payload: Map<String, Any>) {
        requireKeys(projectId, EventTypes.ICS_STARTED, payload, ICS_STARTED_KEYS)
        payload["report_reference"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException(
                "ICS_STARTED missing or blank 'report_reference' in '$projectId'"
            )
        payload["finalArtifactReference"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException(
                "ICS_STARTED missing or blank 'finalArtifactReference' in '$projectId'"
            )
        payload["taskId"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException(
                "ICS_STARTED missing or blank 'taskId' in '$projectId'"
            )
    }

    private fun checkIcsCompleted(projectId: String, payload: Map<String, Any>) {
        requireKeys(projectId, EventTypes.ICS_COMPLETED, payload, ICS_COMPLETED_KEYS)
        payload["report_reference"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException(
                "ICS_COMPLETED missing or blank 'report_reference' in '$projectId'"
            )
        payload["taskId"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException(
                "ICS_COMPLETED missing or blank 'taskId' in '$projectId'"
            )
        payload["icsOutputReference"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException(
                "ICS_COMPLETED missing or blank 'icsOutputReference' in '$projectId'"
            )
    }

    private fun checkAssemblyStarted(projectId: String, payload: Map<String, Any>) {
        requireKeys(projectId, EventTypes.ASSEMBLY_STARTED, payload, ASSEMBLY_STARTED_KEYS)
        payload["report_reference"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException(
                "ASSEMBLY_STARTED missing or blank 'report_reference' in '$projectId'"
            )
        payload["contractSetId"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException(
                "ASSEMBLY_STARTED missing or blank 'contractSetId' in '$projectId'"
            )
        val totalRaw = payload["totalContracts"]
            ?: throw LedgerValidationException(
                "ASSEMBLY_STARTED missing 'totalContracts' in '$projectId'"
            )
        val total = toInt(totalRaw)
            ?: throw LedgerValidationException(
                "ASSEMBLY_STARTED 'totalContracts' must be an integer in '$projectId'"
            )
        if (total < 1) {
            throw LedgerValidationException(
                "ASSEMBLY_STARTED 'totalContracts' must be >= 1, got $total in '$projectId'"
            )
        }
    }

    private fun checkAssemblyCompleted(projectId: String, payload: Map<String, Any>) {
        requireKeys(projectId, EventTypes.ASSEMBLY_COMPLETED, payload, ASSEMBLY_COMPLETED_KEYS)
        payload["report_reference"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException(
                "ASSEMBLY_COMPLETED missing or blank 'report_reference' in '$projectId'"
            )
        payload["contractSetId"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException(
                "ASSEMBLY_COMPLETED missing or blank 'contractSetId' in '$projectId'"
            )
        val totalRaw = payload["totalContracts"]
            ?: throw LedgerValidationException(
                "ASSEMBLY_COMPLETED missing 'totalContracts' in '$projectId'"
            )
        val total = toInt(totalRaw)
            ?: throw LedgerValidationException(
                "ASSEMBLY_COMPLETED 'totalContracts' must be an integer in '$projectId'"
            )
        if (total < 1) {
            throw LedgerValidationException(
                "ASSEMBLY_COMPLETED 'totalContracts' must be >= 1, got $total in '$projectId'"
            )
        }
        payload["finalArtifactReference"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException(
                "ASSEMBLY_COMPLETED missing or blank 'finalArtifactReference' in '$projectId'"
            )
        payload["taskId"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException(
                "ASSEMBLY_COMPLETED missing or blank 'taskId' in '$projectId'"
            )
        payload["assemblyId"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException(
                "ASSEMBLY_COMPLETED missing or blank 'assemblyId' in '$projectId'"
            )
    }

    private fun checkAssemblyValidated(projectId: String, state: ValidationState) {
        if (!state.hasExecutionCompleted) {
            throw LedgerValidationException(
                "ASSEMBLY_VALIDATED requires EXECUTION_COMPLETED in '$projectId'"
            )
        }
    }

    // ── UCS-1 ingestion lifecycle event checks ────────────────────────────────

    private fun checkContractCreated(projectId: String, payload: Map<String, Any>) {
        requireKeys(projectId, EventTypes.CONTRACT_CREATED, payload, CONTRACT_CREATED_KEYS)
        payload["contractId"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException(
                "CONTRACT_CREATED missing or blank 'contractId' in '$projectId'"
            )
        payload["intentId"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException(
                "CONTRACT_CREATED missing or blank 'intentId' in '$projectId'"
            )
        payload["report_reference"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException(
                "CONTRACT_CREATED missing or blank 'report_reference' (RRIL-1) in '$projectId'"
            )
        payload["contractClass"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException(
                "CONTRACT_CREATED missing or blank 'contractClass' in '$projectId'"
            )
        payload["executionType"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException(
                "CONTRACT_CREATED missing or blank 'executionType' in '$projectId'"
            )
        payload["targetDomain"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException(
                "CONTRACT_CREATED missing or blank 'targetDomain' in '$projectId'"
            )
        checkPositionAndTotal(projectId, EventTypes.CONTRACT_CREATED, payload)
        // requiredCapabilities: must be present, non-empty, all values valid ContractCapability enums
        @Suppress("UNCHECKED_CAST")
        val rawCapabilities = payload["requiredCapabilities"] as? List<*>
            ?: throw LedgerValidationException(
                "CONTRACT_CREATED missing or invalid 'requiredCapabilities' in '$projectId'"
            )
        if (rawCapabilities.isEmpty()) {
            throw LedgerValidationException(
                "CONTRACT_CREATED 'requiredCapabilities' must not be empty in '$projectId'"
            )
        }
        val validNames = com.agoii.mobile.contracts.ContractCapability.entries.map { it.name }.toSet()
        rawCapabilities.forEach { raw ->
            val name = raw?.toString() ?: ""
            if (name !in validNames) {
                throw LedgerValidationException(
                    "CONTRACT_CREATED 'requiredCapabilities' contains unknown value '$name' in '$projectId'"
                )
            }
        }
    }

    private fun checkContractValidatedEvent(projectId: String, payload: Map<String, Any>) {
        requireKeys(projectId, EventTypes.CONTRACT_VALIDATED, payload, CONTRACT_VALIDATED_KEYS)
        payload["contractId"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException(
                "CONTRACT_VALIDATED missing or blank 'contractId' in '$projectId'"
            )
        payload["report_reference"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException(
                "CONTRACT_VALIDATED missing or blank 'report_reference' (RRIL-1) in '$projectId'"
            )
    }

    private fun checkContractApproved(projectId: String, payload: Map<String, Any>) {
        requireKeys(projectId, EventTypes.CONTRACT_APPROVED, payload, CONTRACT_APPROVED_KEYS)
        payload["contractId"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException(
                "CONTRACT_APPROVED missing or blank 'contractId' in '$projectId'"
            )
        payload["report_reference"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException(
                "CONTRACT_APPROVED missing or blank 'report_reference' (RRIL-1) in '$projectId'"
            )
        payload["executionRoute"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException(
                "CONTRACT_APPROVED missing or blank 'executionRoute' in '$projectId'"
            )
    }

    // ── 4. INVARIANTS ─────────────────────────────────────────────────────────

    private fun checkInvariants(
        projectId: String,
        type: String,
        payload: Map<String, Any>,
        state: ValidationState
    ) {
        // Invariant 1 — Sequential Integrity: enforced in checkStructural (single source)

        // Invariant 2 — Single Active Contract: at most 1 open contract in simulated
        if (state.activeContractCount > 1) {
            throw LedgerValidationException(
                "Invariant 2: ${state.activeContractCount} active contracts in '$projectId' (max 1)"
            )
        }

        // Invariant 3 — Validated task cannot re-enter lifecycle.
        // VALIDATED in simulated tasks AND candidateType != TASK_VALIDATED means VALIDATED
        // was contributed by currentEvents (not the candidate itself) → re-entry is blocked.
        if (type in TASK_LIFECYCLE_TYPES) {
            val taskId = payload["taskId"]?.toString()
            if (taskId != null &&
                TaskLifecycle.VALIDATED in (state.tasks[taskId] ?: emptySet()) &&
                type != EventTypes.TASK_VALIDATED
            ) {
                throw LedgerValidationException(
                    "Invariant 3: task '$taskId' is already validated — " +
                        "lifecycle is complete and cannot be re-entered in '$projectId'"
                )
            }
        }

        // Invariant 4 — Contract Coverage: delegated to checkExecutionCompleted (payload layer)
        // Invariant 5 — No Skipped Lifecycle: delegated to checkTransition via LedgerAudit

        // Invariant 6 — Terminal Lock: COMMIT_EXECUTED or COMMIT_ABORTED in simulated and candidate
        // is not itself one of those events means the ledger was already terminal before this candidate.
        if (state.isTerminal && type != EventTypes.COMMIT_EXECUTED && type != EventTypes.COMMIT_ABORTED) {
            throw LedgerValidationException(
                "Invariant 6: terminal state reached (COMMIT_EXECUTED/COMMIT_ABORTED) — " +
                    "no further events are permitted in '$projectId'"
            )
        }
    }

    // ── HELPERS ───────────────────────────────────────────────────────────────

    /**
     * Validates position and total invariants for events that carry both fields.
     *
     * Global rules:
     *  - position MUST be >= 1
     *  - total MUST be >= 1
     *  - position MUST be <= total
     */
    private fun checkPositionAndTotal(projectId: String, type: String, payload: Map<String, Any>) {
        val position = payload["position"]?.let { toInt(it) }
            ?: throw LedgerValidationException(
                "$type missing or invalid 'position' in '$projectId'"
            )
        val total = payload["total"]?.let { toInt(it) }
            ?: throw LedgerValidationException(
                "$type missing or invalid 'total' in '$projectId'"
            )
        if (position < 1) {
            throw LedgerValidationException(
                "$type 'position' must be >= 1, got $position in '$projectId'"
            )
        }
        if (total < 1) {
            throw LedgerValidationException(
                "$type 'total' must be >= 1, got $total in '$projectId'"
            )
        }
        if (position > total) {
            throw LedgerValidationException(
                "$type 'position' ($position) must be <= 'total' ($total) in '$projectId'"
            )
        }
    }

    private fun toInt(value: Any?): Int? = when (value) {
        is Int    -> value
        is Long   -> value.toInt()
        is Double -> if (value % 1.0 == 0.0) value.toInt() else null
        is Float  -> if (value % 1.0f == 0.0f) value.toInt() else null
        is String -> value.toIntOrNull()
        else      -> null
    }

    private fun checkCommitContract(projectId: String, payload: Map<String, Any>) {
        requireKeys(projectId, EventTypes.COMMIT_CONTRACT, payload, COMMIT_CONTRACT_KEYS)
        payload["report_reference"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException(
                "COMMIT_CONTRACT missing or blank 'report_reference' in '$projectId'"
            )
        payload["contractSetId"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException(
                "COMMIT_CONTRACT missing or blank 'contractSetId' in '$projectId'"
            )
        payload["finalArtifactReference"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException(
                "COMMIT_CONTRACT missing or blank 'finalArtifactReference' in '$projectId'"
            )
        payload["approvalStatus"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException(
                "COMMIT_CONTRACT missing or blank 'approvalStatus' in '$projectId'"
            )
    }

    private fun checkCommitResult(projectId: String, payload: Map<String, Any>, type: String) {
        requireKeys(projectId, type, payload, COMMIT_RESULT_KEYS)
        payload["report_reference"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException(
                "$type missing or blank 'report_reference' in '$projectId'"
            )
    }

    companion object {
        private val TASK_LIFECYCLE_TYPES    = setOf(
            EventTypes.TASK_ASSIGNED,
            EventTypes.TASK_STARTED,
            EventTypes.TASK_EXECUTED,
            EventTypes.TASK_COMPLETED,
            EventTypes.TASK_VALIDATED
        )
        private val CONTRACTS_GENERATED_KEYS = setOf(
            "intentId", "contractSetId", "contracts", "total",
            "report_reference",  // RRIL-1: canonical RRID key (standardized from report_id)
            "type"               // CLOSURE-04: "interaction" marks ICS loop contracts
        )
        private val CONTRACT_STARTED_KEYS   = setOf("position", "total", "contract_id")
        private val TASK_ASSIGNED_KEYS      = setOf(
            "taskId",
            "position", "total",
            "contractId", "report_reference", "requirements", "constraints"
        )
        private val TASK_EXECUTED_KEYS      = setOf(
            "taskId", "contractId", "contractorId", "artifactReference",
            "executionStatus", "validationStatus", "validationReasons",
            "report_reference", "position", "total"
        )
        private val RECOVERY_CONTRACT_KEYS  = setOf(
            "contractId", "taskId", "contractType", "executionPosition",
            "report_reference",
            "failureClass", "violationField", "correctionDirective",
            "successCondition", "artifactReference",
            "irs_violation_type"
        )
        private val DELTA_CONTRACT_CREATED_KEYS = setOf(
            "contractId", "violationField", "report_reference", "delta_iteration_count"
        )
        private val TASK_ID_ONLY            = setOf("taskId")
        private val TASK_WITH_POSITION_KEYS = setOf("taskId", "position", "total")
        private val CONTRACT_COMPLETED_KEYS = setOf("position", "total", "contractId", "report_reference")
        private val EXECUTION_COMPLETED_KEYS = setOf("total")
        private val ASSEMBLY_STARTED_KEYS    = setOf("report_reference", "contractSetId", "totalContracts")
        private val ASSEMBLY_COMPLETED_KEYS  = setOf(
            "report_reference", "contractSetId", "totalContracts",
            "finalArtifactReference", "taskId", "assemblyId", "traceMap"
        )
        private val ICS_STARTED_KEYS         = setOf("report_reference", "finalArtifactReference", "taskId")
        private val ICS_COMPLETED_KEYS       = setOf("report_reference", "taskId", "icsOutputReference")
        // UCS-1 ingestion lifecycle event key sets
        private val CONTRACT_CREATED_KEYS    = setOf(
            "contractId", "intentId", "report_reference",
            "contractClass", "executionType", "targetDomain",
            "position", "total", "requiredCapabilities"
        )
        private val CONTRACT_VALIDATED_KEYS  = setOf("contractId", "report_reference")
        private val CONTRACT_APPROVED_KEYS   = setOf("contractId", "report_reference", "executionRoute")
        // Commit contract lifecycle event key sets
        private val COMMIT_CONTRACT_KEYS     = setOf(
            "report_reference", "contractSetId", "finalArtifactReference",
            "proposedActions", "approvalStatus"
        )
        private val COMMIT_RESULT_KEYS       = setOf("report_reference", "approvalStatus")
    }
}
