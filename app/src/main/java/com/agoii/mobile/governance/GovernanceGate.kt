package com.agoii.mobile.governance

import com.agoii.mobile.core.EventRepository
import com.agoii.mobile.core.EventTypes

/**
 * GovernanceGate — the single write authority for Governor-driven events.
 *
 * All event writes from Governor MUST go through this gate.
 * TransitionLaw (nested below) enforces that each Governor-owned event
 * is written at most once per project, preventing illegal duplicates.
 *
 * Governor MUST NOT access the EventRepository directly.
 */
class GovernanceGate(private val eventStore: EventRepository) {

    /**
     * TransitionLaw — the sole authority for legal Governor event transitions.
     *
     * Each event in [SINGLETON_EVENTS] may appear at most once in the ledger.
     * appendEvent is a no-op when the transition is not permitted.
     * Governor MUST NOT check transitions; this law handles all enforcement.
     */
    private object TransitionLaw {

        private val SINGLETON_EVENTS: Set<String> = setOf(
            EventTypes.CONTRACTS_GENERATED,
            EventTypes.EXECUTION_STARTED,
            EventTypes.ASSEMBLY_STARTED,
            EventTypes.ASSEMBLY_COMPLETED
        )

        fun isAllowed(existingTypes: Set<String>, type: String): Boolean =
            type !in SINGLETON_EVENTS || type !in existingTypes
    }

    /**
     * Appends [type] to the ledger for [projectId] with [payload] if TransitionLaw permits.
     * Silently discards writes that violate the transition law.
     */
    fun appendEvent(projectId: String, type: String, payload: Map<String, Any>) {
        val existingTypes = eventStore.loadEvents(projectId).mapTo(mutableSetOf()) { it.type }
        if (TransitionLaw.isAllowed(existingTypes, type)) {
            eventStore.appendEvent(projectId, type, payload)
        }
    }
}
