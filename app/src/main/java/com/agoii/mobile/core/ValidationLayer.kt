package com.agoii.mobile.core

class LedgerValidationException(message: String) : RuntimeException(message)

class ValidationLayer {

    // ── Internal lifecycle enum ────────────────────────────────────────────────

    private enum class TaskLifecycle { ASSIGNED, STARTED, COMPLETED, VALIDATED }

    // ── Derived validation state (produced by a single pass over simulated) ───

    private data class ValidationState(
        /** Index of the first sequence-number violation in simulated; -1 if none. */
        val firstSeqViolationAt: Int,
        /** True when ASSEMBLY_COMPLETED already exists in currentEvents (pre-candidate). */
        val terminalLocked: Boolean,
        /** Total contracts declared by the first CONTRACT_STARTED, or null if none seen. */
        val totalContracts: Int?,
        /** Number of CONTRACT_COMPLETED events in simulated. */
        val completedContracts: Int,
        /** Number of open (started but not yet completed) contracts in simulated. */
        val activeContractCount: Int,
        /** Position of the open contract in simulated; null when none is open. */
        val activeContractPosition: Int?,
        /** Position of the open contract BEFORE the candidate was applied; null if none. */
        val priorActiveContractPosition: Int?,
        /** True when EXECUTION_COMPLETED is present in simulated. */
        val hasExecutionCompleted: Boolean,
        /** Highest task lifecycle level per taskId derived from currentEvents only. */
        val priorTasks: Map<String, TaskLifecycle>,
        /** Highest task lifecycle level per taskId derived from the full simulated ledger. */
        val simulatedTasks: Map<String, TaskLifecycle>
    )

    // ── Public entry point ────────────────────────────────────────────────────

    fun validate(
        projectId: String,
        type: String,
        payload: Map<String, Any>,
        currentEvents: List<Event>
    ) {
        // Phase 0 — type must be known before the candidate can be safely constructed
        if (type !in EventTypes.ALL) {
            throw LedgerValidationException("Unknown event type '$type' for '$projectId'")
        }

        // Phase 1 — structural: first-event rule (cannot be derived from simulated)
        if (currentEvents.isEmpty() && type != EventTypes.INTENT_SUBMITTED) {
            throw LedgerValidationException(
                "First event must be '${EventTypes.INTENT_SUBMITTED}' for '$projectId'"
            )
        }

        // Phase 2 — construct simulated ledger and derive all shared state in one pass
        val candidateSeq = if (currentEvents.isEmpty()) 0L
                           else currentEvents.last().sequenceNumber + 1L
        val simulated = currentEvents + Event(
            type = type,
            payload = payload,
            sequenceNumber = candidateSeq
        )
        val state = deriveState(simulated)

        // Phase 3 — transition check (reads only currentEvents.last(), no rescan)
        checkTransition(projectId, type, currentEvents)

        // Phase 4 — payload check (reads state, no ledger rescan)
        checkPayload(projectId, type, payload, state)

        // Phase 5 — invariants (reads state, no ledger rescan)
        checkInvariants(projectId, type, payload, state)
    }

    // ── Single-pass state derivation ──────────────────────────────────────────

    private fun deriveState(simulated: List<Event>): ValidationState {
        var firstSeqViolationAt = -1
        var terminalLocked = false
        var totalContracts: Int? = null
        val activeFreq = mutableMapOf<Int, Int>()
        var completedContracts = 0
        var hasExecutionCompleted = false
        val taskState = mutableMapOf<String, TaskLifecycle>()

        // Snapshots captured just before the candidate (last event) is processed
        var priorTasks: Map<String, TaskLifecycle> = emptyMap()
        var priorActiveContractPosition: Int? = null

        for (i in simulated.indices) {
            val ev = simulated[i]
            val isCandidate = i == simulated.size - 1

            // Snapshot state before the candidate is applied
            if (isCandidate) {
                priorTasks = taskState.toMap()
                priorActiveContractPosition = activeFreq.entries.firstOrNull { it.value > 0 }?.key
            }

            // Invariant 1: sequence continuity across simulated
            if (firstSeqViolationAt == -1 && ev.sequenceNumber != i.toLong()) {
                firstSeqViolationAt = i
            }

            // Invariant 6: terminal lock triggered by ASSEMBLY_COMPLETED in currentEvents
            if (!isCandidate && ev.type == EventTypes.ASSEMBLY_COMPLETED) {
                terminalLocked = true
            }

            // Contract tracking
            when (ev.type) {
                EventTypes.CONTRACT_STARTED -> {
                    val pos   = ev.payload["position"]?.let { toInt(it) }
                    val total = ev.payload["total"]?.let { toInt(it) }
                    if (totalContracts == null && total != null) totalContracts = total
                    if (pos != null) activeFreq[pos] = (activeFreq[pos] ?: 0) + 1
                }
                EventTypes.CONTRACT_COMPLETED -> {
                    completedContracts++
                    val pos = ev.payload["position"]?.let { toInt(it) }
                    if (pos != null) {
                        val cnt = activeFreq[pos] ?: 0
                        if (cnt > 0) activeFreq[pos] = cnt - 1
                    }
                }
                EventTypes.EXECUTION_COMPLETED -> hasExecutionCompleted = true
            }

            // Task lifecycle tracking — highest level reached per task
            val taskId = ev.payload["taskId"]?.toString()
            if (taskId != null) {
                val level: TaskLifecycle? = when (ev.type) {
                    EventTypes.TASK_ASSIGNED  -> TaskLifecycle.ASSIGNED
                    EventTypes.TASK_STARTED   -> TaskLifecycle.STARTED
                    EventTypes.TASK_COMPLETED -> TaskLifecycle.COMPLETED
                    EventTypes.TASK_VALIDATED -> TaskLifecycle.VALIDATED
                    else                       -> null
                }
                if (level != null) {
                    val existing = taskState[taskId]
                    if (existing == null || level.ordinal > existing.ordinal) {
                        taskState[taskId] = level
                    }
                }
            }
        }

        return ValidationState(
            firstSeqViolationAt      = firstSeqViolationAt,
            terminalLocked           = terminalLocked,
            totalContracts           = totalContracts,
            completedContracts       = completedContracts,
            activeContractCount      = activeFreq.values.sum(),
            activeContractPosition   = activeFreq.entries.firstOrNull { it.value > 0 }?.key,
            priorActiveContractPosition = priorActiveContractPosition,
            hasExecutionCompleted    = hasExecutionCompleted,
            priorTasks               = priorTasks,
            simulatedTasks           = taskState.toMap()
        )
    }

    // ── 2. TRANSITION ─────────────────────────────────────────────────────────

    private fun checkTransition(projectId: String, type: String, currentEvents: List<Event>) {
        if (currentEvents.isEmpty()) return
        val lastType = currentEvents.last().type
        if (!LedgerAudit.isLegalTransition(lastType, type)) {
            throw LedgerValidationException(
                "Illegal transition '$lastType' → '$type' in '$projectId'"
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

    private fun checkContractStarted(projectId: String, payload: Map<String, Any>) {
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
        val taskId = payload["taskId"]?.toString()
            ?: throw LedgerValidationException(
                "TASK_STARTED missing 'taskId' in '$projectId'"
            )
        if (state.priorTasks[taskId] == null) {
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
        val taskId = payload["taskId"]?.toString()
            ?: throw LedgerValidationException(
                "TASK_COMPLETED missing 'taskId' in '$projectId'"
            )
        val priorLevel = state.priorTasks[taskId]
        if (priorLevel == null || priorLevel.ordinal < TaskLifecycle.STARTED.ordinal) {
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
        val taskId = payload["taskId"]?.toString()
            ?: throw LedgerValidationException(
                "TASK_VALIDATED missing 'taskId' in '$projectId'"
            )
        val priorLevel = state.priorTasks[taskId]
        if (priorLevel == null || priorLevel.ordinal < TaskLifecycle.COMPLETED.ordinal) {
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
        // Must close the contract that was open before the candidate was applied
        if (position != state.priorActiveContractPosition) {
            throw LedgerValidationException(
                "CONTRACT_COMPLETED position $position does not match active contract " +
                    "(active=${state.priorActiveContractPosition}) in '$projectId'"
            )
        }
    }

    private fun checkExecutionCompleted(projectId: String, state: ValidationState) {
        val total = state.totalContracts
            ?: throw LedgerValidationException(
                "EXECUTION_COMPLETED: no CONTRACT_STARTED found in '$projectId'"
            )
        // completedContracts is from simulated; candidate is EXECUTION_COMPLETED (not
        // CONTRACT_COMPLETED), so this equals the count in currentEvents exactly.
        if (state.completedContracts != total) {
            throw LedgerValidationException(
                "EXECUTION_COMPLETED: expected $total CONTRACT_COMPLETED, " +
                    "found ${state.completedContracts} in '$projectId'"
            )
        }
    }

    private fun checkAssemblyValidated(projectId: String, state: ValidationState) {
        // hasExecutionCompleted is derived from simulated; candidate is ASSEMBLY_VALIDATED,
        // so this flag is true only if EXECUTION_COMPLETED is already in currentEvents.
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
        // Invariant 1 — Sequential Integrity: ∀ i in simulated: seq[i] == i
        if (state.firstSeqViolationAt >= 0) {
            throw LedgerValidationException(
                "Invariant 1: sequence violation in '$projectId' at index ${state.firstSeqViolationAt}"
            )
        }

        // Invariant 2 — Single Active Contract: at most 1 open contract in simulated
        if (state.activeContractCount > 1) {
            throw LedgerValidationException(
                "Invariant 2: ${state.activeContractCount} active contracts in '$projectId' (max 1)"
            )
        }

        // Invariant 3 — Task Lifecycle Completeness: validated task cannot re-enter
        if (type in TASK_LIFECYCLE_TYPES) {
            val taskId = payload["taskId"]?.toString()
            if (taskId != null && state.priorTasks[taskId] == TaskLifecycle.VALIDATED) {
                throw LedgerValidationException(
                    "Invariant 3: task '$taskId' is already validated — " +
                        "lifecycle is complete and cannot be re-entered in '$projectId'"
                )
            }
        }

        // Invariant 4 — Contract Coverage: delegated to checkExecutionCompleted (payload layer)
        // Invariant 5 — No Skipped Lifecycle: delegated to checkTransition via LedgerAudit

        // Invariant 6 — Terminal Lock: no event after ASSEMBLY_COMPLETED
        if (state.terminalLocked) {
            throw LedgerValidationException(
                "Invariant 6: terminal state reached (ASSEMBLY_COMPLETED) — " +
                    "no further events are permitted in '$projectId'"
            )
        }
    }

    // ── HELPERS ───────────────────────────────────────────────────────────────

    private fun toInt(value: Any): Int? = when (value) {
        is Int    -> value
        is Long   -> value.toInt()
        is Double -> if (value % 1.0 == 0.0) value.toInt() else null
        is Float  -> if (value % 1.0f == 0.0f) value.toInt() else null
        is String -> value.toIntOrNull()
        else      -> null
    }

    companion object {
        private val TASK_LIFECYCLE_TYPES = setOf(
            EventTypes.TASK_ASSIGNED,
            EventTypes.TASK_STARTED,
            EventTypes.TASK_COMPLETED,
            EventTypes.TASK_VALIDATED
        )
    }
}
