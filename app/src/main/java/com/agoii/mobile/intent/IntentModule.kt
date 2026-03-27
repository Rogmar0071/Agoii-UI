package com.agoii.mobile.intent

import com.agoii.mobile.contracts.ContractIntent
import com.agoii.mobile.ingress.ContractStatus
import com.agoii.mobile.ingress.IngressContract
import java.util.UUID

// ─── Module-level constants ───────────────────────────────────────────────────

/** Maximum number of Recovery Contracts (RCF-1) before the session is hard-failed. */
private const val MAX_RECOVERY_ATTEMPTS = 3

/**
 * Number of consecutive interactions targeting the same field, all ending
 * without progress, before stagnation is declared.
 */
private const val STAGNATION_THRESHOLD = 2

/** Ordered list of all mandatory IntentMaster fields. */
private val MANDATORY_FIELDS = listOf("objective", "constraints", "environment", "resources")

// ─── Intent Module ────────────────────────────────────────────────────────────

/**
 * IntentModule — a CLOSED SYSTEM responsible for the full intent lifecycle.
 *
 * Responsibilities (non-negotiable, all internal):
 *  1. Intent construction        — builds IntentMaster from a validated IngressContract.
 *  2. Intent state management    — maintained by [IntentStateManager]; append-only.
 *  3. Intent completeness        — enforced by [IntentCompletionEngine].
 *  4. Interaction orchestration  — driven by [InteractionDriver].
 *  5. Convergence control        — guarded by [ConvergenceController].
 *  6. Report generation          — produced by [IntentReportGenerator] (MQP requirement).
 *  7. Recovery triggering        — detected and issued by [RecoveryTrigger] (RCF-1).
 *  8. RRID anchoring             — generated and propagated by [RridAnchorHandler].
 *
 * Position in system:
 *   RAW INPUT → IngressContract → IntentModule → ContractSystemOrchestrator
 *
 * Non-negotiable rules:
 *  - All 7 internal components are private nested classes; no external substitution.
 *  - [createSession] constructs an IntentMaster from an ACCEPTED IngressContract.
 *  - [step] executes exactly one interaction cycle per call.
 *  - Validated fields are NEVER overwritten (enforced by [IntentStateManager]).
 *  - All outputs carry the session RRID (enforced by [RridAnchorHandler]).
 *  - Recovery is triggered when stagnation is detected (enforced by [RecoveryTrigger]).
 *  - The module is stateless between sessions; all per-session state is encapsulated
 *    within the [IntentStateManager].
 */
class IntentModule {

    private val stateManager          = IntentStateManager()
    private val completionEngine      = IntentCompletionEngine()
    private val interactionDriver     = InteractionDriver()
    private val convergenceController = ConvergenceController()
    private val reportGenerator       = IntentReportGenerator()
    private val recoveryTrigger       = RecoveryTrigger()
    private val rridAnchorHandler     = RridAnchorHandler()

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Create a new Intent session from a validated [IngressContract].
     *
     * Pre-populates mandatory fields from [IngressContract.payload.extractedFields]
     * where available; remaining fields begin empty and are collected via [step].
     *
     * @param ingress  Ingress contract that has passed upstream validation.
     *                 Must carry [ContractStatus.ACCEPTED].
     * @return         Initial [IntentMaster] in [IntentStatus.PENDING] state.
     * @throws IllegalArgumentException if [ingress.status] is not ACCEPTED, or if
     *                                  a session for the same contractId already exists.
     */
    fun createSession(ingress: IngressContract): IntentMaster {
        require(ingress.status == ContractStatus.ACCEPTED) {
            "IngressContract '${ingress.contractId}' must be ACCEPTED before creating an Intent session"
        }
        val rrid = rridAnchorHandler.generate()
        return stateManager.init(ingress, rrid)
    }

    /**
     * Advance the Intent session by exactly one interaction cycle.
     *
     * Execution flow per call:
     *   Guard   — [ConvergenceController] blocks drift and exceeded recovery.
     *   Apply   — [InteractionDriver] applies [response] to the last pending field.
     *   Advance — transition PENDING / RECOVERING → IN_PROGRESS.
     *   Check   — [IntentCompletionEngine] evaluates remaining missing fields.
     *   Report  — [IntentReportGenerator] freezes state when complete.
     *   Recover — [RecoveryTrigger] issues RCF-1 on stagnation.
     *   Prompt  — [InteractionDriver] issues the next prompt and logs it.
     *
     * @param intentId Session identifier returned by [createSession].
     * @param response User-supplied answer to the previous prompt; null on first call.
     * @return [IntentModuleResult] for this cycle.
     * @throws IllegalArgumentException if no session with [intentId] exists.
     */
    fun step(intentId: String, response: String? = null): IntentModuleResult {

        val master = stateManager.get(intentId)
            ?: throw IllegalArgumentException("Intent session '$intentId' not found")

        // ── Terminal guard ────────────────────────────────────────────────────
        when (master.status) {
            IntentStatus.COMPLETE ->
                return IntentModuleResult.Complete(
                    intentMaster   = master,
                    report         = master.report!!,
                    contractIntent = buildContractIntent(master)
                )
            IntentStatus.FAILED ->
                return IntentModuleResult.Failed(
                    master, "Session '${master.intentId}' is in terminal FAILED state"
                )
            else -> { /* continue */ }
        }

        // ── Step 1: Convergence guard ─────────────────────────────────────────
        val guard = convergenceController.guard(master)
        if (!guard.passed) {
            val failed = stateManager.update(intentId, master.copy(status = IntentStatus.FAILED))
            return IntentModuleResult.Failed(failed, guard.reason)
        }

        // ── Step 2: Apply response (when provided) ────────────────────────────
        var current = if (response != null) {
            stateManager.update(intentId, interactionDriver.apply(master, response))
        } else {
            master
        }

        // ── Step 3: Advance lifecycle state ───────────────────────────────────
        if (current.status == IntentStatus.PENDING || current.status == IntentStatus.RECOVERING) {
            current = stateManager.update(intentId, current.copy(status = IntentStatus.IN_PROGRESS))
        }

        // ── Step 4: Completeness check ────────────────────────────────────────
        val completion = completionEngine.evaluate(current)
        if (completion.isComplete) {
            val report   = reportGenerator.generate(current)
            val anchored = rridAnchorHandler.anchor(report, current.rrid)
            val done     = stateManager.update(
                intentId,
                current.copy(
                    status       = IntentStatus.COMPLETE,
                    report       = anchored,
                    completeness = 1.0
                )
            )
            return IntentModuleResult.Complete(done, anchored, buildContractIntent(done))
        }

        // ── Step 5: Stagnation → Recovery ─────────────────────────────────────
        if (recoveryTrigger.shouldRecover(current)) {
            val newAttempts = current.recoveryAttempts + 1
            if (newAttempts > MAX_RECOVERY_ATTEMPTS) {
                val failed = stateManager.update(
                    intentId,
                    current.copy(status = IntentStatus.FAILED, recoveryAttempts = newAttempts)
                )
                return IntentModuleResult.Failed(
                    failed,
                    "Max recovery attempts ($MAX_RECOVERY_ATTEMPTS) exceeded for session '${master.intentId}'"
                )
            }
            val recovering = stateManager.update(
                intentId,
                current.copy(status = IntentStatus.RECOVERING, recoveryAttempts = newAttempts)
            )
            val rcf      = recoveryTrigger.issue(recovering)
            val anchored = rridAnchorHandler.anchorRecovery(rcf, recovering.rrid)
            return IntentModuleResult.Recovering(recovering, anchored)
        }

        // ── Step 6: Issue next interaction prompt ─────────────────────────────
        val nextField = completion.nextMissingField!!
        val prompt    = interactionDriver.prompt(nextField)
        val withLog   = stateManager.update(
            intentId,
            interactionDriver.recordPrompt(current, nextField, prompt)
        )
        return IntentModuleResult.NeedsInteraction(withLog, prompt, nextField)
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private fun buildContractIntent(master: IntentMaster): ContractIntent =
        ContractIntent(
            objective   = master.objective.value,
            constraints = master.constraints.value,
            environment = master.environment.value,
            resources   = master.resources.value
        )

    // ═══════════════════════════════════════════════════════════════════════════
    // ─── 1. Intent State Manager ───────────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Maintains the current [IntentMaster] for every active session.
     *
     * Rules:
     *  - Sessions are created once via [init]; duplicate session IDs are rejected.
     *  - [update] guards against overwriting already-validated fields.
     *  - No session is ever deleted.
     */
    private class IntentStateManager {

        private val sessions = mutableMapOf<String, IntentMaster>()

        /** Create and register a new session. Rejects duplicate session IDs. */
        fun init(ingress: IngressContract, rrid: Rrid): IntentMaster {
            require(!sessions.containsKey(ingress.contractId)) {
                "Intent session '${ingress.contractId}' already exists"
            }
            val fields = ingress.payload.extractedFields
            val master = IntentMaster(
                intentId         = ingress.contractId,
                rrid             = rrid,
                reportReference  = rrid.value,
                status           = IntentStatus.PENDING,
                objective        = fieldFrom(fields, "objective",
                    fallback = ingress.payload.normalizedIntent),
                constraints      = fieldFrom(fields, "constraints"),
                environment      = fieldFrom(fields, "environment"),
                resources        = fieldFrom(fields, "resources"),
                interactionLog   = emptyList(),
                completeness     = computeCompleteness(fields),
                recoveryAttempts = 0,
                report           = null
            )
            sessions[ingress.contractId] = master
            return master
        }

        /** Retrieve the current master for [intentId], or null if not found. */
        fun get(intentId: String): IntentMaster? = sessions[intentId]

        /**
         * Persist [updated] as the new master for [intentId].
         *
         * Completeness validation guard: if an existing field is already validated,
         * the incoming update for that field is silently discarded.
         */
        fun update(intentId: String, updated: IntentMaster): IntentMaster {
            val existing = sessions[intentId]
                ?: error("Intent session '$intentId' not found")
            val safe = updated.copy(
                objective    = if (existing.objective.validated)    existing.objective    else updated.objective,
                constraints  = if (existing.constraints.validated)  existing.constraints  else updated.constraints,
                environment  = if (existing.environment.validated)  existing.environment  else updated.environment,
                resources    = if (existing.resources.validated)    existing.resources    else updated.resources
            )
            sessions[intentId] = safe
            return safe
        }

        // ─── Private helpers ──────────────────────────────────────────────────

        private fun fieldFrom(
            fields:   Map<String, String>,
            name:     String,
            fallback: String = ""
        ): IntentFieldState {
            val value     = fields[name] ?: fallback
            val validated = fields.containsKey(name) && value.isNotBlank()
            return IntentFieldState(value = value, validated = validated)
        }

        private fun computeCompleteness(fields: Map<String, String>): Double {
            val count = MANDATORY_FIELDS.count { name ->
                fields.containsKey(name) && fields[name]!!.isNotBlank()
            }
            return count.toDouble() / MANDATORY_FIELDS.size
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ─── 2. Intent Completion Engine ──────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Evaluates missing mandatory fields and determines the next required field.
     *
     * Rules:
     *  - A field is considered missing when it is not validated OR its value is blank.
     *  - Fields are evaluated in [MANDATORY_FIELDS] order (deterministic).
     *  - [isComplete] is true only when every mandatory field passes both checks.
     */
    private class IntentCompletionEngine {

        data class CompletionResult(
            val isComplete:       Boolean,
            val nextMissingField: String?,
            val missingFields:    List<String>,
            val completeness:     Double
        )

        fun evaluate(master: IntentMaster): CompletionResult {
            val missing = MANDATORY_FIELDS.filter { name ->
                val fs = fieldState(master, name)
                !fs.validated || fs.value.isBlank()
            }
            val completeness = (MANDATORY_FIELDS.size - missing.size).toDouble() / MANDATORY_FIELDS.size
            return CompletionResult(
                isComplete       = missing.isEmpty(),
                nextMissingField = missing.firstOrNull(),
                missingFields    = missing,
                completeness     = completeness
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ─── 3. Interaction Driver ────────────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Issues communication contracts (prompts) and applies user responses back
     * into the IntentMaster.
     *
     * Rules:
     *  - [prompt] is deterministic: same field always yields the same prompt text.
     *  - [apply] writes to the field identified by the last unresolved log record,
     *    falling back to the first still-missing mandatory field.
     *  - A response is validated (validated = true) only when it is non-blank.
     *  - [recordPrompt] adds a new unresolved entry to the interaction log.
     */
    private class InteractionDriver {

        /** Return the standard prompt for [field]. */
        fun prompt(field: String): String = when (field) {
            "objective"   -> "Please provide the primary objective for this intent."
            "constraints" -> "Please specify any constraints that must be respected during execution."
            "environment" -> "Please describe the target environment for execution."
            "resources"   -> "Please list the resources required to fulfill this intent."
            else          -> "Please provide a value for '$field'."
        }

        /**
         * Apply [response] to the field identified by the last unresolved log record.
         * When no unresolved record exists (e.g. after recovery), applies to the first
         * still-missing field and appends a recovery log entry.
         */
        fun apply(master: IntentMaster, response: String): IntentMaster {
            val lastUnresolved = master.interactionLog.lastOrNull { !it.resolved }
            val targetField    = lastUnresolved?.fieldTargeted
                ?: MANDATORY_FIELDS.firstOrNull { name ->
                    val fs = fieldState(master, name)
                    !fs.validated || fs.value.isBlank()
                }
                ?: return master  // all fields already complete; nothing to update

            val validated    = response.isNotBlank()
            val updatedField = IntentFieldState(value = response, validated = validated)

            val updatedLog = if (lastUnresolved != null) {
                master.interactionLog.map { record ->
                    if (record == lastUnresolved)
                        record.copy(response = response, resolved = validated)
                    else
                        record
                }
            } else {
                // Recovery scenario: no pending log entry — append a recovery record.
                master.interactionLog + InteractionRecord(
                    sequence      = master.interactionLog.size + 1,
                    fieldTargeted = targetField,
                    prompt        = "Recovery input for '$targetField'",
                    response      = response,
                    resolved      = validated
                )
            }

            val withField = setField(master, targetField, updatedField).copy(
                interactionLog = updatedLog
            )
            val newCompleteness = MANDATORY_FIELDS.count { name ->
                val fs = fieldState(withField, name)
                fs.validated && fs.value.isNotBlank()
            }.toDouble() / MANDATORY_FIELDS.size

            return withField.copy(completeness = newCompleteness)
        }

        /**
         * Append a new unresolved [InteractionRecord] for the given [field] and
         * return the updated master (log is append-only).
         */
        fun recordPrompt(master: IntentMaster, field: String, promptText: String): IntentMaster {
            val record = InteractionRecord(
                sequence      = master.interactionLog.size + 1,
                fieldTargeted = field,
                prompt        = promptText,
                response      = null,
                resolved      = false
            )
            return master.copy(interactionLog = master.interactionLog + record)
        }

        private fun setField(
            master:    IntentMaster,
            name:      String,
            state:     IntentFieldState
        ): IntentMaster = when (name) {
            "objective"   -> master.copy(objective   = state)
            "constraints" -> master.copy(constraints = state)
            "environment" -> master.copy(environment = state)
            "resources"   -> master.copy(resources   = state)
            else          -> master
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ─── 4. Convergence Controller ────────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Enforces incremental completion and blocks illegal advancement.
     *
     * Drift conditions that result in a failed guard:
     *  - Session is already in [IntentStatus.COMPLETE] (terminal; no re-entry).
     *  - Session is already in [IntentStatus.FAILED] (terminal; no re-entry).
     *  - [recoveryAttempts] exceeds [MAX_RECOVERY_ATTEMPTS] (convergence impossible).
     *
     * Note: validated fields are additionally protected at the [IntentStateManager]
     * layer; the Convergence Controller operates at the session-lifecycle level.
     */
    private class ConvergenceController {

        data class GuardResult(val passed: Boolean, val reason: String)

        fun guard(master: IntentMaster): GuardResult {
            if (master.status == IntentStatus.COMPLETE) {
                return GuardResult(
                    passed = false,
                    reason = "Session '${master.intentId}' is already COMPLETE; no further advancement"
                )
            }
            if (master.status == IntentStatus.FAILED) {
                return GuardResult(
                    passed = false,
                    reason = "Session '${master.intentId}' is in terminal FAILED state"
                )
            }
            if (master.recoveryAttempts > MAX_RECOVERY_ATTEMPTS) {
                return GuardResult(
                    passed = false,
                    reason = "Max recovery attempts ($MAX_RECOVERY_ATTEMPTS) exceeded; convergence blocked"
                )
            }
            return GuardResult(passed = true, reason = "")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ─── 5. Intent Report Generator ───────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Produces the frozen CONTRACT REPORT (MQP requirement) when the IntentMaster
     * reaches [IntentStatus.COMPLETE].
     *
     * Rules:
     *  - The report captures state at the moment of generation; it is immutable.
     *  - [completenessAt] is always 1.0 when this generator is invoked (all fields
     *    validated).
     *  - RRID anchoring is applied by [RridAnchorHandler] after generation.
     */
    private class IntentReportGenerator {

        fun generate(master: IntentMaster): IntentReport = IntentReport(
            intentId         = master.intentId,
            rrid             = master.rrid,
            reportReference  = master.reportReference,
            objective        = master.objective.value,
            constraints      = master.constraints.value,
            environment      = master.environment.value,
            resources        = master.resources.value,
            completenessAt   = master.completeness,
            interactionCount = master.interactionLog.size,
            recoveryAttempts = master.recoveryAttempts
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ─── 6. Recovery Trigger ──────────────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Detects interaction stagnation and issues Recovery Contracts (RCF-1).
     *
     * Stagnation definition:
     *   The last [STAGNATION_THRESHOLD] interaction log entries all target the same
     *   mandatory field AND that field remains incomplete (blank or not validated).
     *
     * When stagnation is detected, [issue] produces an RCF-1 Recovery Contract
     * listing every mandatory field that is still missing.  The RRID is propagated
     * by [RridAnchorHandler] after issuance.
     */
    private class RecoveryTrigger {

        fun shouldRecover(master: IntentMaster): Boolean {
            val log = master.interactionLog
            if (log.size < STAGNATION_THRESHOLD) return false

            val lastN        = log.takeLast(STAGNATION_THRESHOLD)
            val allSameField = lastN.map { it.fieldTargeted }.distinct().size == 1
            if (!allSameField) return false

            val targetField  = lastN.first().fieldTargeted
            val fs           = fieldState(master, targetField)
            return fs.value.isBlank() || !fs.validated
        }

        fun issue(master: IntentMaster): RecoveryContract {
            val missing = MANDATORY_FIELDS.filter { name ->
                val fs = fieldState(master, name)
                fs.value.isBlank() || !fs.validated
            }
            return RecoveryContract(
                intentId      = master.intentId,
                rrid          = master.rrid,
                type          = "RCF-1",
                reason        = "Stagnation detected after ${master.recoveryAttempts} recovery attempt(s) with no progress",
                missingFields = missing
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ─── 7. RRID Anchor Handler ────────────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Generates RRID values and anchors them to all module outputs.
     *
     * Rules:
     *  - [generate] produces a unique RRID per session at creation time.
     *  - [anchor] stamps the RRID onto an [IntentReport], ensuring the
     *    [IntentReport.reportReference] propagates to downstream systems.
     *  - [anchorRecovery] stamps the same session RRID onto a [RecoveryContract].
     */
    private class RridAnchorHandler {

        /** Generate a new unique RRID for a session. */
        fun generate(): Rrid = Rrid(UUID.randomUUID().toString())

        /** Apply [rrid] to [report], propagating [IntentReport.reportReference]. */
        fun anchor(report: IntentReport, rrid: Rrid): IntentReport =
            report.copy(rrid = rrid, reportReference = rrid.value)

        /** Apply [rrid] to [contract], anchoring the Recovery Contract to the session. */
        fun anchorRecovery(contract: RecoveryContract, rrid: Rrid): RecoveryContract =
            contract.copy(rrid = rrid)
    }
}

// ─── Package-level field accessor ────────────────────────────────────────────

/**
 * Return the [IntentFieldState] for [name] from [master].
 *
 * Defined at package level so all seven internal components can use it
 * without duplicating the when-expression.
 */
internal fun fieldState(master: IntentMaster, name: String): IntentFieldState = when (name) {
    "objective"   -> master.objective
    "constraints" -> master.constraints
    "environment" -> master.environment
    "resources"   -> master.resources
    else          -> error("Unknown mandatory field: '$name'")
}
