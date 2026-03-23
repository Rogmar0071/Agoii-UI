package com.agoii.mobile.contracts

// ─── Contract Adaptation Engine ───────────────────────────────────────────────

/**
 * ContractAdapter — modifies contract structure (not intent) when agent matching
 * returns [AgentMatchDecision.ADAPT].
 *
 * Adaptation strategies (applied per step):
 *  1. Split  — steps with load > [SPLIT_THRESHOLD] are split into two sub-steps,
 *              each carrying half the load, to reduce complexity per step.
 *  2. Rename — every step description is rewritten to be maximally explicit,
 *              removing abbreviations and adding the objective context.
 *  3. Annotate — an explicit constraint note is prepended to each step
 *              description when the originating contract had violations.
 *
 * Rules:
 *  - Adaptation modifies ONLY [ExecutionPlan]; intent and surface are unchanged.
 *  - Pure function: no state, no side effects.
 *  - All changes are recorded in [AdaptedContract.adaptationNotes].
 */
class ContractAdapter {

    /**
     * Produce an [AdaptedContract] from [scoredContract].
     *
     * Only called when matching decision is [AgentMatchDecision.ADAPT].
     *
     * @param scoredContract The scored and classified contract to adapt.
     * @return [AdaptedContract] with a revised execution plan and full adaptation trace.
     */
    fun adapt(scoredContract: ScoredContract): AdaptedContract {
        val original         = scoredContract.derivation
        val hasViolations    = !original.constraints.passed
        val adaptations      = mutableListOf<StepAdaptation>()
        val allAdaptedSteps  = mutableListOf<ExecutionStep>()

        original.executionPlan.steps.forEachIndexed { _, step ->
            val adaptation = adaptStep(step, hasViolations, allAdaptedSteps.size)
            adaptations.add(adaptation)
            allAdaptedSteps.addAll(adaptation.adaptedSteps)
        }

        // Re-number all adapted steps with continuous 1-based positions.
        val reNumbered = allAdaptedSteps.mapIndexed { index, step ->
            step.copy(position = index + 1)
        }

        val notes = buildAdaptationNotes(adaptations, scoredContract.classification)

        return AdaptedContract(
            original        = scoredContract,
            adaptedPlan     = ExecutionPlan.from(reNumbered),
            adaptations     = adaptations,
            adaptationNotes = notes
        )
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private fun adaptStep(
        step:          ExecutionStep,
        hasViolations: Boolean,
        currentOffset: Int
    ): StepAdaptation {
        return if (step.load > SPLIT_THRESHOLD) {
            // Split strategy: break into two sub-steps at half load each.
            val halfLoad = step.load / 2
            val rem      = step.load - halfLoad
            val subStepA = ExecutionStep(
                position    = currentOffset + 1,
                description = "[ADAPTED/1] ${explicitDescription(step, hasViolations)} — part A (init)",
                module      = step.module,
                load        = halfLoad
            )
            val subStepB = ExecutionStep(
                position    = currentOffset + 2,
                description = "[ADAPTED/2] ${explicitDescription(step, hasViolations)} — part B (finalise)",
                module      = step.module,
                load        = rem
            )
            StepAdaptation(
                originalStep   = step,
                adaptedSteps   = listOf(subStepA, subStepB),
                adaptationNote = "step[${step.position}/${step.module}] split: load ${step.load} " +
                                 "exceeds threshold $SPLIT_THRESHOLD → split into A(load=$halfLoad) + B(load=$rem)"
            )
        } else {
            // Rename strategy: rewrite description for explicitness.
            val adapted = step.copy(
                position    = currentOffset + 1,
                description = "[ADAPTED] ${explicitDescription(step, hasViolations)}"
            )
            StepAdaptation(
                originalStep   = step,
                adaptedSteps   = listOf(adapted),
                adaptationNote = "step[${step.position}/${step.module}] renamed for explicitness"
            )
        }
    }

    /** Builds a maximally explicit description for a step. */
    private fun explicitDescription(step: ExecutionStep, hasViolations: Boolean): String {
        val base = "execute ${step.module.name} layer (load=${step.load}): ${step.description}"
        return if (hasViolations) {
            "⚠ constraint-aware: $base"
        } else {
            base
        }
    }

    private fun buildAdaptationNotes(
        adaptations:    List<StepAdaptation>,
        classification: ContractClassification
    ): List<String> = buildList {
        add("adaptation triggered: classification=$classification")
        add("total steps before adaptation: ${adaptations.size}")
        val totalAfter = adaptations.sumOf { it.adaptedSteps.size }
        add("total steps after adaptation: $totalAfter")
        adaptations.forEach { add(it.adaptationNote) }
    }

    companion object {
        /** Steps with load above this threshold are split into two sub-steps. */
        const val SPLIT_THRESHOLD = 2
    }
}
