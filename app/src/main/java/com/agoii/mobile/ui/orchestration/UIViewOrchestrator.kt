package com.agoii.mobile.ui.orchestration

import android.util.Log
import com.agoii.mobile.core.ReplayStructuralState
import com.agoii.mobile.ui.core.StateProjection
import com.agoii.mobile.ui.core.UIState

/**
 * Combined view state produced by [UIViewOrchestrator].
 *
 * @property core    The canonical [UIState] projected from the ledger.
 * @property modules A map of module ID → presentation model for each registered [UIModule].
 */
data class CombinedViewState(
    val core: UIState,
    val modules: Map<String, Any>
)

/**
 * Orchestrates the full UI rendering pipeline:
 *
 *   [ReplayStructuralState]
 *     → [StateProjection.project] → [UIState]
 *     → each [UIModule.render] (in registry order) → [CombinedViewState]
 *
 * Rules:
 * - No business logic — this is a pure data pipeline.
 * - Module outputs are keyed by [UIModule.id]; later registrations overwrite earlier ones
 *   with the same id.
 * - No side effects. [orchestrate] may be called as many times as needed; it always
 *   produces the same output for the same input.
 */
class UIViewOrchestrator(
    private val registry: UIModuleRegistry = UIModuleRegistry(),
    private val projection: StateProjection = StateProjection()
) {

    /**
     * Runs the full projection + rendering pipeline for the given [replayState].
     * Returns an immutable [CombinedViewState].
     */
    fun orchestrate(replayState: ReplayStructuralState): CombinedViewState {
        Log.e("AGOII_TRACE", "DEAD_UI_PATH_TRIGGERED")
        val uiState = projection.project(replayState)
        val moduleOutputs = buildModuleOutputs(uiState)
        return CombinedViewState(core = uiState, modules = moduleOutputs)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private fun buildModuleOutputs(state: UIState): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        for (module in registry.getModules()) {
            result[module.id] = module.render(state)
        }
        return result.toMap()
    }
}
