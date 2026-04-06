package com.agoii.mobile.core

import java.util.UUID

class EventLedger(private val store: EventStore) : EventRepository {

    private val lock = LedgerLock()
    private val validation = ValidationLayer()
    private val integrity = LedgerIntegrity()

    /**
     * CONTRACT MQP-LEDGER-ACTIVATION-v1 — observer notified AFTER each successful append.
     * Notification is delivered outside the per-project lock so observers may safely
     * call [appendEvent] without risk of deadlock.
     */
    @Volatile private var observer: LedgerObserver? = null

    /**
     * Register the single [LedgerObserver] for this ledger instance.
     * Replaces any previously registered observer.
     *
     * CONTRACT MQP-LEDGER-ACTIVATION-v1: called once by CoreBridge during initialisation,
     * before any events are appended.  Must be called from a single thread.
     */
    @Synchronized
    fun registerObserver(observer: LedgerObserver) {
        this.observer = observer
    }

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

        // Notify observer OUTSIDE the lock — allows safe re-entrant appendEvent calls
        // from within the observer (e.g. Governor advancing the ledger).
        observer?.onLedgerUpdated(projectId)
    }

    override fun loadEvents(projectId: String): List<Event> =
        store.loadEvents(projectId)
}
