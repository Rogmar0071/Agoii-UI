package com.agoii.mobile.intent

import com.agoii.mobile.contracts.ContractIntent
import com.agoii.mobile.ingress.ContractStatus
import com.agoii.mobile.ingress.IngressContract

// ─── Module-level constants ───────────────────────────────────────────────────

/** Ordered list of all mandatory IntentMaster fields. */
private val MANDATORY_FIELDS = listOf("objective", "constraints", "environment", "resources")

// ─── Intent Module ────────────────────────────────────────────────────────────

/**
 * IntentModule — a CLOSED SYSTEM responsible for the intent lifecycle.
 *
 * Owned responsibilities (all internal, non-negotiable):
 *  1. Intent construction    — builds IntentMaster from a validated IngressContract
 *                              and an externally supplied report reference (RRID).
 *  2. Intent state management — maintained by [IntentStateManager]; validated fields
 *                              are never overwritten.
 *  3. Intent completeness    — evaluated by [IntentCompletionEngine] (deterministic,
 *                              field-ordered).
 *  4. Interaction contracts  — issued by [InteractionDriver] as typed
 *                              [CommunicationContract] artifacts; no free-form text.
 *  5. Report generation      — produced by [IntentReportGenerator] (MQP requirement).
 *
 * NOT owned by this module (handled externally):
 *  - Recovery detection and RCF-1 issuance
 *  - Convergence enforcement
 *  - RRID generation (accepted as input; never generated here)
 *  - Validation beyond field completeness
 *
 * Position in system:
 *   RAW INPUT → IngressContract → IntentModule → ContractSystemOrchestrator
 *
 * Non-negotiable rules:
 *  - All internal components are private nested classes; no external substitution.
 *  - [createSession] requires an externally supplied [reportReference]; the module
 *    stores and propagates it but NEVER generates it.
 *  - [step] executes exactly one interaction cycle per call.
 *  - All outputs carry the session [reportReference].
 *  - No direct text interaction — every request is a [CommunicationContract].
 */
class IntentModule {

    private val stateManager     = IntentStateManager()
    private val completionEngine = IntentCompletionEngine()
    private val interactionDriver = InteractionDriver()
    private val reportGenerator  = IntentReportGenerator()

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Create a new Intent session from a validated [IngressContract] and an
     * externally supplied report reference.
     *
     * Pre-populates mandatory fields from [IngressContract.payload.extractedFields]
     * where available; remaining fields are collected incrementally via [step].
     *
     * @param ingress         Ingress contract that has passed upstream validation.
     *                        Must carry [ContractStatus.ACCEPTED].
     * @param reportReference Externally supplied RRID value. Stored verbatim and
     *                        propagated to all module outputs. MUST NOT be blank.
     * @return                Initial [IntentMaster] in [IntentStatus.PENDING] state.
     * @throws IllegalArgumentException if [ingress.status] is not ACCEPTED, if
     *                                  [reportReference] is blank, or if a session
     *                                  for the same contractId already exists.
     */
    fun createSession(ingress: IngressContract, reportReference: String): IntentMaster {
        require(ingress.status == ContractStatus.ACCEPTED) {
            "IngressContract '${ingress.contractId}' must be ACCEPTED before creating an Intent session"
        }
        require(reportReference.isNotBlank()) {
            "reportReference must not be blank"
        }
        return stateManager.init(ingress, reportReference)
    }

    /**
     * Advance the Intent session by exactly one interaction cycle.
     *
     * Execution flow per call:
     *   Apply   — [InteractionDriver] applies [response] to the last pending field.
     *   Advance — transition PENDING / RECOVERING → IN_PROGRESS.
     *   Check   — [IntentCompletionEngine] evaluates remaining missing fields.
     *   Report  — [IntentReportGenerator] freezes the MQP report when complete.
     *   Contract — [InteractionDriver] issues a [CommunicationContract] for the
     *              next missing field.
     *
     * @param intentId Session identifier returned by [createSession].
     * @param response External actor's answer to the previous [CommunicationContract];
     *                 null on the first call.
     * @return [IntentModuleResult] for this cycle.
     * @throws IllegalArgumentException if no session with [intentId] exists.
     */
    fun step(intentId: String, response: String? = null): IntentModuleResult {

        val master = stateManager.get(intentId)
            ?: throw IllegalArgumentException("Intent session '$intentId' not found")

        // ── Terminal guard ─────────────────────────────────────────────────────
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

        // ── Step 1: Apply response (when provided) ─────────────────────────────
        var current = if (response != null) {
            stateManager.update(intentId, interactionDriver.apply(master, response))
        } else {
            master
        }

        // ── Step 2: Advance lifecycle state ────────────────────────────────────
        if (current.status == IntentStatus.PENDING || current.status == IntentStatus.RECOVERING) {
            current = stateManager.update(intentId, current.copy(status = IntentStatus.IN_PROGRESS))
        }

        // ── Step 3: Completeness check ─────────────────────────────────────────
        val completion = completionEngine.evaluate(current)
        if (completion.isComplete) {
            val report = reportGenerator.generate(current)
            val done   = stateManager.update(
                intentId,
                current.copy(
                    status       = IntentStatus.COMPLETE,
                    report       = report,
                    completeness = 1.0
                )
            )
            return IntentModuleResult.Complete(done, report, buildContractIntent(done))
        }

        // ── Step 4: Issue Communication Contract for next missing field ─────────
        val nextField = completion.nextMissingField!!
        val contract  = interactionDriver.contract(current, nextField)
        val withLog   = stateManager.update(
            intentId,
            interactionDriver.recordContract(current, contract)
        )
        return IntentModuleResult.NeedsInteraction(withLog, contract, nextField)
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

        /** Create and register a new session from [ingress] and [reportReference]. */
        fun init(ingress: IngressContract, reportReference: String): IntentMaster {
            require(!sessions.containsKey(ingress.contractId)) {
                "Intent session '${ingress.contractId}' already exists"
            }
            val rrid   = Rrid(reportReference)
            val fields = ingress.payload.extractedFields
            val master = IntentMaster(
                intentId         = ingress.contractId,
                rrid             = rrid,
                reportReference  = reportReference,
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
         * Validated-field guard: if an existing field is already validated, the
         * incoming update for that field is silently discarded.
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
     * Issues typed [CommunicationContract] artifacts and applies external responses
     * back into the IntentMaster.
     *
     * Rules:
     *  - [contract] is deterministic: same session + field always yields the same
     *    contract structure (prompt text, type, field name).
     *  - NO free-form text prompts are used; all interaction is contract-based.
     *  - [apply] writes to the field identified by the last unresolved log record,
     *    falling back to the first still-missing mandatory field.
     *  - A response is validated (validated = true) only when it is non-blank.
     *  - [recordContract] adds a new unresolved entry to the interaction log.
     */
    private class InteractionDriver {

        /**
         * Produce a [CommunicationContract] targeting [field] for the given session.
         * The contract carries the session's [reportReference] so every interaction
         * is anchored to the RRID.
         */
        fun contract(master: IntentMaster, field: String): CommunicationContract =
            CommunicationContract(
                intentId        = master.intentId,
                reportReference = master.reportReference,
                type            = "FIELD_REQUEST",
                fieldRequired   = field,
                sequence        = master.interactionLog.size + 1,
                prompt          = promptFor(field)
            )

        /**
         * Apply [response] to the field identified by the last unresolved log record.
         * When no unresolved record exists, applies to the first still-missing
         * mandatory field and appends a corrective log entry.
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
                master.interactionLog + InteractionRecord(
                    sequence      = master.interactionLog.size + 1,
                    fieldTargeted = targetField,
                    prompt        = promptFor(targetField),
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
         * Append a new unresolved [InteractionRecord] derived from [comm] and return
         * the updated master (log is append-only).
         */
        fun recordContract(master: IntentMaster, comm: CommunicationContract): IntentMaster {
            val record = InteractionRecord(
                sequence      = comm.sequence,
                fieldTargeted = comm.fieldRequired,
                prompt        = comm.prompt,
                response      = null,
                resolved      = false
            )
            return master.copy(interactionLog = master.interactionLog + record)
        }

        // ─── Private helpers ──────────────────────────────────────────────────

        private fun promptFor(field: String): String = when (field) {
            "objective"   -> "Please provide the primary objective for this intent."
            "constraints" -> "Please specify any constraints that must be respected during execution."
            "environment" -> "Please describe the target environment for execution."
            "resources"   -> "Please list the resources required to fulfill this intent."
            else          -> "Please provide a value for '$field'."
        }

        private fun setField(
            master: IntentMaster,
            name:   String,
            state:  IntentFieldState
        ): IntentMaster = when (name) {
            "objective"   -> master.copy(objective   = state)
            "constraints" -> master.copy(constraints = state)
            "environment" -> master.copy(environment = state)
            "resources"   -> master.copy(resources   = state)
            else          -> master
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ─── 4. Intent Report Generator (MQP) ─────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Produces the frozen MQP-compliant CONTRACT REPORT when the IntentMaster
     * reaches [IntentStatus.COMPLETE].
     *
     * Report content (MQP requirement — descriptive ONLY; no fixes, no interpretation):
     *  - Full type inventory: all types used within the session.
     *  - State structure: resolved field values.
     *  - Field completeness status: per-field validated flag.
     *  - Interaction history: full ordered log at freeze time.
     *  - Error conditions: any incomplete or invalid fields at freeze time.
     *
     * Rules:
     *  - The report captures state at the moment of generation; it is immutable.
     *  - The RRID and reportReference are copied verbatim from the master; no new
     *    values are generated.
     */
    private class IntentReportGenerator {

        fun generate(master: IntentMaster): IntentReport {
            val errorConditions = MANDATORY_FIELDS.mapNotNull { name ->
                val fs = fieldState(master, name)
                when {
                    fs.value.isBlank() -> "Field '$name': value is blank"
                    !fs.validated      -> "Field '$name': value present but not validated"
                    else               -> null
                }
            }
            return IntentReport(
                intentId                = master.intentId,
                rrid                    = master.rrid,
                reportReference         = master.reportReference,
                objective               = master.objective.value,
                constraints             = master.constraints.value,
                environment             = master.environment.value,
                resources               = master.resources.value,
                completenessAt          = master.completeness,
                interactionCount        = master.interactionLog.size,
                recoveryAttempts        = master.recoveryAttempts,
                typeInventory           = listOf(
                    "IntentMaster",
                    "IntentFieldState",
                    "IntentStatus",
                    "InteractionRecord",
                    "CommunicationContract",
                    "IntentReport",
                    "Rrid"
                ),
                fieldCompletenessStatus = MANDATORY_FIELDS.associateWith { name ->
                    fieldState(master, name).validated
                },
                errorConditions         = errorConditions,
                interactionHistory      = master.interactionLog.toList()
            )
        }
    }
}

// ─── Package-level field accessor ────────────────────────────────────────────

/**
 * Return the [IntentFieldState] for [name] from [master].
 *
 * Defined at package level so all internal components can use it
 * without duplicating the when-expression.
 */
internal fun fieldState(master: IntentMaster, name: String): IntentFieldState = when (name) {
    "objective"   -> master.objective
    "constraints" -> master.constraints
    "environment" -> master.environment
    "resources"   -> master.resources
    else          -> error("Unknown mandatory field: '$name'")
}
