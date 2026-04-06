package com.agoii.mobile.core

/**
 * LedgerObserver — receives a notification every time an event is successfully
 * appended to the EventLedger.
 *
 * CONTRACT MQP-LEDGER-ACTIVATION-v1:
 * The EventLedger is the SOLE activation trigger for system progression.
 * Any component that must react to ledger changes MUST implement this interface
 * and register itself via [EventLedger.registerObserver].
 *
 * Implementation rules:
 *  - Implementations MUST be idempotent and re-entry–safe.
 *  - Implementations MUST NOT assume the notification is delivered inside any lock.
 *  - Implementations MUST guard against concurrent or recursive activation.
 */
interface LedgerObserver {
    /**
     * Called after an event has been successfully persisted and verified for
     * [projectId].  The observer MUST NOT assume any specific event type; it
     * reads the ledger state itself to decide whether to act.
     */
    fun onLedgerUpdated(projectId: String)
}
