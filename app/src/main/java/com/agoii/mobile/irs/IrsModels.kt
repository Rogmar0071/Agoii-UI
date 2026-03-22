package com.agoii.mobile.irs

// ─── Evidence ────────────────────────────────────────────────────────────────

/** A single traceable reference to an external evidence item. */
data class EvidenceRef(val id: String, val source: String)

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
