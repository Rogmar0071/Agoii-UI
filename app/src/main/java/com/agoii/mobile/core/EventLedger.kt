package com.agoii.mobile.core

import java.util.UUID

class EventLedger(private val store: EventStore) : EventRepository {

    private val lock = LedgerLock()
    private val validation = ValidationLayer()
    private val integrity = LedgerIntegrity()

    private fun nextSequence(events: List<Event>): Long =
        if (events.isEmpty()) 0L else events.last().sequenceNumber + 1L

    override fun appendEvent(projectId: String, type: String, payload: Map<String, Any>) {
        // FIX 4 — NULL SAFETY: guard against null/blank inputs before any lock or store access
        if (projectId.isBlank()) return
        if (payload.isEmpty()) return

        // FIX 1 / FIX 5 — BYPASS VALIDATION for INTENT_SUBMITTED: skip ValidationLayer and
        // LedgerIntegrity so that no side-effects, no governor, and no execution are triggered.
        if (type == EventTypes.INTENT_SUBMITTED) {
            lock.withLock(projectId) {
                val currentEvents = store.loadEvents(projectId)

                val newEvent = Event(
                    // FIX 2 — HARD-CODED EVENT TYPE: literal constant, not derived or transformed
                    type = EventTypes.INTENT_SUBMITTED,
                    // FIX 3 — MINIMAL EVENT STRUCTURE: id, type, timestamp, payload only
                    payload = payload,
                    id = UUID.randomUUID().toString(),
                    sequenceNumber = nextSequence(currentEvents),
                    timestamp = System.currentTimeMillis()
                )

                store.appendEvent(projectId, newEvent, currentEvents)
            }
            return
        }

        lock.withLock(projectId) {

            val currentEvents = store.loadEvents(projectId)

            validation.validate(projectId, type, payload, currentEvents)

            val newEvent = Event(
                type = type,
                payload = payload,
                id = UUID.randomUUID().toString(),
                sequenceNumber = nextSequence(currentEvents),
                timestamp = System.currentTimeMillis()
            )

            store.appendEvent(projectId, newEvent, currentEvents)

            val persisted = store.loadEvents(projectId)
            integrity.verify(projectId, persisted)
        }
    }

    override fun loadEvents(projectId: String): List<Event> =
        store.loadEvents(projectId)
}
