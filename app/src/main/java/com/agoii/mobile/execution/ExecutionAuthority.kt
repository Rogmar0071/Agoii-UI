// ONLY showing modified patterns — rest of your file remains EXACTLY the same

// =========================
// 🔧 CONVERGENCE AUTHORITY
// =========================

companion object {
    /**
     * Maximum number of DELTA_CONTRACT_CREATED attempts allowed
     * for a single taskId before the system is declared non-convergent.
     *
     * This is the single source of truth for convergence control.
     */
    const val MAX_DELTA: Int = 3
}

// =========================
// 🔧 GLOBAL LAMBDA FIXES
// =========================

// BEFORE:
// events.any {
//     it.type == EventTypes.X && it.payload["y"] == z
// }

// AFTER (APPLY THIS EVERYWHERE):

events.any { event ->
    event.type == EventTypes.X &&
    event.payload["y"] == z
}

// -------------------------

// BEFORE:
events.count {
    it.type == EventTypes.X &&
    it.payload["taskId"] == taskId
}

// AFTER:

events.count { event ->
    event.type == EventTypes.X &&
    event.payload["taskId"] == taskId
}

// -------------------------

// BEFORE:
events.lastOrNull {
    it.type == EventTypes.TASK_ASSIGNED &&
    it.payload["taskId"] == taskId
}

// AFTER:

events.lastOrNull { event ->
    event.type == EventTypes.TASK_ASSIGNED &&
    event.payload["taskId"] == taskId
}

// -------------------------

// BEFORE:
events.firstOrNull {
    it.type == EventTypes.CONTRACT_CREATED &&
    it.payload["contractId"]?.toString() == contractId
}

// AFTER:

events.firstOrNull { event ->
    event.type == EventTypes.CONTRACT_CREATED &&
    event.payload["contractId"]?.toString() == contractId
}

// -------------------------

// BEFORE:
val recoveryAlreadyExists = events.any {
    it.type == EventTypes.RECOVERY_CONTRACT &&
    it.payload["recoveryId"] == recoveryId
}

// AFTER:

val recoveryAlreadyExists = events.any { event ->
    event.type == EventTypes.RECOVERY_CONTRACT &&
    event.payload["recoveryId"] == recoveryId
}

// -------------------------

// BEFORE (payload leak risk):
val x = payload["something"]

// AFTER (always scoped):
val x = event.payload["something"]

// =========================
// 🔧 CRITICAL BLOCK (ICS)
// =========================

val icsAlreadyCompleted = events.any { event ->
    event.type == EventTypes.ICS_COMPLETED &&
    event.payload["report_reference"]?.toString() == reportReference
}

// =========================
// 🔧 PRIOR SUCCESS CHECK
// =========================

val priorSuccessful = events.any { event ->
    event.type == EventTypes.TASK_EXECUTED &&
    event.payload["taskId"] == taskId &&
    event.payload["executionStatus"] == ExecutionStatus.SUCCESS.name
}

// =========================
// 🔧 PRIOR FAILURE COUNT
// =========================

val priorFailedAttempts = events.count { event ->
    event.type == EventTypes.TASK_EXECUTED &&
    event.payload["taskId"] == taskId &&
    event.payload["executionStatus"] != ExecutionStatus.SUCCESS.name
}

// =========================
// 🔧 TASK ASSIGNED LOOKUP
// =========================

val taskAssignedEvent = events.lastOrNull { event ->
    event.type == EventTypes.TASK_ASSIGNED &&
    event.payload["taskId"] == taskId
}

// =========================
// 🔧 CONTRACT LOOKUPS
// =========================

val contractCreated = events.firstOrNull { event ->
    event.type == EventTypes.CONTRACT_CREATED &&
    event.payload["contractId"]?.toString() == contractId
}

// =========================
// 🔧 DELTA COUNT
// =========================

val deltaIterationCount = events.count { event ->
    event.type == EventTypes.DELTA_CONTRACT_CREATED
}

// =========================
// 🔧 VALIDATION CHECK
// =========================

if (violations.any { v -> v.fieldPath == deltaContext.violationField }) {
    // unchanged logic
}

// =========================
// 🔧 NON-CONVERGENCE CHECK
// =========================

return violations.any { v ->
    v.fieldPath == deltaContext.violationField
}
