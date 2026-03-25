package com.agoii.mobile.ui.core

import com.agoii.mobile.core.ReplayStructuralState

/**
 * Reads a [ReplayStructuralState] and exposes all 5 structural fields of the current state.
 *
 * Responsibility: mapping only — no computation beyond reading [ReplayStructuralState].
 * All properties throw [IllegalStateException] if [render] has not yet been called.
 */
class LedgerViewEngine(private val projection: StateProjection = StateProjection()) {

    /** Whether execution has been initiated (assignedTasks > 0). */
    val executionStarted: Boolean
        get() = _uiState?.executionStarted ?: throw IllegalStateException("UIState not initialized")

    /** Whether execution has fully completed (fullyExecuted). */
    val executionCompleted: Boolean
        get() = _uiState?.executionCompleted ?: throw IllegalStateException("UIState not initialized")

    /** Whether the assembly phase has been started. */
    val assemblyStarted: Boolean
        get() = _uiState?.assemblyStarted ?: throw IllegalStateException("UIState not initialized")

    /** Whether assembly validation has passed. */
    val assemblyValidated: Boolean
        get() = _uiState?.assemblyValidated ?: throw IllegalStateException("UIState not initialized")

    /** Whether the assembly phase has fully completed. */
    val assemblyCompleted: Boolean
        get() = _uiState?.assemblyCompleted ?: throw IllegalStateException("UIState not initialized")

    private var _uiState: UIState? = null

    /**
     * Ingest a new [ReplayStructuralState] and update all exposed properties.
     * This is the only write path into the engine.
     */
    fun render(state: ReplayStructuralState) {
        _uiState = projection.project(state)
    }
}
