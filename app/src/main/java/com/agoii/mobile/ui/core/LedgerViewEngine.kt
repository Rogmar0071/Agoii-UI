package com.agoii.mobile.ui.core

import com.agoii.mobile.core.ReplayState

/**
 * Reads a [ReplayState] and exposes the ledger's current phase, active
 * contract / task identifiers, and overall execution progress.
 *
 * Responsibility: mapping only — no computation beyond reading [ReplayState].
 */
class LedgerViewEngine(private val projection: StateProjection = StateProjection()) {

    /** The current lifecycle phase derived from the ledger. */
    val currentPhase: String get() = _uiState?.phase ?: "idle"

    /** The contract currently being processed, or null if none is active. */
    val activeContract: String? get() = _uiState?.activeContractId

    /** The task currently being processed, or null if none is active. */
    val activeTask: String? get() = _uiState?.activeTaskId

    /** Fraction of contracts completed in the current execution run (0.0–1.0). */
    val executionProgress: Float get() = _uiState?.progress ?: 0f

    private var _uiState: UIState? = null

    /**
     * Ingest a new [ReplayState] and update all exposed properties.
     * This is the only write path into the engine.
     */
    fun render(state: ReplayState) {
        _uiState = projection.project(state)
    }

    /** Returns the latest [UIState] produced by the last [render] call, or null. */
    fun currentUIState(): UIState? = _uiState
}
