package agoii.ui.core

/**
 * CHAT-UI-01 — Chat UI state models for LibreChat-style interaction surface.
 *
 * Scope: /ui-module/core/ ONLY
 * Source of truth: ReplayStructuralState (via UiStateBinder)
 *
 * Invariants:
 *   RL-01 (REPLAY_PURITY)     — All values originate from Replay; no UI-derived logic
 *   ARCH-07 (UI_ISOLATION)    — No imports from interaction/**, bridge/**, execution/**
 *   MQP-UI-CHAT-LAYER-v1     — UI displays only; does NOT interpret intent or compute state
 */

/**
 * ChatMessage — a single chat turn displayed in the conversation view.
 *
 * @param id     Unique identifier for this message (sourced from Replay).
 * @param text   Display text of the message.
 * @param isUser True if this message originated from the user; false for system output.
 */
data class ChatMessage(
    val id: String,
    val text: String,
    val isUser: Boolean
)

/**
 * ChatUiModel — complete UI state for the chat interaction surface.
 *
 * messages     — ordered list of chat turns, derived from ReplayStructuralState.
 * currentInput — transient text field value; NOT sent to execution until dispatch.
 *
 * NO computed properties. NO derived logic. ZERO business rules.
 */
data class ChatUiModel(
    val messages: List<ChatMessage>,
    val currentInput: String
)
