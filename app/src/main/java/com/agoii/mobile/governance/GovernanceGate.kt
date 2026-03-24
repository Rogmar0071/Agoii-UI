package com.agoii.mobile.governance

import com.agoii.mobile.core.EventRepository

// ─── GovernanceGate — Single Write Authority ──────────────────────────────────

/**
 * GovernanceGate — the sole path through which any event may be appended to the ledger.
 *
 * Rules:
 *  - ALL event writes MUST pass through [appendEvent]; no caller may bypass this gate.
 *  - The gate evaluates every [ModuleState] in [requiredStates] before writing.
 *  - If ANY required state reports [ModuleState.isValidationComplete] == false,
 *    the write is blocked and [appendEvent] returns false without touching the ledger.
 *  - When [requiredStates] is empty the gate always allows the write.
 *  - The gate is stateless; it never caches or mutates module state.
 */
class GovernanceGate(private val eventStore: EventRepository) {

    /**
     * Attempts to append [type]/[payload] to [projectId]'s ledger.
     *
     * @param projectId     The project ledger to write to.
     * @param type          The event type string.
     * @param payload       The event payload.
     * @param requiredStates Module states that must ALL be validation-complete for the
     *                      write to proceed.
     * @return true when the event was appended; false when blocked by a failing state.
     */
    fun appendEvent(
        projectId: String,
        type: String,
        payload: Map<String, Any>,
        requiredStates: List<ModuleState> = emptyList()
    ): Boolean {
        val errors = requiredStates
            .filter { !it.isValidationComplete() }
            .flatMap { it.getValidationErrors() }
        if (errors.isNotEmpty()) return false
        eventStore.appendEvent(projectId, type, payload)
        return true
    }
}
