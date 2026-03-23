package com.agoii.mobile.ui.orchestration

import com.agoii.mobile.ui.core.UIState
import com.agoii.mobile.ui.modules.ContractModuleUI
import com.agoii.mobile.ui.modules.TaskModuleUI
import com.agoii.mobile.ui.modules.ExecutionModuleUI

// ── Adapter wrappers ──────────────────────────────────────────────────────────
// Each adapter bridges a module presenter to the [UIModule] interface so the
// registry does not need to know about module-specific present() signatures.

private class ContractModuleAdapter(
    private val delegate: ContractModuleUI = ContractModuleUI()
) : UIModule {
    override val id: String = "contract"
    override fun render(state: UIState) = delegate.present(state)
}

private class TaskModuleAdapter(
    private val delegate: TaskModuleUI = TaskModuleUI()
) : UIModule {
    override val id: String = "task"
    override fun render(state: UIState) = delegate.present(state)
}

private class ExecutionModuleAdapter(
    private val delegate: ExecutionModuleUI = ExecutionModuleUI()
) : UIModule {
    override val id: String = "execution"
    override fun render(state: UIState) = delegate.present(state)
}

// ── Registry ──────────────────────────────────────────────────────────────────

/**
 * Maintains the ordered set of registered [UIModule]s.
 *
 * Modules are registered in construction order and returned in that same order
 * by [getModules]. All three core modules are registered by default.
 */
class UIModuleRegistry {

    private val modules: MutableList<UIModule> = mutableListOf(
        ContractModuleAdapter(),
        TaskModuleAdapter(),
        ExecutionModuleAdapter()
    )

    /** Returns every registered module in registration order. */
    fun getModules(): List<UIModule> = modules.toList()

    /**
     * Registers an additional [module]. Duplicate IDs are allowed; the
     * later registration takes priority during rendering in
     * [UIViewOrchestrator].
     */
    fun register(module: UIModule) {
        modules += module
    }
}
