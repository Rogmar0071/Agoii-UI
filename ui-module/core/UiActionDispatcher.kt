package agoii.ui.core

import agoii.ui.bridge.CoreBridge

/**
 * UiActionDispatcher — routes ALL user actions through CoreBridge.
 *
 * CONTRACT MQP-UI-INGRESS-ONLY-v1: UI interaction is exclusively routed via
 * CoreBridge.appendUserMessage().  NO execution calls are permitted from the UI layer.
 * Contract approval (approve) is the only non-ingress action, and it writes a
 * ledger event (CONTRACTS_APPROVED) — not an execution trigger.
 *
 * Invariants:
 *   UI-STOP-03                 — ONLY allowed path: UI → CoreBridge → Ledger
 *   ARCH-02 (LAYER_PURITY)    — No cross-layer calls
 *   MQP-UI-INGRESS-ONLY-v1    — sendInteraction is NO-OP; appendUserMessage is the path
 */
class UiActionDispatcher(private val coreBridge: CoreBridge) {

    /**
     * MQP-UI-DECOUPLE-EXECUTION-v1: NO-OP — dispatcher no longer triggers execution.
     * UI interaction is now routed exclusively via CoreBridge.appendUserMessage().
     */
    fun sendInteraction(input: String) {
        // NO-OP — dispatcher no longer triggers execution
    }

    /**
     * Approve a contract by ID.
     * Delegates to coreBridge.approveContracts().
     */
    fun approve(contractId: String) {
        coreBridge.approveContracts(contractId)
    }

    fun approveIntent(intentId: String) {
        coreBridge.approveIntent(intentId)
    }

    fun rejectIntent(intentId: String) {
        coreBridge.rejectIntent(intentId)
    }
}
