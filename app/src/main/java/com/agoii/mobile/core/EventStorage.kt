package com.agoii.mobile.core

/**
 * Storage abstraction for the event ledger.
 *
 * Separating storage from [EventLedger] allows test-only in-memory implementations
 * to be injected without requiring an Android [android.content.Context].
 *
 * The production implementation is [EventStore]; test code may supply
 * any in-memory implementation of this interface.
 */
interface EventStorage {

    /**
     * Persist [event] for [projectId].
     *
     * [priorEvents] is the snapshot of events that existed immediately before this
     * write; the implementation may use it to produce an atomic write (e.g. write
     * the full list to disk atomically) or may ignore it for simple in-memory stores.
     */
    fun appendEvent(projectId: String, event: Event, priorEvents: List<Event>)

    /** Return all events for [projectId] in append order, or an empty list. */
    fun loadEvents(projectId: String): List<Event>
}
