package agoii.ui.core

import android.util.Log
import agoii.ui.bridge.CoreBridge

/**
 * UiStateBinder — binds ReplayStructuralState to UiModel.
 *
 * UI-01 REPLAY BINDING: ALL UI state comes from coreBridge.replayState().
 * This class performs ZERO derivation — pure mapping only.
 *
 * Invariants:
 *   RL-01  (REPLAY_PURITY)     — State from replay only
 *   ARCH-04 (STATE_AUTHORITY)   — Single derivation path
 *   UI-STOP-01                  — Reads ONLY governanceView, executionView, auditView, conversation
 *   UI-STOP-02                  — No derived state, no logic
 *   MQP-PHASE-3                 — conversation = state.conversation (Replay sole authority)
 */
class UiStateBinder(private val coreBridge: CoreBridge) {

    /**
     * Produce a UiModel by reading ReplayStructuralState.
     *
     * Maps:
     *   state.governanceView → UiModel.governance
     *   state.executionView  → UiModel.execution
     *   state.auditView      → UiModel.audit
     *   state.conversation   → UiModel.chat (via buildChatModel)
     *
     * ZERO computation. ZERO derivation. Pure read.
     */
    fun getUiModel(): UiModel {
        Log.e("AGOII_TRACE", "REPLAY_LOAD_START")
        val state = coreBridge.replayState()
        Log.e("AGOII_TRACE", "REPLAY_LOAD_DONE")
        return UiModel(
            governance = state.governanceView,
            execution = state.executionView,
            audit = state.auditView,
            chat = buildChatModel(state),
            intentConstruction = state.intentConstruction
        )
    }

    /**
     * Build ChatUiModel from ReplayStructuralState.
     *
     * MQP-PHASE-3: messages = state.conversation projected to ChatMessage.
     * NO hardcoded messages. NO derivation. NO invented history.
     * Replay is the SOLE authority — UI is a pure projection.
     *
     * If the conversation is empty (no messages yet in ledger),
     * the messages list is empty and the UI renders an empty chat surface.
     */
    private fun buildChatModel(state: ReplayStructuralState): ChatUiModel {
        return ChatUiModel(
            messages = state.conversation.map { msg ->
                ChatMessage(
                    id = msg.id,
                    text = msg.text,
                    isUser = msg.isUser
                )
            },
            // currentInput is always empty when bound from Replay — transient typing state
            // is owned by the InteractionPanel composable (local `var input by remember {}`).
            currentInput = ""
        )
    }
}
