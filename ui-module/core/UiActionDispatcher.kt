package agoii.ui.core

import agoii.ui.bridge.CoreBridge

/**
 * UiActionDispatcher — routes ALL user actions through CoreBridge.
 *
 * UI-03 INTERACTION FLOW: No direct execution calls permitted.
 * ALL interactions go through coreBridge.processInteraction() or coreBridge.approveContracts().
 *
 * Invariants:
 *   UI-STOP-03                 — ONLY allowed path: UI → CoreBridge → Nemoclaw
 *   ARCH-02 (LAYER_PURITY)    — No cross-layer calls
 *   DONE-UI-03                 — ALL interactions routed through CoreBridge
 */
class UiActionDispatcher(private val coreBridge: CoreBridge) {

    /**
     * Send a user interaction to the system core.
     * Delegates to coreBridge.processInteraction().
     */
    fun sendInteraction(input: String) {
        coreBridge.processInteraction(input)
    }

    /**
     * Approve a contract by ID.
     * Delegates to coreBridge.approveContracts().
     */
    fun approve(contractId: String) {
        coreBridge.approveContracts(contractId)
    }
}
