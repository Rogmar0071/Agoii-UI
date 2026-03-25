package com.agoii.mobile.core

/**
 * EventLedger — the SOLE authoritative write gateway for all events.
 *
 * Rules:
 *  - ALL event writes MUST go through this class; no other component may call
 *    [EventRepository.appendEvent] directly.
 *  - Every append is validated by [ValidationLayer] before being persisted.
 *    An illegal event causes an [IllegalStateException] — fail fast, never silent.
 *  - Read access ([loadEvents]) delegates transparently to the underlying store.
 *  - Implements [EventRepository] so it can be injected wherever an
 *    [EventRepository] is expected (e.g. [Governor]) without changing call-site types.
 *
 * @param store The underlying [EventRepository] that performs physical I/O.
 */
class EventLedger(private val store: EventRepository) : EventRepository {

    private val validation = ValidationLayer()

    /**
     * Validate [type] against the current ledger state for [projectId], then persist.
     *
     * @throws IllegalStateException if [ValidationLayer] rejects the event.
     */
    override fun appendEvent(projectId: String, type: String, payload: Map<String, Any>) {
        val current = store.loadEvents(projectId)
        when (val result = validation.validate(current, type)) {
            is ValidationLayer.ValidationResult.Rejected ->
                throw IllegalStateException(
                    "EventLedger rejected event '$type' for project '$projectId': ${result.reason}"
                )
            is ValidationLayer.ValidationResult.Accepted ->
                store.appendEvent(projectId, type, payload)
        }
    }

    /** Delegates to the underlying store — read path is unchanged. */
    override fun loadEvents(projectId: String): List<Event> = store.loadEvents(projectId)
}
