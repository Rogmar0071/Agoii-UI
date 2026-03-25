package com.agoii.mobile.core

/**
 * Minimum contract for an append-only event repository.
 * Having a dedicated interface keeps the core layer free of Android dependencies
 * and allows in-memory implementations in unit tests.
 */
interface EventRepository {
    /**
     * Append exactly one event to the named project's ledger.
     *
     * @throws LedgerValidationException if [type] is not in [EventTypes.ALL] or the transition
     *         from the current last event to [type] is illegal.  Only thrown by implementations
     *         that enforce pre-write validation (e.g. [EventLedger]).
     * @throws LedgerWriteException if the underlying write operation fails.
     */
    fun appendEvent(projectId: String, type: String, payload: Map<String, Any> = emptyMap())

    /**
     * Return all events in insertion order.
     *
     * @throws LedgerCorruptionException if the persisted ledger data is structurally corrupt.
     *         Implementations that do not persist data (e.g. in-memory) never throw.
     */
    fun loadEvents(projectId: String): List<Event>
}
