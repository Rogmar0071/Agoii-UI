package com.agoii.mobile.core

import java.util.UUID

/**
 * Single write authority for the Agoii event ledger.
 *
 * [EventLedger] is the ONLY class that may initiate a write to the underlying
 * [EventStore].  All callers (Governor, ExecutionOrchestrator, CoreBridge)
 * receive an [EventRepository] reference that resolves to an [EventLedger] instance
 * so that every append is channelled through this class.
 *
 * Write pipeline (System Laws 1–7):
 *  1. Acquire the exclusive per-project lock ([LedgerLock]).
 *  2. Load current events from [store] (pre-write read; eliminates TOCTOU).
 *  3. Run [ValidationLayer.validate] — reject unknown type or illegal transition.
 *  4. Construct the full [Event] here: UUID id, monotonic sequenceNumber, wall-clock timestamp.
 *  5. Delegate to [EventStore.appendEvent] — pure persistence, no logic, atomic file write.
 *  6. Reload persisted events from [store] (post-commit truth) and run [LedgerIntegrity.verify].
 *  7. Release the lock.
 *
 * Read path: [loadEvents] is a transparent delegation to [store].
 * Reads are not locked because [EventStore] uses an atomic file rename for writes, so
 * any concurrent read always observes a consistent snapshot.
 */
class EventLedger(private val store: EventStore) : EventRepository {

    private val lock       = LedgerLock()
    private val validation = ValidationLayer()
    private val integrity  = LedgerIntegrity()

    /**
     * Append one event to [projectId]'s ledger.
     *
     * Event identity fields are assigned here — [EventStore] receives a fully-constructed
     * [Event] and performs only the atomic file write.
     *
     * @throws LedgerValidationException  if [type] is unknown or the transition is illegal.
     * @throws LedgerCorruptionException  if the current ledger data is structurally corrupt.
     * @throws LedgerWriteException       if the underlying atomic write fails.
     */
    override fun appendEvent(projectId: String, type: String, payload: Map<String, Any>) {
        lock.withLock(projectId) {
            val currentEvents = store.loadEvents(projectId)
            validation.validate(projectId, type, currentEvents)
            val nextSequence = if (currentEvents.isEmpty()) 0L
                               else currentEvents.last().sequenceNumber + 1L
            val newEvent = Event(
                type           = type,
                payload        = payload,
                id             = UUID.randomUUID().toString(),
                sequenceNumber = nextSequence,
                timestamp      = System.currentTimeMillis()
            )
            store.appendEvent(projectId, newEvent)
            val persisted = store.loadEvents(projectId)
            integrity.verify(projectId, persisted)
        }
    }

    /**
     * Return all events for [projectId] in insertion order.
     *
     * @throws LedgerCorruptionException if the persisted ledger data is corrupt.
     */
    override fun loadEvents(projectId: String): List<Event> = store.loadEvents(projectId)
}
