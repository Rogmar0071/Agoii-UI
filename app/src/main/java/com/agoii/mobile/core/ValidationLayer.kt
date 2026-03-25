package com.agoii.mobile.core

class LedgerValidationException(message: String) : RuntimeException(message)

class ValidationLayer {

    fun validate(
        projectId: String,
        type: String,
        payload: Map<String, Any>,
        currentEvents: List<Event>
    ) {
        checkStructural(projectId, type, currentEvents)
        checkTransition(projectId, type, currentEvents)
        checkPayload(projectId, type, payload, currentEvents)
        checkInvariants(projectId, type, payload, currentEvents)
    }

    // ── 1. STRUCTURAL ─────────────────────────────────────────────────────────

    private fun checkStructural(projectId: String, type: String, currentEvents: List<Event>) {
        if (type !in EventTypes.ALL) {
            throw LedgerValidationException("Unknown event type '$type' for '$projectId'")
        }
        if (currentEvents.isEmpty()) {
            if (type != EventTypes.INTENT_SUBMITTED) {
                throw LedgerValidationException(
                    "First event must be '${EventTypes.INTENT_SUBMITTED}' for '$projectId'"
                )
            }
        } else {
            val expectedLast = (currentEvents.size - 1).toLong()
            if (currentEvents.last().sequenceNumber != expectedLast) {
                throw LedgerValidationException(
                    "Sequence gap in '$projectId': expected last=$expectedLast, " +
                        "got=${currentEvents.last().sequenceNumber}"
                )
            }
        }
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
        currentEvents: List<Event>
    ) {
        checkGlobalPayload(projectId, type, payload)
        when (type) {
            EventTypes.CONTRACT_STARTED    -> checkContractStarted(projectId, payload)
            EventTypes.TASK_ASSIGNED       -> checkTaskAssigned(projectId, payload)
            EventTypes.TASK_STARTED        -> checkTaskStarted(projectId, payload, currentEvents)
            EventTypes.TASK_COMPLETED      -> checkTaskCompleted(projectId, payload, currentEvents)
            EventTypes.TASK_VALIDATED      -> checkTaskValidated(projectId, payload, currentEvents)
            EventTypes.CONTRACT_COMPLETED  -> checkContractCompleted(projectId, payload, currentEvents)
            EventTypes.EXECUTION_COMPLETED -> checkExecutionCompleted(projectId, currentEvents)
            EventTypes.ASSEMBLY_VALIDATED  -> checkAssemblyValidated(projectId, currentEvents)
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
        currentEvents: List<Event>
    ) {
        val taskId = payload["taskId"]?.toString()
            ?: throw LedgerValidationException(
                "TASK_STARTED missing 'taskId' in '$projectId'"
            )
        val assignedIds = currentEvents
            .filter { it.type == EventTypes.TASK_ASSIGNED }
            .mapNotNullTo(HashSet()) { it.payload["taskId"]?.toString() }
        if (taskId !in assignedIds) {
            throw LedgerValidationException(
                "TASK_STARTED: taskId '$taskId' not found in TASK_ASSIGNED events in '$projectId'"
            )
        }
    }

    private fun checkTaskCompleted(
        projectId: String,
        payload: Map<String, Any>,
        currentEvents: List<Event>
    ) {
        val taskId = payload["taskId"]?.toString()
            ?: throw LedgerValidationException(
                "TASK_COMPLETED missing 'taskId' in '$projectId'"
            )
        val startedIds = currentEvents
            .filter { it.type == EventTypes.TASK_STARTED }
            .mapNotNullTo(HashSet()) { it.payload["taskId"]?.toString() }
        if (taskId !in startedIds) {
            throw LedgerValidationException(
                "TASK_COMPLETED: taskId '$taskId' not found in TASK_STARTED events in '$projectId'"
            )
        }
    }

    private fun checkTaskValidated(
        projectId: String,
        payload: Map<String, Any>,
        currentEvents: List<Event>
    ) {
        val taskId = payload["taskId"]?.toString()
            ?: throw LedgerValidationException(
                "TASK_VALIDATED missing 'taskId' in '$projectId'"
            )
        val completedIds = currentEvents
            .filter { it.type == EventTypes.TASK_COMPLETED }
            .mapNotNullTo(HashSet()) { it.payload["taskId"]?.toString() }
        if (taskId !in completedIds) {
            throw LedgerValidationException(
                "TASK_VALIDATED: taskId '$taskId' not found in TASK_COMPLETED events in '$projectId'"
            )
        }
    }

    private fun checkContractCompleted(
        projectId: String,
        payload: Map<String, Any>,
        currentEvents: List<Event>
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
        // Must match the currently active (unmatched) contract.
        // Single-pass frequency map correctly handles any duplicate positions that may exist
        // in a corrupted ledger before Invariant 2 runs later in the pipeline.
        val activeFrequency = mutableMapOf<Int, Int>()
        for (ev in currentEvents) {
            val pos = ev.payload["position"]?.let { toInt(it) } ?: continue
            when (ev.type) {
                EventTypes.CONTRACT_STARTED   ->
                    activeFrequency[pos] = (activeFrequency[pos] ?: 0) + 1
                EventTypes.CONTRACT_COMPLETED -> {
                    val count = activeFrequency[pos] ?: 0
                    if (count > 0) activeFrequency[pos] = count - 1
                }
            }
        }
        if ((activeFrequency[position] ?: 0) == 0) {
            throw LedgerValidationException(
                "CONTRACT_COMPLETED position $position does not match active contract in '$projectId'"
            )
        }
    }

    private fun checkExecutionCompleted(projectId: String, currentEvents: List<Event>) {
        val firstContractStarted = currentEvents.firstOrNull { it.type == EventTypes.CONTRACT_STARTED }
            ?: throw LedgerValidationException(
                "EXECUTION_COMPLETED: no CONTRACT_STARTED found in '$projectId'"
            )
        val total = firstContractStarted.payload["total"]?.let { toInt(it) }
            ?: throw LedgerValidationException(
                "EXECUTION_COMPLETED: CONTRACT_STARTED has no valid 'total' in '$projectId'"
            )
        val completedCount = currentEvents.count { it.type == EventTypes.CONTRACT_COMPLETED }
        if (completedCount != total) {
            throw LedgerValidationException(
                "EXECUTION_COMPLETED: expected $total CONTRACT_COMPLETED, " +
                    "found $completedCount in '$projectId'"
            )
        }
    }

    private fun checkAssemblyValidated(projectId: String, currentEvents: List<Event>) {
        if (currentEvents.none { it.type == EventTypes.EXECUTION_COMPLETED }) {
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
        currentEvents: List<Event>
    ) {
        checkInv1SequentialIntegrity(projectId, currentEvents)
        checkInv2SingleActiveContract(projectId, type, currentEvents)
        checkInv3TaskLifecycle(projectId, type, payload, currentEvents)
        // Invariant 4 (all contracts completed at EXECUTION_COMPLETED) is enforced by checkExecutionCompleted
        // Invariant 5 (no skipped lifecycle) is enforced by checkTransition via LedgerAudit
        checkInv6TerminalLock(projectId, currentEvents)
    }

    // Invariant 1: ∀ i: ledger[i].sequence == i
    // checkStructural already validates the last element (O(1) fast-fail); this full O(n)
    // scan is required by the contract to detect gaps anywhere inside the existing ledger.
    private fun checkInv1SequentialIntegrity(projectId: String, currentEvents: List<Event>) {
        for ((index, event) in currentEvents.withIndex()) {
            if (event.sequenceNumber != index.toLong()) {
                throw LedgerValidationException(
                    "Invariant 1: sequence violation in '$projectId' at index $index: " +
                        "expected=$index actual=${event.sequenceNumber}"
                )
            }
        }
    }

    // Invariant 2: Only one CONTRACT_STARTED without a paired CONTRACT_COMPLETED
    private fun checkInv2SingleActiveContract(
        projectId: String,
        type: String,
        currentEvents: List<Event>
    ) {
        val started   = currentEvents.count { it.type == EventTypes.CONTRACT_STARTED }
        val completed = currentEvents.count { it.type == EventTypes.CONTRACT_COMPLETED }
        val active    = started - completed
        if (active > 1) {
            throw LedgerValidationException(
                "Invariant 2: $active active contracts detected in '$projectId' (max 1 allowed)"
            )
        }
        if (type == EventTypes.CONTRACT_STARTED && active > 0) {
            // active > 0 guarantees at least one CONTRACT_STARTED exists; lastOrNull is defensive
            val openPosition = currentEvents
                .lastOrNull { it.type == EventTypes.CONTRACT_STARTED }
                ?.payload?.get("position")
            throw LedgerValidationException(
                "Invariant 2: CONTRACT_STARTED rejected — contract at position " +
                    "$openPosition is still open in '$projectId'"
            )
        }
    }

    // Invariant 3: A task that has been VALIDATED cannot be re-entered.
    // The filter runs only for the 4 task-lifecycle event types, keeping the hot path O(n).
    private fun checkInv3TaskLifecycle(
        projectId: String,
        type: String,
        payload: Map<String, Any>,
        currentEvents: List<Event>
    ) {
        if (type !in setOf(
                EventTypes.TASK_ASSIGNED, EventTypes.TASK_STARTED,
                EventTypes.TASK_COMPLETED, EventTypes.TASK_VALIDATED
            )
        ) return
        val taskId = payload["taskId"]?.toString() ?: return
        val validatedIds = currentEvents
            .filter { it.type == EventTypes.TASK_VALIDATED }
            .mapNotNullTo(HashSet()) { it.payload["taskId"]?.toString() }
        if (taskId in validatedIds) {
            throw LedgerValidationException(
                "Invariant 3: task '$taskId' is already validated — " +
                    "lifecycle is complete and cannot be re-entered in '$projectId'"
            )
        }
    }

    // Invariant 6: After ASSEMBLY_COMPLETED, no further events are permitted
    private fun checkInv6TerminalLock(projectId: String, currentEvents: List<Event>) {
        if (currentEvents.any { it.type == EventTypes.ASSEMBLY_COMPLETED }) {
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
}
