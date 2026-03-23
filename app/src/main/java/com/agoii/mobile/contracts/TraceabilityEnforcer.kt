package com.agoii.mobile.contracts

// ─── Contract Traceability Enforcement ───────────────────────────────────────

/**
 * TraceabilityEnforcer — eliminates construction-level drift.
 *
 * Verifies that every [ExecutionStep] in an [ExecutionPlan] maps back to a
 * concrete keyword or structural rule in the originating [ContractIntent].
 *
 * Traceability rules:
 *  1. [ContractModule.CORE] is always traced to the structural system requirement
 *     (always present, always valid — every contract touches the core model).
 *  2. All other modules are traced by locating their detection keyword in the
 *     full intent text (objective + constraints + environment + resources).
 *  3. A step is UNMAPPED when its module is non-CORE and no detection keyword
 *     is present in the intent — indicating a step that arrived without intent
 *     backing (construction drift).
 *
 * Rules:
 *  - Pure function: no state, no side effects.
 *  - [TraceabilityResult.passed] is true only when every step is mapped.
 */
class TraceabilityEnforcer {

    /**
     * Audit every step in [plan] against [intent].
     *
     * @param intent The contract intent that generated the surface.
     * @param plan   The execution plan to audit.
     * @return [TraceabilityResult] with per-step mapping and the passed flag.
     */
    fun enforce(intent: ContractIntent, plan: ExecutionPlan): TraceabilityResult {
        val allText = buildString {
            append(intent.objective)
            append(" ")
            append(intent.constraints)
            append(" ")
            append(intent.environment)
            append(" ")
            append(intent.resources)
        }.lowercase()

        val mappings = plan.steps.map { step -> mapStep(step, allText) }
        val unmapped = mappings.filter { !it.isMapped }.map { it.step }

        return TraceabilityResult(
            passed        = unmapped.isEmpty(),
            stepMappings  = mappings,
            unmappedSteps = unmapped
        )
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private fun mapStep(step: ExecutionStep, intentText: String): StepMapping {
        val (reference, mapped) = when (step.module) {
            ContractModule.CORE ->
                Pair("core system requirement — every contract mutation touches the core event model", true)

            ContractModule.UI ->
                findKeyword(intentText, "ui", "screen", "display", "view", "layout", "compose")

            ContractModule.BRIDGE ->
                findKeyword(intentText, "bridge", "interop", "native", "platform")

            ContractModule.ORCHESTRATION ->
                findKeyword(intentText, "orchestrat", "sequenc", "pipeline", "step", "stage")

            ContractModule.IRS ->
                findKeyword(intentText, "irs", "intent resolution", "scout", "swarm",
                            "reconstruction", "evidence")

            ContractModule.GOVERNANCE ->
                findKeyword(intentText, "governance", "contract", "approval", "surface", "gate")
        }

        return StepMapping(
            step            = step,
            intentReference = reference,
            isMapped        = mapped
        )
    }

    private fun findKeyword(text: String, vararg keywords: String): Pair<String, Boolean> {
        val found = keywords.firstOrNull { text.contains(it) }
        return if (found != null) {
            Pair("keyword '$found' found in intent", true)
        } else {
            Pair("no matching keyword found in intent", false)
        }
    }
}
