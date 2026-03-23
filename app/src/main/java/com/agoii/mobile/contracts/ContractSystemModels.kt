package com.agoii.mobile.contracts

// ─── Contract System Consolidation — Foundation Models ───────────────────────
//
// Extends the engine-layer models with the full measurement + alignment stack:
//   Intent Layer → Scoring Layer → Classification → Agent Alignment

// ─── Intent Layer ─────────────────────────────────────────────────────────────

/**
 * Result of the [ObjectiveValidator] pass.
 *
 * @property valid   true only when all three objective checks pass.
 * @property reasons Ordered list of failure reasons (empty when [valid] = true).
 */
data class ObjectiveValidationResult(
    val valid:   Boolean,
    val reasons: List<String>
)

// ─── Traceability ─────────────────────────────────────────────────────────────

/**
 * Mapping of a single execution step back to the intent that originated it.
 *
 * @property step            The execution step being traced.
 * @property intentReference Human-readable description of the intent keyword/rule
 *                           that caused this step to be included.
 * @property isMapped        true when a valid intent reference was found.
 */
data class StepMapping(
    val step:            ExecutionStep,
    val intentReference: String,
    val isMapped:        Boolean
)

/**
 * Output of the [TraceabilityEnforcer] pass.
 *
 * A contract passes traceability when every execution step is backed by a
 * concrete intent keyword — no extra steps, no missing steps.
 *
 * @property passed        true when all steps are mapped to intent.
 * @property stepMappings  One entry per step in the execution plan.
 * @property unmappedSteps Steps that could not be traced to the intent.
 */
data class TraceabilityResult(
    val passed:        Boolean,
    val stepMappings:  List<StepMapping>,
    val unmappedSteps: List<ExecutionStep>
)

// ─── Mathematical Scoring Layer ───────────────────────────────────────────────

/**
 * Complete mathematical score for a contract derivation.
 *
 * Formulas:
 *   EL  = surfaceWeight + executionCount + (2 × conditionCount)
 *   RS  = Σ (severity × likelihood)  per failure
 *   CCF = Σ of five dimension scores (0–3 each), total 0–15
 *
 * @property executionLoad    EL — quantifies how much execution capacity is consumed.
 * @property riskScore        RS — quantifies predicted failure impact.
 * @property confidenceIndex  CCF — quantifies structural integrity (higher = more reliable).
 */
data class ContractScore(
    val executionLoad:   Int,
    val riskScore:       Int,
    val confidenceIndex: Int
) {
    init {
        require(executionLoad   >= 0) { "executionLoad must be ≥ 0" }
        require(riskScore       >= 0) { "riskScore must be ≥ 0" }
        require(confidenceIndex in 0..15) { "confidenceIndex must be in [0, 15]" }
    }
}

// ─── Classification ───────────────────────────────────────────────────────────

/**
 * Risk classification of a contract derived from its [ContractScore].
 *
 * Classification table (boolean rule, no threshold overlap):
 *   LOW    — EL ≤ 6  AND RS ≤ 4  AND CCF ≥ 10  → safe for standard execution.
 *   HIGH   — EL > 10 OR  RS > 8  OR  CCF < 5   → requires high-fidelity agent.
 *   MEDIUM — all other cases                    → requires controlled execution.
 */
enum class ContractClassification { LOW, MEDIUM, HIGH }

/**
 * A scored and classified contract — the fully measured artifact.
 *
 * Wraps the structural [ContractDerivation] with the measurement layer.
 *
 * @property derivation      Structural output from the [ContractEngine].
 * @property score           Mathematical score (EL + RS + CCF).
 * @property classification  Risk classification (LOW / MEDIUM / HIGH).
 * @property traceability    Step-to-intent mapping audit result.
 */
data class ScoredContract(
    val derivation:     ContractDerivation,
    val score:          ContractScore,
    val classification: ContractClassification,
    val traceability:   TraceabilityResult
)

// ─── Agent Capability Model ───────────────────────────────────────────────────

/**
 * Capability profile of an agent that may execute a contract.
 *
 * All dimensions are scored 0–3 (higher = more capable), except [driftTendency]
 * where lower means more reliable.
 *
 * @property agentId              Unique identifier for this agent.
 * @property constraintObedience  How reliably the agent follows constraints (0–3).
 * @property structuralAccuracy   How accurately the agent follows structure (0–3).
 * @property driftTendency        How likely the agent is to deviate (0–3; 0 = no drift).
 * @property complexityHandling   Ability to handle complex, multi-step plans (0–3).
 * @property outputReliability    Consistency and determinism of output (0–3).
 */
data class AgentProfile(
    val agentId:             String,
    val constraintObedience: Int,
    val structuralAccuracy:  Int,
    val driftTendency:       Int,
    val complexityHandling:  Int,
    val outputReliability:   Int
) {
    init {
        require(constraintObedience in 0..3) { "constraintObedience must be in [0, 3]" }
        require(structuralAccuracy  in 0..3) { "structuralAccuracy must be in [0, 3]" }
        require(driftTendency       in 0..3) { "driftTendency must be in [0, 3]" }
        require(complexityHandling  in 0..3) { "complexityHandling must be in [0, 3]" }
        require(outputReliability   in 0..3) { "outputReliability must be in [0, 3]" }
    }

    /**
     * Effective capability score — inverting driftTendency so lower drift
     * contributes positively.  Max = 15.
     */
    val capabilityScore: Int get() =
        constraintObedience + structuralAccuracy + (3 - driftTendency) +
        complexityHandling  + outputReliability
}

// ─── Agent Matching ───────────────────────────────────────────────────────────

/** Result of an agent–contract match evaluation. */
enum class AgentMatchDecision { ACCEPT, ADAPT, REJECT }

/**
 * Full result of an agent-matching evaluation.
 *
 * @property decision       ACCEPT / ADAPT / REJECT.
 * @property agentProfile   The agent profile that was evaluated.
 * @property scoredContract The contract that was matched against.
 * @property reasons        Ordered list of matching reasons (trace).
 */
data class AgentMatchResult(
    val decision:       AgentMatchDecision,
    val agentProfile:   AgentProfile,
    val scoredContract: ScoredContract,
    val reasons:        List<String>
)

// ─── Contract Adaptation ──────────────────────────────────────────────────────

/**
 * A single adaptation applied to a step during contract adaptation.
 *
 * @property originalStep   Step as it appeared in the original plan.
 * @property adaptedSteps   Replacement steps (may be one step per original, or
 *                          multiple when the original was split).
 * @property adaptationNote Human-readable description of what changed and why.
 */
data class StepAdaptation(
    val originalStep:   ExecutionStep,
    val adaptedSteps:   List<ExecutionStep>,
    val adaptationNote: String
)

/**
 * Result of the [ContractAdapter] pass when a contract required adaptation.
 *
 * The adapted plan replaces the original plan in downstream execution.
 *
 * @property original        The [ScoredContract] before adaptation.
 * @property adaptedPlan     Replacement execution plan (steps may be split/rewritten).
 * @property adaptations     Per-step adaptation records.
 * @property adaptationNotes Summary trace of every change made.
 */
data class AdaptedContract(
    val original:         ScoredContract,
    val adaptedPlan:      ExecutionPlan,
    val adaptations:      List<StepAdaptation>,
    val adaptationNotes:  List<String>
)

// ─── System Orchestration Result ──────────────────────────────────────────────

/**
 * Terminal result of the full [ContractSystemOrchestrator] pipeline.
 *
 * Captures the outcome of every stage from objective validation through to
 * the agent alignment decision, including any structural adaptation applied.
 *
 * @property objectiveValidation Outcome of the Intent Layer (Step 0).
 * @property scoredContract      Fully scored and classified contract (null if
 *                               objective validation failed or engine rejected).
 * @property matchResult         Agent–contract alignment result (null if no scored contract).
 * @property adaptedContract     Structural adaptation result (null if no adaptation needed).
 * @property readyForExecution   true only when the system is in a state where
 *                               execution may safely proceed.
 */
data class ContractSystemResult(
    val objectiveValidation: ObjectiveValidationResult,
    val scoredContract:      ScoredContract?,
    val matchResult:         AgentMatchResult?,
    val adaptedContract:     AdaptedContract?,
    val readyForExecution:   Boolean
)
