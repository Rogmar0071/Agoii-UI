package com.agoii.mobile.core

import java.util.UUID

class EventLedger(private val store: EventStorage) : EventRepository {

    private val lock = LedgerLock()
    private val validation = ValidationLayer()
    private val integrity = LedgerIntegrity()

    override fun appendEvent(projectId: String, type: String, payload: Map<String, Any>) {
        lock.withLock(projectId) {

            val currentEvents = store.loadEvents(projectId)

            validation.validate(projectId, type, payload, currentEvents)

            val nextSequence = if (currentEvents.isEmpty()) 0L
            else currentEvents.last().sequenceNumber + 1L

            val newEvent = Event(
                type = type,
                payload = payload,
                id = UUID.randomUUID().toString(),
                sequenceNumber = nextSequence,
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
