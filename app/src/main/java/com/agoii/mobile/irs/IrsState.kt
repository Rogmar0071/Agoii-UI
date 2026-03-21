package com.agoii.mobile.irs

/**
 * IRS-02 State Models
 *
 * All types used by the deterministic orchestration layer. These extend the IRS-01
 * governance models to support step-by-step execution, agent isolation, and full
 * traceability of every decision made during intent resolution.
 */

/**
 * Lifecycle status of a single intent resolution session.
 * The orchestrator advances through these states ONE step per invocation.
 *
 * Terminal stop states: NEEDS_CLARIFICATION, UNSTABLE, INFEASIBLE, REJECTED, CERTIFIED.
 */
enum class SessionStatus {
    /** Session created; no processing done yet. */
    INITIATED,
    /** Step 1 (reconstruct) done; IntentDraft available. */
    RECONSTRUCTED,
    /** Step 2 (detect_gaps): gaps found — STOP, clarification required. */
    NEEDS_CLARIFICATION,
    /** Step 2 passed (no gaps); ready for Step 3 (run_scouts). */
    SCOUTING,
    /** Step 3 (run_scouts) done; evidence collected, ready for Step 4 (swarm_validate). */
    SWARM_PENDING,
    /** Step 4 (swarm_validate): conflicts detected — STOP. */
    UNSTABLE,
    /** Step 4 passed (consensus); ready for Step 5 (simulate). */
    SIMULATION_PENDING,
    /** Step 5 (simulate): infeasible — STOP. */
    INFEASIBLE,
    /** Step 5 passed; ready for Step 6 (PCCV). */
    PCCV_PENDING,
    /** Step 6 (PCCV): violations found — STOP. */
    REJECTED,
    /** Step 7: all gates passed; intent is certified. */
    CERTIFIED
}

/**
 * Structured output from a single Knowledge Scout agent.
 *
 * Agents MUST return:
 *  - structured output
 *  - confidence score (0.0–1.0)
 *  - reasoning trace
 *
 * Agents MUST NOT mutate state or bypass the orchestrator.
 */
data class ScoutEvidence(
    /** The intent field this evidence relates to. */
    val field: String,
    /** Canonical source identifier (e.g. "environment_lookup", "dependency_check"). */
    val source: String,
    /** Human-readable finding from the scout. */
    val content: String,
    /** Confidence score for this finding, in [0.0, 1.0]. */
    val confidence: Float,
    /** Step-by-step reasoning trace from the scout agent. */
    val reasoningTrace: String
)

/**
 * Aggregated result from the Swarm Validator (N≥2 independent passes).
 * Merging is deterministic — majority agreement is NOT used.
 * Any single conflict makes [consistent] = false.
 */
data class ConsensusResult(
    /** True only if ALL independent passes agree and find no contradictions. */
    val consistent: Boolean,
    /** Number of passes that succeeded. */
    val passCount: Int,
    /** Total number of passes run. */
    val totalPasses: Int,
    /** Conflict descriptions from any failing pass. */
    val conflicts: List<String>
)

/**
 * Result of the Simulation Engine's feasibility check.
 * Every failure is explicitly recorded in [failurePoints]; no soft failures allowed.
 */
data class SimulationOutcome(
    val feasible: Boolean,
    val failurePoints: List<String>
)

/**
 * Result of the PCCV gate — the final mandatory checkpoint.
 * All five dimensions must pass for [pass] to be true.
 * Every violation is recorded with an explicit description.
 */
data class PccvResult(
    val pass: Boolean,
    val completeness: Boolean,
    val consistency: Boolean,
    val feasibility: Boolean,
    val nonAssumption: Boolean,
    val reproducibility: Boolean,
    /** Human-readable description of each failing dimension. */
    val violations: List<String>
)

/**
 * Structured clarification request returned to the user when IRS-02 cannot
 * proceed due to missing or ambiguous information.
 *
 * The user MUST address all items before re-submitting.
 */
data class ClarificationRequest(
    val sessionId: String,
    /** Fields that are absent from the submitted intent. */
    val missingFields: List<String>,
    /** Fields that appear ambiguous or contradictory. */
    val ambiguousFields: List<String>,
    /** Swarm-detected conflicts, if any. */
    val conflicts: List<String>,
    /** Human-readable prompt describing exactly what the user must provide. */
    val requiredInput: String
)

/**
 * The single source of truth for a single intent resolution session.
 *
 * Rules (enforced by [IntentStateManager]):
 *  - Append-only updates — no field is ever overwritten.
 *  - Full traceability — every append is logged in [stepLog].
 *  - All state must live here; orchestrator components hold no hidden state.
 */
data class IntentState(
    val sessionId: String,
    val rawInput: String,
    val optionalContext: Map<String, Any>,
    val status: SessionStatus,
    /** Structured intent draft produced by the Reconstruction Engine. Null until step 1 completes. */
    val intentDraft: IntentDraft? = null,
    /** Missing/ambiguous fields identified by the Gap Detector. */
    val gaps: List<String> = emptyList(),
    /** Evidence items collected by the Knowledge Scout Orchestrator. */
    val evidence: List<ScoutEvidence> = emptyList(),
    /** Append-only log of all validation reports and stage transitions. */
    val stepLog: List<String> = emptyList(),
    /** Swarm validation consensus result. Null until step 4 completes. */
    val consensusResult: ConsensusResult? = null,
    /** Simulation feasibility result. Null until step 5 completes. */
    val simulationOutcome: SimulationOutcome? = null,
    /** PCCV gate result. Null until step 6 completes. */
    val pccvResult: PccvResult? = null,
    /** Clarification request when status == NEEDS_CLARIFICATION. */
    val clarificationRequest: ClarificationRequest? = null,
    /** Certified intent produced by the Certification Emitter. Non-null only when CERTIFIED. */
    val certifiedIntent: CertifiedIntent? = null
)

/**
 * Result returned by [IrsOrchestrator.step] and [IrsOrchestrator.process].
 *
 * - [Advanced]: one stage completed; call [IrsOrchestrator.step] again to continue.
 * - [NeedsClarification]: gap detection stopped the pipeline; user must provide more information.
 * - [Rejected]: a non-recoverable failure (unstable, infeasible, or PCCV violation).
 * - [Certified]: all stages passed; [CertifiedIntent] is ready to submit to core.
 * - [AlreadyTerminal]: the session is in a terminal state; no further stepping is possible.
 */
sealed class OrchestratorResult {
    data class Advanced(val state: IntentState, val step: String) : OrchestratorResult()
    data class NeedsClarification(
        val request: ClarificationRequest,
        val state: IntentState
    ) : OrchestratorResult()
    data class Rejected(val state: IntentState, val reason: String) : OrchestratorResult()
    data class Certified(val intent: CertifiedIntent, val state: IntentState) : OrchestratorResult()
    data class AlreadyTerminal(val state: IntentState) : OrchestratorResult()
}
