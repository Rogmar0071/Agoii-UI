package com.agoii.mobile.execution

import android.util.Log
import com.agoii.mobile.contracts.AgentProfile
import com.agoii.mobile.contracts.ContractIntent
import com.agoii.mobile.contracts.ContractSystemOrchestrator
import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventLedger
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.core.LedgerValidationException
import com.agoii.mobile.core.ValidationLayer
import com.agoii.mobile.ics.ICSContract
import com.agoii.mobile.ics.IcsModule
import com.agoii.mobile.ics.IntentConstructionResult
import java.util.UUID

/**
 * Result returned by [ExecutionEntryPoint.executeIntent].
 *
 * @property event   The [Event] that was persisted, or null when blocked.
 * @property status  "AUTHORIZED" on success; "BLOCKED" on failure.
 * @property reason  Human-readable description of the block reason, or null on success.
 * @property stage   The pipeline stage at which blocking occurred:
 *                   "VALIDATION" (Phase 1) or "AUTHORIZATION" (Phase 2), or null on success.
 */
data class AuthorizationResult(
    val event:  Event?,
    val status: String,
    val reason: String?,
    val stage:  String?
) {
    val authorized: Boolean get() = status == "AUTHORIZED"

    companion object {
        fun blocked(reason: String, stage: String) =
            AuthorizationResult(event = null, status = "BLOCKED", reason = reason, stage = stage)
        fun authorized(event: Event) =
            AuthorizationResult(event = event, status = "AUTHORIZED", reason = null, stage = null)
    }
}

/**
 * ExecutionEntryPoint — the ONLY component allowed to trigger contract derivation
 * and invoke [ExecutionAuthority].
 *
 * Responsibilities:
 *  1. Receive an intent payload from the orchestration layer.
 *  2. Derive contracts via [ContractSystemOrchestrator].
 *  3. Delegate pure validation and authorization to [ExecutionAuthority].
 *  4. Write [EventTypes.CONTRACTS_GENERATED] to the ledger when authorized.
 *
 * Rules:
 *  - Contains ZERO progression logic — Governor is invoked exclusively by CoreBridge.
 *  - Only orchestration — no decisions.
 *  - Returns a structured [AuthorizationResult] on every path; no silent nulls.
 *  - [ExecutionAuthority] is private and not reachable from any other class.
 */
class ExecutionEntryPoint(
    private val ledger:              EventLedger,
    private val executionAuthority:  ExecutionAuthority
) {

    private val contractSystemOrchestrator = ContractSystemOrchestrator()
    private val validationLayer            = ValidationLayer()
    private val icsModule                  = IcsModule()

    companion object {
        private const val DEFAULT_CONSTRAINTS = "standard"
        private const val DEFAULT_ENVIRONMENT = "mobile"
        private const val DEFAULT_RESOURCES   = "available"

        /**
         * Default agent profile used when evaluating [ContractSystemOrchestrator].
         * Represents a maximally capable agent for deterministic contract derivation.
         *
         * All capability dimensions use a 0–3 scale (higher = more capable).
         * driftTendency uses an inverted scale (0 = no drift, 3 = high drift).
         */
        private val DEFAULT_AGENT_PROFILE = AgentProfile(
            agentId             = "default-agent",
            constraintObedience = 3,
            structuralAccuracy  = 3,
            driftTendency       = 0,
            complexityHandling  = 3,
            outputReliability   = 3
        )
    }

    /**
     * Execute the full intent-to-contracts pipeline for [projectId].
     *
     * Flow (locked):
     *  1. Extract objective from [intentPayload].
     *  2. Call [ContractSystemOrchestrator.evaluate] to derive an [ExecutionPlan].
     *  3. Map [ExecutionStep] list → [ExecutionContract] descriptors.
     *  4. Call [ExecutionAuthority.evaluate] (pure pre-ledger gate).
     *  5. On approval, persist [EventTypes.CONTRACTS_GENERATED] to the ledger.
     *  6. Return [AuthorizationResult] — AUTHORIZED or BLOCKED with reason + stage.
     *     Governor progression is the exclusive responsibility of the caller (CoreBridge).
     *
     * @param projectId     The project ledger to write to.
     * @param intentPayload The payload of the INTENT_SUBMITTED event.
     * @return [AuthorizationResult] — never null; always carries status + reason + stage.
     */
    fun executeIntent(
        projectId:     String,
        intentPayload: Map<String, Any>
    ): AuthorizationResult {
        // ── Phase 0: Extract intent identity ─────────────────────────────────
        // Moved before the construction loop so intentId/objective can be passed
        // to IcsModule.constructIntent without a second payload scan.
        val objective = intentPayload["objective"] as? String
            ?: return AuthorizationResult.blocked(
                "Intent payload missing 'objective'",
                "AUTHORIZATION"
            )

        val intentId = intentPayload["intentId"] as? String
            ?: UUID.nameUUIDFromBytes(objective.toByteArray()).toString()

        // ── Phase 1: Intent construction step (MQP-INTENT-STEP-EXECUTION-v1) ──
        // Emit exactly one INTENT_* event per execution cycle. Contract derivation
        // remains blocked until a later cycle observes INTENT_APPROVED.
        val icsContract = ICSContract(
            contractId      = "ic_$intentId",
            intentId        = intentId,
            userInput       = objective,
            contextSnapshot = intentPayload
        )
        val constructionResult = icsModule.constructIntentStep(projectId, icsContract, ledger)
        if (constructionResult is IntentConstructionResult.Blocked) {
            return AuthorizationResult.blocked(constructionResult.reason, "INTENT_CONSTRUCTION")
        }

        // ── Phase 2: Intent Authority gate (MQP-POST-INTENT-AUTHORITY-GATE-v1) ─
        // Reload events after the single-step write so the gate can stop execution
        // until INTENT_APPROVED exists. CONTRACTS_GENERATED remains downstream of
        // that approval boundary.
        val projectEvents = ledger.loadEvents(projectId)
        val intentAuthorityEventsPresent = projectEvents.any { it.type in setOf(
            EventTypes.INTENT_PARTIAL_CREATED,
            EventTypes.INTENT_UPDATED,
            EventTypes.INTENT_IN_PROGRESS,
            EventTypes.INTENT_COMPLETED,
            EventTypes.INTENT_APPROVAL_REQUESTED,
            EventTypes.INTENT_APPROVED,
            EventTypes.INTENT_REJECTED
        )}
        if (intentAuthorityEventsPresent) {
            val intentApproved = projectEvents.any { it.type == EventTypes.INTENT_APPROVED }
            if (!intentApproved) {
                return AuthorizationResult.blocked("INTENT_NOT_APPROVED", "INTENT_AUTHORITY")
            }
        }

        // ── Phase 3: Contract derivation ──────────────────────────────────────
        val intent = ContractIntent(
            objective   = objective,
            constraints = DEFAULT_CONSTRAINTS,
            environment = DEFAULT_ENVIRONMENT,
            resources   = DEFAULT_RESOURCES
        )

        val csoResult = contractSystemOrchestrator.evaluate(intent, DEFAULT_AGENT_PROFILE)

        val steps = csoResult.adaptedContract?.adaptedPlan?.steps
            ?: csoResult.scoredContract?.derivation?.executionPlan?.steps
            ?: return AuthorizationResult.blocked(
                "ContractSystemOrchestrator produced no execution plan",
                "AUTHORIZATION"
            )

        if (steps.isEmpty()) {
            return AuthorizationResult.blocked(
                "ContractSystemOrchestrator produced an empty execution plan",
                "AUTHORIZATION"
            )
        }

        val reportId = UUID.nameUUIDFromBytes("rrid:$intentId".toByteArray(Charsets.UTF_8)).toString()

        val executionContracts = steps.map { step ->
            ExecutionContract(
                contractId      = "contract_${step.position}",
                name            = step.description,
                position        = step.position,
                reportReference = reportId
            )
        }

        Log.e("AGOII_TRACE", "[EXECUTION_EVALUATE_START] projectId=$projectId")
        val authorityResult = executionAuthority.evaluate(ExecutionContractInput(executionContracts, reportId))

        if (authorityResult is ExecutionAuthorityResult.Blocked) {
            return AuthorizationResult.blocked(authorityResult.reason, "AUTHORIZATION")
        }

        val approved      = authorityResult as ExecutionAuthorityResult.Approved
        val ordered       = approved.orderedContracts
        val capabilitySet = approved.capabilitySet
        val total = ordered.size

        val contractSetId = UUID.randomUUID().toString()
        val enrichedContracts: List<Map<String, Any>> = ordered.map { c ->
            mapOf(
                "contractId"       to c.contractId,
                "name"             to c.name,
                "position"         to c.position,
                "report_reference" to reportId
            )
        }
        val payload: Map<String, Any> = mapOf(
            "intentId"        to intentId,
            "contractSetId"   to contractSetId,
            "report_reference" to reportId,
            "contracts"       to enrichedContracts,
            "total"           to total
        )

        // projectEvents is the reloaded list (post-construction loop).
        // The last event is INTENT_APPROVED, making the transition
        // INTENT_APPROVED → CONTRACTS_GENERATED legal per LedgerAudit.
        try {
            validationLayer.validate(
                projectId     = projectId,
                type          = EventTypes.CONTRACTS_GENERATED,
                payload       = payload,
                currentEvents = projectEvents
            )
        } catch (e: LedgerValidationException) {
            return AuthorizationResult.blocked("validation failed: ${e.message}", "VALIDATION")
        }

        Log.e("AGOII_TRACE", "[CONTRACT_GENERATED] projectId=$projectId")
        ledger.appendEvent(projectId, EventTypes.CONTRACTS_GENERATED, payload)

        val capabilityPayload: Map<String, Any> = mapOf(
            "intentId"        to intentId,
            "reportReference" to reportId,
            "capabilities"    to capabilitySet.capabilities.map { cap ->
                mapOf(
                    "id"              to cap.id,
                    "objectiveLink"   to cap.objectiveLink,
                    "requiredOutcome" to cap.requiredOutcome,
                    "constraints"     to cap.constraints
                )
            },
            "total"           to capabilitySet.capabilities.size
        )
        Log.e("AGOII_TRACE", "[CAPABILITY_DERIVED] projectId=$projectId total=${capabilitySet.capabilities.size}")
        ledger.appendEvent(projectId, EventTypes.CAPABILITY_DERIVED, capabilityPayload)

        return AuthorizationResult.authorized(
            Event(type = EventTypes.CONTRACTS_GENERATED, payload = payload)
        )
    }
}
