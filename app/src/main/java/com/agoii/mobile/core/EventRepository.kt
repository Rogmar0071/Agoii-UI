package com.agoii.mobile.core

/**
 * Minimum contract for an append-only event repository.
 * Having a dedicated interface keeps the core layer free of Android dependencies
 * and allows in-memory implementations in unit tests.
 */
interface EventRepository {
    /** Append exactly one event to the named project's ledger. */
    fun appendEvent(projectId: String, type: String, payload: Map<String, Any> = emptyMap())

    /** Return all events in insertion order. Never throws; returns empty list on error. */
    fun loadEvents(projectId: String): List<Event>
}
