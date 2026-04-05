package agoii.ui.core

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
 *   UI-STOP-01                  — Reads ONLY governanceView, executionView, auditView
 *   UI-STOP-02                  — No derived state, no logic
 */
class UiStateBinder(private val coreBridge: CoreBridge) {

    /**
     * Produce a UiModel by reading ReplayStructuralState.
     *
     * Maps:
     *   state.governanceView → UiModel.governance
     *   state.executionView  → UiModel.execution
     *   state.auditView      → UiModel.audit
     *   state.executionView  → UiModel.chat (via buildChatModel)
     *
     * ZERO computation. ZERO derivation. Pure read.
     */
    fun getUiModel(): UiModel {
        val state = coreBridge.replayState()

        return UiModel(
            governance = state.governanceView,
            execution = state.executionView,
            audit = state.auditView,
            chat = buildChatModel(state)
        )
    }

    /**
     * Build ChatUiModel from ReplayStructuralState.
     *
     * Reads ONLY state.executionView.executionStatus — no other derivation.
     * Single system message reflects current execution state from Replay.
     *
     * CHAT-UI-02: NO stored history. NO invented messages. Replay is sole authority.
     */
    private fun buildChatModel(state: ReplayStructuralState): ChatUiModel {
        val message = when (state.executionView.executionStatus) {
            "success" -> "Execution completed"
            "failed"  -> "Execution failed"
            "running" -> "Execution in progress"
            else      -> "Awaiting input"
        }

        return ChatUiModel(
            messages = listOf(
                ChatMessage(
                    id = "system",
                    text = message,
                    isUser = false
                )
            ),
            currentInput = ""
        )
    }
}
