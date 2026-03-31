package com.agoii.mobile.contractors.matching

import com.agoii.mobile.contractors.capabilities.NemoclawCapabilities
import com.agoii.mobile.contractors.registry.NemoclawContractorDefinition

// ─── NemoClaw Matching Rules ──────────────────────────────────────────────────
//
// CONTRACT: REGISTER_NEMOCLAW_CONTRACTOR — Section 3 (MATCHING RULES)
// Implements deterministic matching for NemoClaw task assignment.
// NO heuristics. NO randomness. NO AI-based selection.

/**
 * Input task descriptor for NemoClaw matching evaluation.
 *
 * @property type                       Task category (must be in accepted types for a match).
 * @property requiresSandbox            Whether the task mandates a sandboxed execution context.
 * @property requiresExecution          Whether the task requires active execution (not planning).
 * @property policy                     Execution policy string; null or blank = policy absent.
 * @property outputType                 Expected output format (must be "ContractReport").
 * @property requiresAutonomy           Whether the task requires autonomous contractor decisions.
 * @property requiresRealWorldExecution Whether the task demands real-world side effects.
 */
data class NemoclawMatchingTask(
    val type:                       String,
    val requiresSandbox:            Boolean,
    val requiresExecution:          Boolean,
    val policy:                     String?,
    val outputType:                 String,
    val requiresAutonomy:           Boolean,
    val requiresRealWorldExecution: Boolean
)

/**
 * Result returned when NemoClaw is a valid match for the submitted task.
 *
 * @property contractorId Always [NemoclawContractorDefinition.CONTRACTOR_ID].
 * @property score        Deterministic fitness score (0–100). See [NemoclawMatcher.match].
 */
data class NemoclawMatchResult(
    val contractorId: String,
    val score:        Int
)

/**
 * NemoclawMatcher — deterministic matching engine for the NemoClaw contractor.
 *
 * MATCHING STEPS (executed in strict order):
 *
 * 1. FEASIBILITY CHECK (mandatory pre-gate)
 *    Fails if:
 *      - task.requiresSandbox != true
 *      - task.requiresExecution != true
 *      - task.policy is null or blank
 *      - task.outputType != "ContractReport"
 *
 * 2. CAPABILITY MATCH
 *    Passes only when task.type is one of:
 *      agent_execution | code_execution | sandbox_execution
 *
 * 3. CONSTRAINT CHECK
 *    Fails if:
 *      - task.requiresAutonomy == true
 *      - task.requiresRealWorldExecution == true
 *
 * 4. FITNESS SCORE (deterministic, no heuristics)
 *    +50 if sandbox required
 *    +30 if execution task
 *    +20 if policy provided
 *    Max possible = 100
 *
 * 5. RETURN
 *    On pass → [NemoclawMatchResult] with contractorId and computed score
 *    On fail → null
 *
 * RULES:
 *  - Pure function: same inputs always produce the same output.
 *  - No state. No randomness. No fallback.
 */
class NemoclawMatcher {

    companion object {
        private val ACCEPTED_TASK_TYPES: Set<String> = setOf(
            NemoclawCapabilities.AGENT_EXECUTION,
            NemoclawCapabilities.CODE_EXECUTION,
            "sandbox_execution"
        )

        private const val SCORE_SANDBOX_REQUIRED = 50
        private const val SCORE_EXECUTION_TASK   = 30
        private const val SCORE_POLICY_PROVIDED  = 20
    }

    /**
     * Evaluate [task] against the NemoClaw contractor's capability and constraint surface.
     *
     * @param task The task to evaluate.
     * @return [NemoclawMatchResult] when NemoClaw is a valid match; null otherwise.
     */
    fun match(task: NemoclawMatchingTask): NemoclawMatchResult? {

        // ── STEP 1: FEASIBILITY CHECK ─────────────────────────────────────────
        if (!task.requiresSandbox)               return null
        if (!task.requiresExecution)             return null
        if (task.policy.isNullOrBlank())         return null
        if (task.outputType != "ContractReport") return null

        // ── STEP 2: CAPABILITY MATCH ──────────────────────────────────────────
        // ACCEPTED_TASK_TYPES is the single authoritative set of accepted task categories.
        if (task.type !in ACCEPTED_TASK_TYPES)   return null

        // ── STEP 3: CONSTRAINT CHECK ──────────────────────────────────────────
        if (task.requiresAutonomy)               return null
        if (task.requiresRealWorldExecution)     return null

        // ── STEP 4: FITNESS SCORE ─────────────────────────────────────────────
        // All feasibility conditions are guaranteed true at this point by STEP 1.
        val score = SCORE_SANDBOX_REQUIRED + SCORE_EXECUTION_TASK + SCORE_POLICY_PROVIDED

        // ── STEP 5: RETURN ────────────────────────────────────────────────────
        return NemoclawMatchResult(
            contractorId = NemoclawContractorDefinition.CONTRACTOR_ID,
            score        = score
        )
    }
}
