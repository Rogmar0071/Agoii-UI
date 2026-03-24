package com.agoii.mobile.governance

import com.agoii.mobile.core.EventRepository
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.core.TransitionLaw

// ─── GovernanceGate — Single Write Authority ──────────────────────────────────

/**
 * GovernanceGate — the sole path through which any event may be appended to the ledger.
 *
 * Rules:
 *  - ALL event writes MUST pass through [appendEvent]; no caller may bypass this gate.
 *  - The gate enforces [TransitionLaw] before writing — illegal transitions are rejected.
 *  - The Governor is the sole decision authority; it checks module adapters BEFORE calling
 *    [appendEvent], never after.
 *  - [MODULE_ENFORCEMENT_MAP] is the deterministic declaration of which module adapter
 *    the Governor MUST consult before issuing each guarded event type.
 *  - The gate is stateless; it never caches or mutates module state.
 */
class GovernanceGate(private val eventStore: EventRepository) {

    companion object {
        /**
         * Deterministic event-to-module enforcement map.
         *
         * For each event type listed here, the Governor MUST query the corresponding
         * module adapter's [ModuleState.getStateSignature] and verify the required
         * structural conditions before calling [appendEvent].
         *
         * This map is declarative and static — it is never evaluated at runtime by the gate.
         * It is the canonical record of which module governs each guarded event.
         */
        val MODULE_ENFORCEMENT_MAP: Map<String, String> = mapOf(
            EventTypes.CONTRACT_STARTED   to "ContractIssuanceAdapter",
            EventTypes.TASK_ASSIGNED      to "ContractorModuleAdapter",
            EventTypes.ASSEMBLY_VALIDATED to "AssemblyModuleAdapter"
        )
    }

    /**
     * Appends [type]/[payload] to [projectId]'s ledger after verifying the transition
     * is permitted by [TransitionLaw].
     *
     * The Governor must have already verified all required module states via
     * [MODULE_ENFORCEMENT_MAP] before calling this method.
     *
     * @param projectId The project ledger to write to.
     * @param type      The event type string.
     * @param payload   The event payload.
     * @throws IllegalStateException if the transition from the current last event to [type]
     *         is not permitted by [TransitionLaw].
     */
    fun appendEvent(projectId: String, type: String, payload: Map<String, Any>) {
        val last = eventStore.loadEvents(projectId).lastOrNull()
        check(last == null || TransitionLaw.isAllowed(last.type, type)) {
            "TransitionLaw violation: '${last?.type}' → '$type' is not a permitted transition"
        }
        eventStore.appendEvent(projectId, type, payload)
    }
}
