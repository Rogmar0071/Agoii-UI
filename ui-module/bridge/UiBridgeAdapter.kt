package agoii.ui.bridge

import agoii.ui.core.GovernanceView
import agoii.ui.core.ExecutionView
import agoii.ui.core.AuditView
import agoii.ui.core.ReplayStructuralState

/**
 * UI-STOP-03 ENFORCEMENT: UI → CoreBridge → Nemoclaw
 *
 * UiBridgeAdapter is the SOLE access path between UI and the system core.
 * UI MUST NOT access Nemoclaw, ReplayBuilder, or EventLedger directly.
 *
 * Invariants:
 *   ARCH-02 (LAYER_PURITY)   — No cross-layer leakage
 *   ARCH-03 (BOUNDARY_LOCK)  — UI boundary enforced at adapter level
 *   ARCH-04 (STATE_AUTHORITY) — All state from ReplayStructuralState
 *   RL-01   (REPLAY_PURITY)  — Zero derivation in UI
 */

/**
 * CoreBridge interface — defines the contract the system core must fulfil.
 *
 * This is the ONLY interface UI is permitted to depend on.
 * Implementation lives outside the UI module (system layer).
 */
interface CoreBridge {
    /** Returns the current ReplayStructuralState — the SOLE source of UI truth. */
    fun replayState(): ReplayStructuralState

    /** Routes user interaction through the governed pipeline. */
    fun processInteraction(input: String)

    /** Routes contract approval through the governed pipeline. */
    fun approveContracts(contractId: String)

    /**
     * Append USER_MESSAGE_SUBMITTED event to the ledger and trigger the governed
     * execution pipeline.  UI MUST call ONLY this method for user input — no
     * direct execution calls are permitted.
     *
     * MQP-UI-DECOUPLE-EXECUTION-v1: UI is a pure emitter.  All execution
     * originates from the ledger entry written here.
     */
    fun appendUserMessage(input: String)
}

/**
 * UiBridgeAdapter — thin, stateless adapter that delegates to CoreBridge.
 *
 * Zero logic. Zero derivation. Pure delegation.
 */
class UiBridgeAdapter(private val coreBridge: CoreBridge) {

    /**
     * Load the current ReplayStructuralState.
     * UI reads ONLY from the returned state — no computation permitted.
     */
    fun loadState(): ReplayStructuralState {
        return coreBridge.replayState()
    }

    /**
     * Forward user interaction to the system core via CoreBridge.
     * UI-03: ALL interactions routed through CoreBridge.
     */
    fun interact(input: String) {
        coreBridge.processInteraction(input)
    }

    /**
     * Forward appendUserMessage to the system core via CoreBridge.
     * MQP-UI-DECOUPLE-EXECUTION-v1: UI emits event; system drives execution.
     */
    fun appendUserMessage(input: String) {
        coreBridge.appendUserMessage(input)
    }

    /**
     * Forward contract approval to the system core via CoreBridge.
     * UI-03: ALL interactions routed through CoreBridge.
     */
    fun approve(contractId: String) {
        coreBridge.approveContracts(contractId)
    }
}
