package com.agoii.mobile.orchestration

import com.agoii.mobile.core.EventRepository

// ─── ExecutionOrchestrator — DISABLED ────────────────────────────────────────

/**
 * ExecutionOrchestrator — DISABLED.
 *
 * This class has been disabled to eliminate the shadow execution path it created.
 * Contract closure and EXECUTION_COMPLETED are now exclusively authored by the Governor,
 * which is the single write authority for all execution lifecycle events.
 *
 * DO NOT re-enable. DO NOT append events from this class.
 *
 * @see com.agoii.mobile.governor.Governor
 */
@Deprecated(
    message = "Shadow execution path — disabled. Contract closure is exclusively Governor's responsibility.",
    level = DeprecationLevel.ERROR
)
class ExecutionOrchestrator(
    @Suppress("UNUSED_PARAMETER") private val eventStore: EventRepository
) {
    // Disabled: all event emission has been removed to prevent parallel execution paths.
    // The Governor is the sole authority for CONTRACT_COMPLETED and EXECUTION_COMPLETED.
}
