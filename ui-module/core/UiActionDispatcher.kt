package agoii.ui.core

import android.util.Log
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
        Log.e("AGOII_TRACE", "DISPATCH_START: $input")
        try {
            Log.e("AGOII_TRACE", "DISPATCH_EXECUTING")
            coreBridge.processInteraction(input)
            Log.e("AGOII_TRACE", "DISPATCH_COMPLETED")
        } catch (t: Throwable) {
            Log.e("AGOII_FATAL", "DISPATCH_CRASH: ${t.stackTraceToString()}")
            throw t
        }
        Log.e("AGOII_TRACE", "DISPATCH_END")
    }

    /**
     * Approve a contract by ID.
     * Delegates to coreBridge.approveContracts().
     */
    fun approve(contractId: String) {
        coreBridge.approveContracts(contractId)
    }
}
