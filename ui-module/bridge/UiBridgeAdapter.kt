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
 *
 * CONTRACT MQP-UI-INGRESS-ONLY-v1:
 *   UI MUST NOT trigger execution.  The only permitted ingress point is
 *   [appendUserMessage].  Execution is driven exclusively by the EventLedger
 *   observer (CONTRACT MQP-LEDGER-ACTIVATION-v1).
 */
interface CoreBridge {
    /** Returns the current ReplayStructuralState — the SOLE source of UI truth. */
    fun replayState(): ReplayStructuralState

    /**
     * CONTRACT MQP-UI-INGRESS-ONLY-v1 — pure ledger ingress.
     *
     * Appends USER_MESSAGE_SUBMITTED + INTENT_SUBMITTED and returns immediately.
     * MUST NOT trigger execution, the Governor, or any processing loop.
     * System progression is driven exclusively by the EventLedger observer.
     */
    fun appendUserMessage(input: String)

    /** Routes contract approval through the governed pipeline. */
    fun approveContracts(contractId: String)
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
     * CONTRACT MQP-UI-INGRESS-ONLY-v1: UI is a pure event emitter.
     * Appends USER_MESSAGE_SUBMITTED + INTENT_SUBMITTED via CoreBridge.
     * NO execution is triggered from this call.
     */
    fun appendUserMessage(input: String) {
        coreBridge.appendUserMessage(input)
    }

    /**
     * Forward contract approval to the system core via CoreBridge.
     */
    fun approve(contractId: String) {
        coreBridge.approveContracts(contractId)
    }
}
