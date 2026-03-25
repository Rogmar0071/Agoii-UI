package com.agoii.mobile.core

/**
 * Single write authority for the Agoii event ledger.
 *
 * [EventLedger] is the ONLY class that may initiate a write to the underlying
 * [EventRepository].  All callers (Governor, ExecutionOrchestrator, CoreBridge)
 * receive an [EventRepository] reference that resolves to an [EventLedger] instance
 * so that every append is channelled through this class.
 *
 * Write contract (System Laws 1-6):
 *  1. Acquire the exclusive per-project lock ([LedgerLock]).
 *  2. Load current events from the backing store (read-under-lock eliminates TOCTOU).
 *  3. Run [ValidationLayer.validate] — reject invalid type or illegal transition.
 *  4. Delegate to [store.appendEvent] — store assigns UUID / sequenceNumber / timestamp
 *     and commits atomically.
 *  5. Release the lock.
 *
 * Read path: [loadEvents] is a transparent delegation to the backing store.
 * Reads are not locked because the backing [EventStore] uses an atomic file rename
 * for writes, so any read always observes a consistent snapshot.
 */
class EventLedger(private val store: EventRepository) : EventRepository {

    private val lock       = LedgerLock()
    private val validation = ValidationLayer()

    /**
     * Append one event to [projectId]'s ledger.
     *
     * The full sequence — lock → load → validate → write — is executed under the
     * project-scoped mutex so that no two threads can interleave writes for the same project.
     *
     * @throws LedgerValidationException  if [type] is unknown or the transition is illegal.
     * @throws LedgerCorruptionException  if the current ledger data is structurally corrupt.
     * @throws LedgerWriteException       if the underlying atomic write fails.
     */
    override fun appendEvent(projectId: String, type: String, payload: Map<String, Any>) {
        lock.withLock(projectId) {
            val currentEvents = store.loadEvents(projectId)
            validation.validate(projectId, type, currentEvents)
            store.appendEvent(projectId, type, payload)
        }
    }

    /**
     * Return all events for [projectId] in insertion order.
     *
     * @throws LedgerCorruptionException if the persisted ledger data is corrupt.
     */
    override fun loadEvents(projectId: String): List<Event> = store.loadEvents(projectId)
}
