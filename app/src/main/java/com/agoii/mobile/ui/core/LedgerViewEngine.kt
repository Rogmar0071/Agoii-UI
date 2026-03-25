package com.agoii.mobile.ui.core

import com.agoii.mobile.core.ReplayStructuralState

/**
 * Reads a [ReplayStructuralState] and exposes the ledger's current assembly state and
 * active task identifier.
 *
 * Responsibility: mapping only — no computation beyond reading [ReplayStructuralState].
 */
class LedgerViewEngine(private val projection: StateProjection = StateProjection()) {

    /** The task currently being processed, or null if none is active. */
    val activeTask: String? get() = _uiState?.activeTaskId

    /** Whether the assembly phase has been started. */
    val assemblyStarted: Boolean get() = _uiState?.assemblyStarted ?: false

    /** Whether assembly validation has passed. */
    val assemblyValidated: Boolean get() = _uiState?.assemblyValidated ?: false

    /** Whether the assembly phase has fully completed. */
    val assemblyCompleted: Boolean get() = _uiState?.assemblyCompleted ?: false

    private var _uiState: UIState? = null

    /**
     * Ingest a new [ReplayStructuralState] and update all exposed properties.
     * This is the only write path into the engine.
     */
    fun render(state: ReplayStructuralState) {
        _uiState = projection.project(state)
    }

    /** Returns the latest [UIState] produced by the last [render] call, or null. */
    fun currentUIState(): UIState? = _uiState
}
