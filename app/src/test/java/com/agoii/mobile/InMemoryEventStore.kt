package com.agoii.mobile

import com.agoii.mobile.core.Event
import com.agoii.mobile.core.LedgerWriteException

/**
 * In-memory EventStore for test isolation.
 * 
 * Provides a minimal, thread-safe event store that doesn't require Android Context.
 * Used for deterministic testing where persistence is not needed.
 */
class InMemoryEventStore {
    
    private val ledgers = mutableMapOf<String, MutableList<Event>>()
    private val locks = mutableMapOf<String, Any>()
    
    private fun getLock(projectId: String): Any {
        return synchronized(locks) {
            locks.getOrPut(projectId) { Any() }
        }
    }
    
    fun appendEvent(projectId: String, event: Event, priorEvents: List<Event>) {
        synchronized(getLock(projectId)) {
            val current = ledgers[projectId] ?: mutableListOf()
            
            // Verify optimistic concurrency control
            if (current.size != priorEvents.size) {
                throw LedgerWriteException("Concurrent modification detected for '$projectId'")
            }
            
            current.add(event)
            ledgers[projectId] = current
        }
    }
    
    fun loadEvents(projectId: String): List<Event> {
        synchronized(getLock(projectId)) {
            return ledgers[projectId]?.toList() ?: emptyList()
        }
    }
    
    fun clearLedger(projectId: String) {
        synchronized(getLock(projectId)) {
            ledgers.remove(projectId)
        }
    }
    
    /**
     * Deep copy all events for replay testing.
     * Returns a new list with event copies to ensure isolation.
     */
    fun copyEvents(projectId: String): List<Event> {
        synchronized(getLock(projectId)) {
            return ledgers[projectId]?.map { event ->
                Event(
                    type = event.type,
                    payload = event.payload.toMap(),
                    id = event.id,
                    sequenceNumber = event.sequenceNumber,
                    timestamp = event.timestamp
                )
            } ?: emptyList()
        }
    }
}
