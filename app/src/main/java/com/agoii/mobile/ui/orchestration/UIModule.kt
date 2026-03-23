package com.agoii.mobile.ui.orchestration

import com.agoii.mobile.ui.core.UIState

/**
 * Contract for all pluggable UI modules.
 *
 * Each implementation must:
 * - expose a stable [id] that uniquely identifies it in the registry.
 * - implement [render] as a pure function of [UIState]: same input → same output.
 * - NOT mutate any shared state.
 * - NOT emit events.
 *
 * The return type of [render] is [Any] so that each module can return its own
 * strongly-typed presentation model without coupling the interface to a
 * specific model hierarchy.
 */
interface UIModule {
    /** Stable identifier used by [UIModuleRegistry] to look up this module. */
    val id: String

    /**
     * Produce a presentation model from [state].
     * The returned value is the module's own data class (e.g. [com.agoii.mobile.ui.modules.ContractModuleState]).
     */
    fun render(state: UIState): Any
}
