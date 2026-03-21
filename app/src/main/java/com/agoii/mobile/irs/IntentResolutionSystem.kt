package com.agoii.mobile.irs

import java.util.UUID

/**
 * IRS-01 — Intent Resolution System
 *
 * External pre-core module that transforms raw user input into a Certified Intent
 * before ANY interaction with the Agoii Core governor.
 *
 * Position: OUTSIDE Agoii Core / BEFORE intent_submitted
 * Authority: Pre-entry only — core governor remains final execution authority.
 *
 * Execution graph (deterministic — NO step may be skipped):
 *   intent_capture
 *     → intent_reconstruction
 *     → intent_gap_detection
 *     → swarm_validation
 *     → simulation_validation
 *     → PCCV_gate
 *     → intent_certified  OR  intent_rejected
 *
 * Hard prohibitions (IRS MUST NOT):
 *   - modify core modules
 *   - bypass the PCCV gate
 *   - auto-fill missing fields
 *   - assume defaults silently
 *   - generate contracts
 *   - execute actions
 */
object IntentResolutionSystem {

    // ── Required field names (all six must be present for certification) ─────

    internal val REQUIRED_FIELDS: List<String> = listOf(
        "objective",
        "success_criteria",
        "constraints",
        "environment",
        "resources",
        "acceptance_boundary"
    )

    // ── Keyword aliases accepted during reconstruction ────────────────────────

    private val FIELD_ALIASES: Map<String, List<String>> = mapOf(
        "objective"           to listOf("objective", "goal", "aim", "purpose"),
        "success_criteria"    to listOf("success_criteria", "success", "done_when", "criteria"),
        "constraints"         to listOf("constraints", "constraint", "limitations", "limits"),
        "environment"         to listOf("environment", "env", "platform", "context"),
        "resources"           to listOf("resources", "resource", "dependencies", "deps", "tools"),
        "acceptance_boundary" to listOf(
            "acceptance_boundary", "acceptance", "boundary",
            "exit_criteria", "done_condition"
        )
    )

    /** Minimum character length for a field to be considered substantive. */
    internal const val MIN_FIELD_LENGTH = 3

    /**
     * Placeholder tokens that indicate the user has not provided a real value.
     * All entries are lowercase; comparison is performed with `.lowercase()` at the call site,
     * so this covers case-insensitive variants (n/a, N/A, tbd, TBD, todo, TODO, xxx, XXX, etc.).
     */
    private val PLACEHOLDER_TOKENS = setOf("n/a", "none", "tbd", "todo", "xxx", "?", "-")

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC ENTRY POINT
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Process raw user input through the full IRS-01 pipeline.
     *
     * Input format (one field per line):
     * ```
     * objective: <text>
     * success_criteria: <text>
     * constraints: <text>
     * environment: <text>
     * resources: <text>
     * acceptance_boundary: <text>
     * ```
     * Aliases for each keyword are accepted (see [FIELD_ALIASES]).
     *
     * Returns [IrsResult.Certified] only when every pipeline stage passes.
     * Returns [IrsResult.Rejected] on any failure — never throws.
     */
    fun process(
        rawInput: String,
        optionalContext: Map<String, Any> = emptyMap()
    ): IrsResult {

        // ── Step 1: intent_capture ────────────────────────────────────────────
        val raw = RawIntent(rawInput.trim(), optionalContext)
        if (raw.rawInput.isBlank()) {
            return IrsResult.Rejected(
                failureState  = IrsFailureState.INTENT_INCOMPLETE,
                reason        = "Raw input is empty. No intent to process.",
                missingFields = REQUIRED_FIELDS
            )
        }

        // ── Step 2: intent_reconstruction ─────────────────────────────────────
        val draft = reconstruct(raw)

        // ── Step 3: intent_gap_detection ──────────────────────────────────────
        val gapReport = detectGaps(draft)
        if (!gapReport.isComplete) {
            return IrsResult.Rejected(
                failureState  = IrsFailureState.INTENT_INCOMPLETE,
                reason        = "Intent is incomplete. Missing required fields: " +
                    gapReport.missingFields.joinToString(", "),
                missingFields = gapReport.missingFields
            )
        }

        // ── Step 4: swarm_validation ──────────────────────────────────────────
        val swarmResult = runSwarmValidation(draft)
        if (!swarmResult.passed) {
            return IrsResult.Rejected(
                failureState = IrsFailureState.INTENT_UNSTABLE,
                reason       = "Swarm validation detected inconsistency: ${swarmResult.detail}"
            )
        }

        // ── Step 5: simulation_validation ─────────────────────────────────────
        val simResult = runSimulationValidation(draft)
        if (!simResult.passed) {
            return IrsResult.Rejected(
                failureState = IrsFailureState.INTENT_INFEASIBLE,
                reason       = "Simulation validation failed: ${simResult.detail}"
            )
        }

        // ── Step 6: PCCV gate ─────────────────────────────────────────────────
        val pccvReport = buildPccvReport(gapReport, swarmResult, simResult)
        if (!pccvReport.allPass) {
            val failed = buildList {
                if (!pccvReport.completeness)    add("COMPLETENESS")
                if (!pccvReport.consistency)     add("CONSISTENCY")
                if (!pccvReport.feasibility)     add("FEASIBILITY")
                if (!pccvReport.nonAssumption)   add("NON_ASSUMPTION")
                if (!pccvReport.reproducibility) add("REPRODUCIBILITY")
            }
            return IrsResult.Rejected(
                failureState = IrsFailureState.INTENT_INCONSISTENT,
                reason       = "PCCV gate failed on: ${failed.joinToString(", ")}"
            )
        }

        // ── Certification ─────────────────────────────────────────────────────
        val certified = CertifiedIntent(
            intentId           = draft.intentId,
            objective          = draft.objective!!,
            successCriteria    = draft.successCriteria!!,
            constraints        = draft.constraints!!,
            environment        = draft.environment!!,
            resources          = draft.resources!!,
            acceptanceBoundary = draft.acceptanceBoundary!!,
            evidenceRefs       = buildEvidenceRefs(draft),
            pccvReport         = pccvReport
        )
        return IrsResult.Certified(certified)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. INTENT RECONSTRUCTION ENGINE
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parses raw input into a structured [IntentDraft].
     *
     * Rules (strictly enforced):
     *  - No assumption filling.
     *  - Only explicit extraction from user-provided text.
     *  - Unknown or unrecognised lines are silently ignored (not defaulted).
     */
    internal fun reconstruct(raw: RawIntent): IntentDraft {
        val extracted = mutableMapOf<String, String>()

        for (line in raw.rawInput.lines()) {
            val trimmed   = line.trim()
            val colonIdx  = trimmed.indexOf(':')
            if (colonIdx < 1) continue

            val rawKey = trimmed.substring(0, colonIdx).trim()
                .lowercase()
                .replace(' ', '_')
            val value  = trimmed.substring(colonIdx + 1).trim()
            if (value.isBlank()) continue

            for ((canonical, aliases) in FIELD_ALIASES) {
                if (canonical in extracted) continue          // first occurrence wins
                if (aliases.any { it.replace(' ', '_') == rawKey }) {
                    extracted[canonical] = value
                    break
                }
            }
        }

        return IntentDraft(
            intentId           = UUID.randomUUID().toString(),
            rawInput           = raw.rawInput,
            objective          = extracted["objective"],
            successCriteria    = extracted["success_criteria"],
            constraints        = extracted["constraints"],
            environment        = extracted["environment"],
            resources          = extracted["resources"],
            acceptanceBoundary = extracted["acceptance_boundary"]
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. GAP DETECTION ENGINE
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Identifies every required field that is missing from [draft].
     * A field is considered missing if it is null, blank, or shorter than
     * [MIN_FIELD_LENGTH] characters.
     */
    internal fun detectGaps(draft: IntentDraft): GapReport {
        val missing = mutableListOf<String>()

        fun check(name: String, value: String?) {
            if (value.isNullOrBlank() || value.length < MIN_FIELD_LENGTH) missing += name
        }

        check("objective",           draft.objective)
        check("success_criteria",    draft.successCriteria)
        check("constraints",         draft.constraints)
        check("environment",         draft.environment)
        check("resources",           draft.resources)
        check("acceptance_boundary", draft.acceptanceBoundary)

        return GapReport(missingFields = missing)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. SWARM VALIDATION LAYER
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Runs three independent reasoning passes to detect contradictions and
     * hidden assumptions.
     *
     * Pass 1 — Field distinctness: objective must differ from success_criteria.
     * Pass 2 — Domain coherence:   constraints must differ from resources.
     * Pass 3 — Boundary coherence: acceptance_boundary must differ from objective.
     *
     * Disagreement on any pass → [SwarmResult.passed] = false → INTENT_UNSTABLE.
     */
    internal fun runSwarmValidation(draft: IntentDraft): SwarmResult {
        val obj  = draft.objective          ?: return SwarmResult(false, "Pass 1: objective is absent")
        val sc   = draft.successCriteria    ?: return SwarmResult(false, "Pass 1: success_criteria is absent")
        val cons = draft.constraints        ?: return SwarmResult(false, "Pass 2: constraints is absent")
        val res  = draft.resources          ?: return SwarmResult(false, "Pass 2: resources is absent")
        val ab   = draft.acceptanceBoundary ?: return SwarmResult(false, "Pass 3: acceptance_boundary is absent")

        // Pass 1: objective and success_criteria must be distinct definitions
        if (obj.lowercase() == sc.lowercase()) {
            return SwarmResult(
                false,
                "Pass 1: objective and success_criteria are identical — violates distinct-definition requirement"
            )
        }

        // Pass 2: constraints and resources describe different concepts
        if (cons.lowercase() == res.lowercase()) {
            return SwarmResult(
                false,
                "Pass 2: constraints and resources are identical — possible intent confusion"
            )
        }

        // Pass 3: acceptance_boundary must differ from objective (not a copy-paste)
        if (ab.lowercase() == obj.lowercase()) {
            return SwarmResult(
                false,
                "Pass 3: acceptance_boundary is identical to objective — boundary must be distinct from the goal"
            )
        }

        return SwarmResult(true, "All 3 swarm passes succeeded")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. SIMULATION VALIDATION
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Tests logical execution feasibility of the intent.
     *
     * Checks:
     *  - All fields are substantive (length ≥ [MIN_FIELD_LENGTH]).
     *  - No field contains a known placeholder token (n/a, tbd, todo, etc.).
     */
    internal fun runSimulationValidation(draft: IntentDraft): SimulationResult {
        val fields = listOf(
            "objective"           to draft.objective,
            "success_criteria"    to draft.successCriteria,
            "constraints"         to draft.constraints,
            "environment"         to draft.environment,
            "resources"           to draft.resources,
            "acceptance_boundary" to draft.acceptanceBoundary
        )

        for ((name, value) in fields) {
            if (value == null || value.length < MIN_FIELD_LENGTH) {
                return SimulationResult(false, "Field '$name' is not substantive")
            }
            if (value.trim().lowercase() in PLACEHOLDER_TOKENS) {
                return SimulationResult(
                    false,
                    "Field '$name' contains placeholder '${value.trim()}' — simulation cannot validate feasibility"
                )
            }
        }

        return SimulationResult(true, "All fields pass simulation feasibility check")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7. PCCV GATE
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds the mandatory PCCV checklist.
     * Intent is certified ONLY if ALL five dimensions pass.
     */
    internal fun buildPccvReport(
        gapReport:   GapReport,
        swarmResult: SwarmResult,
        simResult:   SimulationResult
    ): PccvReport = PccvReport(
        completeness    = gapReport.isComplete,
        consistency     = swarmResult.passed,
        feasibility     = simResult.passed,
        nonAssumption   = true,  // guaranteed by reconstruction engine (no auto-fill)
        reproducibility = gapReport.isComplete && swarmResult.passed && simResult.passed
    )

    // ─────────────────────────────────────────────────────────────────────────
    // EVIDENCE BUILDER
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildEvidenceRefs(draft: IntentDraft): List<EvidenceRef> = buildList {
        draft.objective?.let          { add(EvidenceRef("objective",           "raw_input", it)) }
        draft.successCriteria?.let    { add(EvidenceRef("success_criteria",    "raw_input", it)) }
        draft.constraints?.let        { add(EvidenceRef("constraints",         "raw_input", it)) }
        draft.environment?.let        { add(EvidenceRef("environment",         "raw_input", it)) }
        draft.resources?.let          { add(EvidenceRef("resources",           "raw_input", it)) }
        draft.acceptanceBoundary?.let { add(EvidenceRef("acceptance_boundary", "raw_input", it)) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INTERNAL RESULT TYPES
    // ─────────────────────────────────────────────────────────────────────────

    internal data class SwarmResult(val passed: Boolean, val detail: String)
    internal data class SimulationResult(val passed: Boolean, val detail: String)
}
