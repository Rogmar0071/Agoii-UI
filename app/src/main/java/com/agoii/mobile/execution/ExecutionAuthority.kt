package com.agoii.mobile.execution

import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventLedger
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.core.LedgerValidationException
import com.agoii.mobile.core.ValidationLayer
import java.util.UUID

// ── ExecutionAuthority ────────────────────────────────────────────────────────

/**
 * Result returned by [ExecutionAuthority.authorize].
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
 * ExecutionAuthority — the ONLY pre-ledger decision layer for [EventTypes.CONTRACTS_GENERATED].
 *
 * Responsibilities:
 *  1. Accept an intentId and a derived contracts payload.
 *  2. Phase 1 — Validation: delegate to [ValidationLayer].
 *  3. Phase 2 — Authorization: enforce business rules (non-empty, sequential positions, total).
 *  4. Phase 3 — Write: append [EventTypes.CONTRACTS_GENERATED] to [EventLedger] only when both
 *     phases pass.
 *
 * Rules:
 *  - Stateless: no mutable fields; every call reads from and writes to [ledger].
 *  - Deterministic: same intentId + same contracts → same outcome.
 *  - MUST NOT call Governor.
 *  - MUST NOT mutate the input payload.
 *  - Returns [AuthorizationResult.blocked] with a human-readable reason on failure.
 *
 * Identity fields added to every [EventTypes.CONTRACTS_GENERATED] payload:
 *  - `intentId`      — forwarded from the caller; ties the event to the originating intent.
 *  - `contractSetId` — freshly generated UUID; uniquely identifies this contract set.
 *
 * Each contract in the `contracts` list MUST carry:
 *  - `contractId` — unique identifier for the contract (mirrors `id` for traceability).
 *  - `position`   — 1-based sequential index within the contract set.
 */
class ExecutionAuthority(private val ledger: EventLedger) {

    private val validationLayer = ValidationLayer()

    /**
     * Authorize and persist a [EventTypes.CONTRACTS_GENERATED] event.
     *
     * @param projectId  The project ledger to write to.
     * @param intentId   Identifier of the originating intent (for traceability).
     * @param contracts  Derived contract descriptors. Each must contain `id`, `name`, `position`.
     * @return [AuthorizationResult] indicating success or the specific block reason.
     */
    fun authorize(
        projectId: String,
        intentId:  String,
        contracts: List<Map<String, Any>>
    ): AuthorizationResult {

        // ── Phase 2 pre-check: catch empty input before building payload ──────────
        if (contracts.isEmpty()) {
            return AuthorizationResult.blocked("Contracts list is empty", "AUTHORIZATION")
        }

        val total = contracts.size

        // Enforce identity fields on each contract and validate fields.
        val allFieldsValid = contracts.all { contract ->
            !contract["id"]?.toString().isNullOrBlank() &&
            !contract["name"]?.toString().isNullOrBlank() &&
            contract["position"] != null
        }
        if (!allFieldsValid) {
            return AuthorizationResult.blocked(
                "One or more contracts have a blank 'id', blank 'name', or null 'position'",
                "AUTHORIZATION"
            )
        }

        val positions = contracts.mapNotNull { resolveInt(it["position"]) }.sorted()
        if (positions != (1..total).toList()) {
            return AuthorizationResult.blocked(
                "contract positions do not form exact sequence 1..$total; got $positions",
                "AUTHORIZATION"
            )
        }

        // Build the enriched payload (adds intentId + contractSetId for forward traceability).
        val contractSetId = UUID.randomUUID().toString()
        val enrichedContracts: List<Map<String, Any>> = contracts.map { c ->
            val base = c.toMutableMap()
            // Expose contractId as an explicit field alongside `id` for traceability.
            base["contractId"] = c["id"] ?: ""
            base
        }
        val payload: Map<String, Any> = mapOf(
            "intentId"      to intentId,
            "contractSetId" to contractSetId,
            "contracts"     to enrichedContracts,
            "total"         to total
        )

        // ── Phase 1: Validation ──────────────────────────────────────────────────
        val currentEvents = ledger.loadEvents(projectId)
        try {
            validationLayer.validate(
                projectId     = projectId,
                type          = EventTypes.CONTRACTS_GENERATED,
                payload       = payload,
                currentEvents = currentEvents
            )
        } catch (e: LedgerValidationException) {
            return AuthorizationResult.blocked("validation failed: ${e.message}", "VALIDATION")
        }

        // ── Phase 3: Write ───────────────────────────────────────────────────────
        ledger.appendEvent(projectId, EventTypes.CONTRACTS_GENERATED, payload)
        return AuthorizationResult.authorized(
            Event(type = EventTypes.CONTRACTS_GENERATED, payload = payload)
        )
    }

    /** Normalises a numeric payload value to [Int]. Accepts [Int], [Long], and [Double]. */
    private fun resolveInt(value: Any?): Int? = when (value) {
        is Int    -> value
        is Long   -> value.toInt()
        is Double -> value.toInt()
        is String -> value.toIntOrNull()
        else      -> null
    }
}
