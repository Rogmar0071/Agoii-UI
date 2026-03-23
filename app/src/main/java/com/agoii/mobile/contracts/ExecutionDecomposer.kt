package com.agoii.mobile.contracts

// ─── Step 3 — Execution Decomposition ────────────────────────────────────────

/**
 * ExecutionDecomposer — Step 3 of the Contract Engine.
 *
 * Converts a [ContractSurface] into an ordered [ExecutionPlan].
 *
 * Algorithm:
 *  - Each [MappedModule] in the surface becomes exactly one [ExecutionStep].
 *  - Step load equals the module's structural [ContractModule.weight].
 *  - Steps are assigned 1-based positions preserving the surface's sorted module order.
 *
 * Rules:
 *  - Pure function: no state, no side effects.
 *  - Always produces at least one step when the surface is non-empty.
 *  - [ExecutionPlan.totalLoad] equals the sum of all step loads, which equals
 *    [ContractSurface.totalWeight] by construction.
 */
class ExecutionDecomposer {

    /**
     * Decompose [surface] into an ordered [ExecutionPlan].
     *
     * @param surface The mutation footprint produced by [SurfaceMapper].
     * @return [ExecutionPlan] with steps in surface order and totalLoad pre-computed.
     */
    fun decompose(surface: ContractSurface): ExecutionPlan {
        val steps = surface.modules.mapIndexed { index, mappedModule ->
            ExecutionStep(
                position    = index + 1,
                description = "execute ${mappedModule.module.name} layer: ${mappedModule.reason}",
                module      = mappedModule.module,
                load        = mappedModule.module.weight
            )
        }
        return ExecutionPlan.from(steps)
    }
}
