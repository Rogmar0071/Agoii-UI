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

// ─── Reality Validation ───────────────────────────────────────────────────────

/**
 * Graded risk level produced by the REALITY_VALIDATION stage.
 *
 * LOW    — all reality checks pass: credibility acceptable, no contradictions, simulation feasible.
 * MEDIUM — credibility concern: at least one field below acceptance threshold.
 * HIGH   — hard failure: contradiction detected OR simulation infeasible; intent cannot be certified.
 */
enum class RiskLevel { LOW, MEDIUM, HIGH }

/**
 * A single traceable fact retrieved from the reality knowledge gateway.
 *
 * @property domain          The knowledge domain this fact belongs to (e.g. "environment", "dependency").
 * @property claim           Human-readable factual statement.
 * @property credibilityScore How credible this fact is; 0.0 = unreliable, 1.0 = fully authoritative.
 * @property source          Identifier of the knowledge source that provided this fact.
 */
data class KnowledgeFact(
    val domain:           String,
    val claim:            String,
    val credibilityScore: Double,
    val source:           String
) {
    init {
        require(credibilityScore in 0.0..1.0) {
            "credibilityScore must be in [0.0, 1.0], got $credibilityScore"
        }
    }
}

/**
 * Output produced by the credibility scorer.
 *
 * @property overallScore        Weighted average credibility across all mandatory fields (0.0–1.0).
 * @property fieldScores         Per-field credibility score keyed by field name.
 * @property lowCredibilityFields Names of fields whose score is below the acceptance threshold (< 0.5).
 */
data class CredibilityReport(
    val overallScore:         Double,
    val fieldScores:          Map<String, Double>,
    val lowCredibilityFields: List<String>
) {
    /** true when [overallScore] is ≥ 0.5 and no individual field is below the threshold. */
    val isAcceptable: Boolean get() =
        overallScore >= 0.5 && lowCredibilityFields.isEmpty()

    /**
     * Traceable reasons describing any credibility failure.
     * Empty when [isAcceptable] is true.
     */
    val reasons: List<String> get() = buildList {
        if (!isAcceptable) {
            add(
                "credibility: overall score %.2f".format(overallScore) +
                if (lowCredibilityFields.isNotEmpty())
                    "; low-credibility fields: ${lowCredibilityFields.joinToString(", ")}"
                else ""
            )
        }
    }
}

/**
 * A single contradiction detected between two intent fields or evidence sources.
 *
 * @property fieldA      First field involved in the contradiction.
 * @property fieldB      Second field involved in the contradiction.
 * @property description Human-readable description of why these values conflict.
 */
data class Contradiction(
    val fieldA:      String,
    val fieldB:      String,
    val description: String
)

/**
 * Output produced by the contradiction detector.
 *
 * @property hasContradictions true when at least one contradiction was found.
 * @property contradictions    All detected contradictions (empty when [hasContradictions] = false).
 */
data class ContradictionReport(
    val hasContradictions: Boolean,
    val contradictions:    List<Contradiction>
) {
    /**
     * Traceable reasons for each detected contradiction.
     * Empty when [hasContradictions] is false.
     */
    val reasons: List<String> get() = contradictions.map { c ->
        "contradiction: [${c.fieldA}] × [${c.fieldB}] — ${c.description}"
    }
}

/**
 * Terminal output of the REALITY_VALIDATION stage.
 *
 * Graded (non-binary) output conforming to the IRS-05C system law:
 *  - [valid]     is the authoritative pass/fail flag.
 *  - [riskLevel] grades the severity: LOW / MEDIUM / HIGH (boolean rule table; no thresholds).
 *  - [confidence] equals [CredibilityReport.overallScore] directly; no adjustment.
 *  - [reasons]   is a flat, traceable list of all failure descriptions, collected
 *    from sub-module outputs — no logic is duplicated here.
 *
 * IRS-05C-AUDIT: Legacy aliases [passed] and [issues] are formally deprecated.
 * All callers MUST migrate to [valid] and [reasons] respectively.
 *
 * @property valid               true only when all reality checks pass (reasons is empty).
 * @property riskLevel           Graded risk level.
 * @property confidence          Credibility confidence (0.0–1.0); equals credibilityReport.overallScore.
 * @property reasons             All traceable failure/concern descriptions.
 * @property credibilityReport   Detailed per-field credibility scores.
 * @property contradictionReport Detected cross-field contradictions.
 * @property simulationResult    Real-world feasibility simulation output.
 */
data class RealityValidationResult(
    val valid:               Boolean,
    val riskLevel:           RiskLevel,
    val confidence:          Double,
    val reasons:             List<String>,
    val credibilityReport:   CredibilityReport,
    val contradictionReport: ContradictionReport,
    val simulationResult:    RealitySimulationResult
) {
    init {
        require(confidence in 0.0..1.0) { "confidence must be in [0.0, 1.0], got $confidence" }
    }

    /**
     * Deprecated alias for [valid].
     * IRS-05C-AUDIT: migrate all callers to [valid].
     */
    @Deprecated("IRS-05C-AUDIT: use valid", ReplaceWith("valid"))
    val passed: Boolean get() = valid

    /**
     * Deprecated alias for [reasons].
     * IRS-05C-AUDIT: migrate all callers to [reasons].
     */
    @Deprecated("IRS-05C-AUDIT: use reasons", ReplaceWith("reasons"))
    val issues: List<String> get() = reasons
}

/**
 * A single typed record of a constraint-rule evaluation produced during simulation.
 *
 * IRS-05C: replaces the string-based ruleTrace with a strict, machine-parseable schema.
 * Every rule evaluation MUST produce exactly one [SimulationRuleResult], whether it
 * fires (triggered = true) or not (triggered = false).
 *
 * @property ruleId      Unique identifier of the constraint rule (e.g. "RES-01").
 * @property description Human-readable description of what the rule checks.
 * @property triggered   true when the rule detected a violation; false when the rule passed.
 * @property message     Non-null failure description when [triggered] is true; null when PASS.
 */
data class SimulationRuleResult(
    val ruleId:      String,
    val description: String,
    val triggered:   Boolean,
    val message:     String?
) {
    init {
        require((triggered && message != null) || (!triggered && message == null)) {
            "triggered and message must be consistent for rule $ruleId: triggered=$triggered but message=${if (message == null) "null" else "non-null"}"
        }
    }
}

/**
 * Output produced by [com.agoii.mobile.irs.reality.RealitySimulationEngine].
 *
 * IRS-05C: strict schema — all simulation state is fully typed and machine-parseable.
 *
 * @property feasible            true when no constraint rule was triggered.
 * @property failurePoints       Human-readable descriptions of triggered rule violations.
 *                               Derived from [evaluations]; no additional state.
 * @property constraintsChecked  Total number of rules evaluated (= [evaluations].size).
 * @property evaluations         Ordered typed record for every rule evaluation, PASS or FAIL.
 *                               This is the authoritative traceability artifact; every rule
 *                               evaluation is present and machine-parseable.
 */
data class RealitySimulationResult(
    val feasible:            Boolean,
    val failurePoints:       List<String>,
    val constraintsChecked:  Int,
    val evaluations:         List<SimulationRuleResult>
) {
    init {
        require(constraintsChecked == evaluations.size) {
            "constraintsChecked ($constraintsChecked) must equal evaluations.size (${evaluations.size})"
        }
        require(feasible == evaluations.none { it.triggered }) {
            "feasible must equal evaluations.none { triggered }"
        }
    }
}

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
 * Standardized failure classification for all IRS rejection paths.
 *
 * IRS-05C-AUDIT: Every [OrchestratorResult.Rejected.reason] MUST map to exactly one
 * value of this enum. No raw string failure codes are permitted.
 *
 * All rejection paths:
 *  - [EVIDENCE_INVALID]      — evidence validation failed (step 4)
 *  - [REALITY_UNVERIFIABLE]  — reality validation failed (step 5)
 *  - [UNSTABLE]              — swarm validation failed; agents did not reach consensus (step 6)
 *  - [INFEASIBLE]            — simulation infeasible; real-world constraints cannot be met (step 7)
 *  - [PCCV_FAIL]             — precondition/certification-condition validation failed (step 8)
 */
enum class FailureType {
    EVIDENCE_INVALID,
    REALITY_UNVERIFIABLE,
    UNSTABLE,
    INFEASIBLE,
    PCCV_FAIL
}

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
     * @property reason  Machine-readable rejection code; always equals [FailureType.name]
     *                   for one of the standardized failure types.
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
    REALITY_VALIDATION,
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
