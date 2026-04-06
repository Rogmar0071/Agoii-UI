package com.agoii.mobile.ui.core

import com.agoii.mobile.core.ReplayStructuralState

/**
 * Reads a [ReplayStructuralState] and exposes the ledger's current assembly state.
 *
 * Responsibility: mapping only — no computation beyond reading [ReplayStructuralState].
 */
class LedgerViewEngine(private val projection: StateProjection = StateProjection()) {

    /** Whether the assembly phase has been started. */
    val assemblyStarted: Boolean get() = _uiState?.assemblyStarted ?: false

    /** Whether assembly validation has passed. */
    val assemblyValidated: Boolean get() = _uiState?.assemblyValidated ?: false

    /** Whether the assembly phase has fully completed. */
    val assemblyCompleted: Boolean get() = _uiState?.assemblyCompleted ?: false

    private var _uiState: UIState? = null

    /**
     * Returns the current [UIState], or null if [render] has not been called yet.
     */
    fun currentUIState(): UIState? = _uiState

    /**
     * Ingest a new [ReplayStructuralState] and update all exposed properties.
     * This is the only write path into the engine.
     */
    fun render(state: ReplayStructuralState) {
        _uiState = projection.project(state)
    }
}
