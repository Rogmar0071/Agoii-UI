package agoii.ui.core

import agoii.ui.bridge.UIReplayState

/**
 * Reads a [UIReplayState] and exposes the ledger's current assembly state.
 *
 * Responsibility: mapping only — no computation beyond reading [UIReplayState].
 */
class LedgerViewEngine(private val projection: StateProjection = StateProjection()) {

    /** Whether the assembly phase has been started. */
    val assemblyStarted: Boolean
        get() = _uiState?.assemblyStarted
            ?: throw IllegalStateException("UIState not initialized")

    /** Whether assembly validation has passed. */
    val assemblyValidated: Boolean
        get() = _uiState?.assemblyValidated
            ?: throw IllegalStateException("UIState not initialized")

    /** Whether the assembly phase has fully completed. */
    val assemblyCompleted: Boolean
        get() = _uiState?.assemblyCompleted
            ?: throw IllegalStateException("UIState not initialized")

    private var _uiState: UIState? = null

    /**
     * Ingest a new [UIReplayState] and update all exposed properties.
     * This is the only write path into the engine.
     */
    fun render(state: UIReplayState) {
        _uiState = projection.project(state)
    }
}
