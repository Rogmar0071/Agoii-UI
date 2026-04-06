package agoii.ui.core

/**
 * CHAT-UI-01 - Chat UI state models for interaction surface.
 *
 * Scope: /ui-module/core/ ONLY
 * Source of truth: ReplayStructuralState (via UiStateBinder)
 *
 * Invariants:
 *   RL-01 (REPLAY_PURITY)
 *   ARCH-07 (UI_ISOLATION)
 *   MQP-UI-CHAT-LAYER-v1
 */
data class ChatMessage(
    val id: String,
    val text: String,
    val isUser: Boolean
)

/**
 * ChatUiModel - complete UI state for the chat interaction surface.
 *
 * messages     - ordered list of chat turns
 * currentInput - transient input field (NOT execution state)
 *
 * NO computed properties.
 * NO derived logic.
 */
data class ChatUiModel(
    val messages: List<ChatMessage>,
    val currentInput: String
)
