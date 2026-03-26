package com.agoii.mobile.contracts

// ─── Execution Contract Mapper ────────────────────────────────────────────────

/**
 * ExecutionContractMapper — maps an [ExecutionPlan] (or its constituent
 * [ExecutionStep]s) into [ContractDefinition] instances of type
 * [ContractType.EXECUTION].
 *
 * All referenced types ([ExecutionPlan], [ExecutionStep], [ContractModule]) are
 * defined in `ContractModels.kt` within the same `com.agoii.mobile.contracts`
 * package and require no explicit imports.
 *
 * An EXECUTION contract governs a single atomic contractor task.  This mapper
 * is the bridge between the Contract Engine's structural decomposition output
 * and the Contract System's typed contract layer.
 *
 * Mapping rules:
 *  1. One [ContractDefinition] is produced per [ExecutionStep].
 *  2. The objective is derived from [ExecutionStep.description].
 *  3. The scope includes the [ExecutionStep.module] ([ContractModule]) name and "EXECUTION".
 *  4. The expectedOutput is a deterministic statement of successful step completion.
 *  5. Each definition carries the step position and total as metadata.
 *
 * Rules:
 *  - Stateless and pure: equal inputs always produce equal outputs.
 *  - Does not assign contract ids; that is [ContractFactory]'s responsibility.
 */
class ExecutionContractMapper {

    /**
     * Map a single [ExecutionStep] to a [ContractDefinition].
     *
     * @param step  The execution step to map.
     * @param total Total number of steps in the plan (used for metadata and criteria).
     * @return [ContractDefinition] of type [ContractType.EXECUTION].
     */
    fun mapStep(step: ExecutionStep, total: Int): ContractDefinition =
        ContractDefinition(
            type               = ContractType.EXECUTION,
            objective          = step.description,
            scope              = listOf(step.module.name, "EXECUTION"),
            constraints        = listOf(
                "Execution load must not exceed virtual capacity",
                "Step must complete before position ${step.position + 1} begins"
            ),
            expectedOutput     = "Step ${step.position} of $total completed by assigned contractor",
            completionCriteria = listOf(
                "Contractor output recorded for step ${step.position}",
                "No constraint violations raised during step ${step.position}"
            ),
            metadata           = mapOf(
                "position" to step.position.toString(),
                "total"    to total.toString(),
                "module"   to step.module.name,
                "load"     to step.load.toString()
            )
        )

    /**
     * Map an entire [ExecutionPlan] into an ordered list of [ContractDefinition]s.
     *
     * The returned list preserves the step order from [ExecutionPlan.steps].
     *
     * @param plan The execution plan to map.
     * @return Ordered [List] of [ContractDefinition]s, one per step.
     */
    fun mapPlan(plan: ExecutionPlan): List<ContractDefinition> =
        plan.steps.map { step -> mapStep(step, plan.steps.size) }
}
