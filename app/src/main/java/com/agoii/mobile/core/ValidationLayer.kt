package com.agoii.mobile.core

class LedgerValidationException(message: String) : RuntimeException(message)

class ValidationLayer {

    // ── Internal lifecycle enum ────────────────────────────────────────────────

    private enum class TaskLifecycle { ASSIGNED, STARTED, COMPLETED, VALIDATED }

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

            // Terminal detection — ASSEMBLY_COMPLETED anywhere in simulated
            if (ev.type == EventTypes.ASSEMBLY_COMPLETED) isTerminal = true

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
            EventTypes.CONTRACT_STARTED    -> checkContractStarted(projectId, payload)
            EventTypes.TASK_ASSIGNED       -> checkTaskAssigned(projectId, payload)
            EventTypes.TASK_STARTED        -> checkTaskStarted(projectId, payload, state)
            EventTypes.TASK_COMPLETED      -> checkTaskCompleted(projectId, payload, state)
            EventTypes.TASK_VALIDATED      -> checkTaskValidated(projectId, payload, state)
            EventTypes.CONTRACT_COMPLETED  -> checkContractCompleted(projectId, payload, state)
            EventTypes.EXECUTION_COMPLETED -> checkExecutionCompleted(projectId, state)
            EventTypes.ASSEMBLY_VALIDATED  -> checkAssemblyValidated(projectId, state)
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
        payload["contractorId"]
            ?: throw LedgerValidationException(
                "TASK_ASSIGNED missing 'contractorId' in '$projectId'"
            )
        payload["taskId"]
            ?: throw LedgerValidationException(
                "TASK_ASSIGNED missing 'taskId' in '$projectId'"
            )
    }

    private fun checkTaskStarted(
        projectId: String,
        payload: Map<String, Any>,
        state: ValidationState
    ) {
        requireKeys(projectId, EventTypes.TASK_STARTED, payload, TASK_ID_ONLY)
        val taskId = payload["taskId"]?.toString()
            ?: throw LedgerValidationException(
                "TASK_STARTED missing 'taskId' in '$projectId'"
            )
        if (TaskLifecycle.ASSIGNED !in (state.tasks[taskId] ?: emptySet())) {
            throw LedgerValidationException(
                "TASK_STARTED: taskId '$taskId' not found in TASK_ASSIGNED events in '$projectId'"
            )
        }
    }

    private fun checkTaskCompleted(
        projectId: String,
        payload: Map<String, Any>,
        state: ValidationState
    ) {
        requireKeys(projectId, EventTypes.TASK_COMPLETED, payload, TASK_ID_ONLY)
        val taskId = payload["taskId"]?.toString()
            ?: throw LedgerValidationException(
                "TASK_COMPLETED missing 'taskId' in '$projectId'"
            )
        if (TaskLifecycle.STARTED !in (state.tasks[taskId] ?: emptySet())) {
            throw LedgerValidationException(
                "TASK_COMPLETED: taskId '$taskId' not found in TASK_STARTED events in '$projectId'"
            )
        }
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

    private fun checkExecutionCompleted(projectId: String, state: ValidationState) {
        val total = state.totalContracts
            ?: throw LedgerValidationException(
                "EXECUTION_COMPLETED: no CONTRACT_STARTED found in '$projectId'"
            )
        if (state.completedContracts != total) {
            throw LedgerValidationException(
                "EXECUTION_COMPLETED: expected $total CONTRACT_COMPLETED, " +
                    "found ${state.completedContracts} in '$projectId'"
            )
        }
    }

    private fun checkAssemblyValidated(projectId: String, state: ValidationState) {
        if (!state.hasExecutionCompleted) {
            throw LedgerValidationException(
                "ASSEMBLY_VALIDATED requires EXECUTION_COMPLETED in '$projectId'"
            )
        }
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

        // Invariant 6 — Terminal Lock: ASSEMBLY_COMPLETED in simulated and candidate is not itself
        // ASSEMBLY_COMPLETED means the ledger was already terminal before this candidate arrived.
        if (state.isTerminal && type != EventTypes.ASSEMBLY_COMPLETED) {
            throw LedgerValidationException(
                "Invariant 6: terminal state reached (ASSEMBLY_COMPLETED) — " +
                    "no further events are permitted in '$projectId'"
            )
        }
    }

    // ── HELPERS ───────────────────────────────────────────────────────────────

    private fun toInt(value: Any?): Int? = when (value) {
        is Int    -> value
        is Long   -> value.toInt()
        is Double -> if (value % 1.0 == 0.0) value.toInt() else null
        is Float  -> if (value % 1.0f == 0.0f) value.toInt() else null
        is String -> value.toIntOrNull()
        else      -> null
    }

    companion object {
        private val TASK_LIFECYCLE_TYPES    = setOf(
            EventTypes.TASK_ASSIGNED,
            EventTypes.TASK_STARTED,
            EventTypes.TASK_COMPLETED,
            EventTypes.TASK_VALIDATED
        )
        private val CONTRACT_STARTED_KEYS   = setOf("position", "total", "contract_id")
        // position and total are included for traceability (emitted by Governor with CONTRACT_STARTED context).
        private val TASK_ASSIGNED_KEYS      = setOf("contractorId", "taskId", "position", "total")
        private val TASK_ID_ONLY            = setOf("taskId")
        private val CONTRACT_COMPLETED_KEYS = setOf("position", "total")
    }
}
