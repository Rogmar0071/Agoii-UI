package com.agoii.mobile.irs

// ─── Evidence ────────────────────────────────────────────────────────────────

/** A single traceable reference to an external evidence item. */
data class EvidenceRef(val id: String, val source: String)

// ─── Knowledge Scout models ───────────────────────────────────────────────────

/** Classifies the domain covered by a scout run. */
enum class ScoutType { ENVIRONMENT, DEPENDENCY, CONSTRAINT }

/**
 * A single finding produced by a knowledge scout.
 *
 * @property description Human-readable description of the finding.
 * @property severity    One of: INFO, WARNING, ERROR.
 */
data class Finding(val description: String, val severity: String = "INFO")

/**
 * Structured output produced by each knowledge scout.
 *
 * Rules:
 *  - [confidence] must be in [0.0, 1.0].
 *  - When the scout cannot verify something it MUST set confidence ≤ 0.4 (LOW).
 *  - [sourceTrace] lists every data point used to derive [findings].
 *
 * @property type        Which domain was scouted.
 * @property findings    Ordered list of findings (may be empty when nothing was detected).
 * @property confidence  Overall scout confidence; 0.0 = completely unknown, 1.0 = fully verified.
 * @property sourceTrace Ordered list of source identifiers consulted during scouting.
 */
data class ScoutEvidence(
    val type:        ScoutType,
    val findings:    List<Finding>,
    val confidence:  Double,
    val sourceTrace: List<String>
) {
    init {
        require(confidence in 0.0..1.0) { "confidence must be in [0.0, 1.0], got $confidence" }
    }
}

/** Aggregate of all scout results for a session. */
data class KnowledgeScoutReport(
    val environment: ScoutEvidence,
    val dependency:  ScoutEvidence,
    val constraint:  ScoutEvidence
) {
    /** true when every scout returned at least MEDIUM confidence (> 0.4). */
    val allReliable: Boolean get() =
        environment.confidence > 0.4 && dependency.confidence > 0.4 && constraint.confidence > 0.4

    /** Collect all findings rated ERROR across all scouts. */
    val errors: List<Finding> get() =
        (environment.findings + dependency.findings + constraint.findings)
            .filter { it.severity == "ERROR" }
}

// ─── Evidence Validation ──────────────────────────────────────────────────────

/**
 * Output produced by [EvidenceValidator].
 *
 * Validation dimensions (all must pass for [valid] = true):
 *  1. Presence   — ≥ 1 EvidenceRef per mandatory field (pre-checked by GapDetector).
 *  2. Relevance  — each EvidenceRef source is recognisably related to the field it backs.
 *  3. Coverage   — evidence collectively covers the non-trivial content of the field value.
 *  4. Consistency — evidence refs across fields do not contradict each other.
 *
 * @property valid  true only when all four dimensions pass.
 * @property issues Descriptions of every violation detected (empty when [valid] = true).
 */
data class EvidenceValidationResult(
    val valid:  Boolean,
    val issues: List<String>
)

// ─── Intent ──────────────────────────────────────────────────────────────────

/**
 * A single intent field carrying both its value and the evidence that backs it.
 * Mandatory fields: objective, constraints, environment, resources.
 * Each must carry ≥ 1 [EvidenceRef] to pass PCCV.
 */
data class IntentField(
    val value: String,
    val evidence: List<EvidenceRef>
)

/** Fully structured intent with evidence-backed mandatory fields. */
data class IntentData(
    val objective:   IntentField,
    val constraints: IntentField,
    val environment: IntentField,
    val resources:   IntentField
)

// ─── Swarm ───────────────────────────────────────────────────────────────────

/** Governs how the agent swarm is assembled and how consensus is evaluated. */
enum class ConsensusRule { UNANIMOUS, MAJORITY, WEIGHTED }

/**
 * Swarm configuration.
 *
 * @property agentCount    Number of agents to run; must be ≥ 2.
 * @property consensusRule Rule used to determine consistency from individual outputs.
 */
data class SwarmConfig(
    val agentCount:    Int,
    val consensusRule: ConsensusRule
)

/**
 * Output produced by [SwarmValidator].
 *
 * @property consistent   true when the chosen consensus rule is satisfied.
 * @property conflicts    Human-readable descriptions of detected conflicts.
 * @property agentOutputs Per-agent textual result (one entry per agent).
 */
data class SwarmResult(
    val consistent:   Boolean,
    val conflicts:    List<String>,
    val agentOutputs: List<String>
)

// ─── Simulation ───────────────────────────────────────────────────────────────

/**
 * Output produced by [SimulationEngine].
 *
 * @property feasible      true when all real-world constraints can be met.
 * @property failurePoints Descriptions of detected execution failure points.
 */
data class SimulationResult(
    val feasible:      Boolean,
    val failurePoints: List<String>
)

// ─── PCCV ─────────────────────────────────────────────────────────────────────

/**
 * Output produced by [PCCVValidator].
 *
 * @property passed           true when all preconditions are satisfied.
 * @property evidenceCoverage true when every mandatory field has ≥ 1 EvidenceRef.
 * @property errors           All precondition failures detected.
 */
data class PCCVResult(
    val passed:           Boolean,
    val evidenceCoverage: Boolean,
    val errors:           List<String>
)

// ─── Orchestrator output ──────────────────────────────────────────────────────

/**
 * Terminal output of the IRS.  The UI interprets this result independently
 * and must not depend on any IRS-internal state.
 */
sealed class OrchestratorResult {
    /** Intent is fully certified; no assumptions remain. */
    object Certified : OrchestratorResult()

    /** One or more mandatory fields lack sufficient evidence. */
    data class NeedsClarification(val gaps: List<String>) : OrchestratorResult()

    /**
     * Certification rejected.
     *
     * @property reason  Machine-readable rejection code (e.g. "UNSTABLE", "INFEASIBLE", "PCCV_FAIL").
     * @property details Human-readable explanations.
     */
    data class Rejected(val reason: String, val details: List<String> = emptyList()) : OrchestratorResult()
}

// ─── Session & State ─────────────────────────────────────────────────────────

/** Ordered execution stages of the IRS pipeline. */
enum class IrsStage {
    RECONSTRUCTION,
    GAP_DETECTION,
    SCOUTING,
    EVIDENCE_VALIDATION,
    SWARM_VALIDATION,
    SIMULATION,
    PCCV,
    CERTIFICATION
}

/**
 * Immutable snapshot appended to the session history after each stage completes.
 *
 * @property stage              The stage that was executed.
 * @property orchestratorResult Non-null only when this snapshot represents a terminal halt.
 * @property timestamp          Wall-clock milliseconds at snapshot creation.
 */
data class IrsSnapshot(
    val stage:               IrsStage,
    val orchestratorResult:  OrchestratorResult?,
    val timestamp:           Long = System.currentTimeMillis()
)

/**
 * Immutable view of an IRS session at a point in time.
 *
 * @property sessionId   Unique identifier for this session.
 * @property intentData  Current intent (may be enriched by scouting).
 * @property swarmConfig Swarm parameters in effect for the session.
 * @property history     Append-only ordered list of stage snapshots.
 */
data class IrsSession(
    val sessionId:   String,
    val intentData:  IntentData,
    val swarmConfig: SwarmConfig,
    val history:     List<IrsSnapshot>
)

/**
 * Result returned by [IrsOrchestrator.step].
 *
 * @property executedStage      The stage that was executed in this step.
 * @property session            Updated session (history appended).
 * @property terminal           true when orchestration has reached a final state.
 * @property orchestratorResult Non-null when [terminal] is true.
 */
data class StepResult(
    val executedStage:      IrsStage,
    val session:            IrsSession,
    val terminal:           Boolean,
    val orchestratorResult: OrchestratorResult?
)
