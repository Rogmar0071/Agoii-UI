package com.agoii.mobile.core

/**
 * TransitionLaw — the single, absolute source of legal event-to-event transitions.
 *
 * All event writes through [com.agoii.mobile.governance.GovernanceGate] are checked
 * against this law before being committed to the ledger.
 *
 * Rules:
 *  - [ALLOWED] is frozen: no runtime mutation is permitted.
 *  - If a transition is not listed, it is illegal.
 *  - The first event (empty ledger) must be [EventTypes.INTENT_SUBMITTED].
 *  - Terminal states ([EventTypes.ASSEMBLY_COMPLETED], [EventTypes.CONTRACT_FAILED])
 *    map to an empty set — nothing may follow them.
 */
object TransitionLaw {

    /**
     * Complete set of legal event-to-event transitions.
     *
     * Key   = "from" event type (the current last ledger event).
     * Value = set of event types that are legally allowed to follow.
     */
    val ALLOWED: Map<String, Set<String>> = mapOf(
        EventTypes.INTENT_SUBMITTED      to setOf(EventTypes.CONTRACTS_GENERATED),
        EventTypes.CONTRACTS_GENERATED   to setOf(EventTypes.CONTRACTS_READY),
        EventTypes.CONTRACTS_READY       to setOf(EventTypes.CONTRACTS_APPROVED),
        EventTypes.CONTRACTS_APPROVED    to setOf(EventTypes.EXECUTION_STARTED),
        EventTypes.EXECUTION_STARTED     to setOf(EventTypes.CONTRACT_STARTED),
        EventTypes.CONTRACT_STARTED      to setOf(EventTypes.TASK_ASSIGNED, EventTypes.CONTRACT_COMPLETED),
        EventTypes.TASK_ASSIGNED         to setOf(EventTypes.TASK_STARTED),
        EventTypes.TASK_STARTED          to setOf(EventTypes.TASK_COMPLETED, EventTypes.TASK_FAILED),
        EventTypes.TASK_COMPLETED        to setOf(EventTypes.TASK_VALIDATED, EventTypes.TASK_FAILED),
        EventTypes.TASK_VALIDATED        to setOf(EventTypes.CONTRACT_COMPLETED),
        EventTypes.TASK_FAILED           to setOf(
            EventTypes.TASK_ASSIGNED,
            EventTypes.CONTRACTOR_REASSIGNED,
            EventTypes.CONTRACT_FAILED
        ),
        EventTypes.CONTRACTOR_REASSIGNED to setOf(EventTypes.TASK_ASSIGNED),
        EventTypes.CONTRACT_COMPLETED    to setOf(EventTypes.CONTRACT_STARTED, EventTypes.EXECUTION_COMPLETED),
        EventTypes.EXECUTION_COMPLETED   to setOf(EventTypes.ASSEMBLY_STARTED),
        EventTypes.ASSEMBLY_STARTED      to setOf(EventTypes.ASSEMBLY_VALIDATED),
        EventTypes.ASSEMBLY_VALIDATED    to setOf(EventTypes.ASSEMBLY_COMPLETED),
        EventTypes.ASSEMBLY_COMPLETED    to emptySet(),
        EventTypes.CONTRACT_FAILED       to emptySet()
    )

    /**
     * Returns true when [to] is a legal next event type after [from].
     */
    fun isAllowed(from: String, to: String): Boolean =
        to in (ALLOWED[from] ?: emptySet())
}
