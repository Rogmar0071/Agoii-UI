// CONTRACT: INTENT_MODULE_V1_COMPLETE
// CLASSIFICATION:
//   Class: Structural
//   Reversibility: Forward-only
//   Invariant Surface: Intent → Derivation boundary
//   Execution Scope: Both (SIM + REAL)
//
// PURPOSE:
//   Establish IntentModule as the ONLY authority for transforming raw input
//   into a certified, structured IntentMaster using IRS.
//
//   This module encapsulates the entire IRS lifecycle and exposes a strictly
//   controlled output boundary.
//
// SYSTEM POSITION:
//   Ingress → IntentModule → IntentMaster → CoreBridge → Execution
//
// GOVERNING LAWS:
//   - NO ledger writes
//   - NO execution triggering
//   - NO contract derivation
//   - NO mutation of IRS internals
//   - NO exposure of IRS session/state outside module
//   - IntentMaster MUST remain minimal and deterministic
//
// OUTPUT CONTRACT:
//   IntentResult:
//     - Certified(IntentMaster)
//     - NeedsClarification(gaps)
//     - Rejected(reason, details)
//
// INVARIANT GUARANTEES:
//   - Only Certified intent may pass into execution
//   - All intent is validated through IRS lifecycle
//   - No partial or unverified intent escapes this boundary
//
// FILE LOCATION:
//   app/src/main/java/com/agoii/mobile/intent/IntentModule.kt

package com.agoii.mobile.intent

import com.agoii.mobile.irs.*

// ─────────────────────────────────────────────────────────────────────────────
// IntentMaster — minimal, deterministic artifact produced on certification
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Certified, structured representation of a validated intent.
 *
 * Invariants:
 *  - Only produced by [IntentModule] after a successful IRS lifecycle.
 *  - Contains no IRS session state or internal pipeline artifacts.
 *  - [certifiedAt] is the wall-clock millisecond timestamp of certification.
 *
 * @property intentData   The fully evidence-backed intent fields after IRS enrichment.
 * @property sessionId    Certification session identifier; provides traceability back to the IRS run.
 * @property certifiedAt  Timestamp (epoch ms) at which certification was issued.
 */
data class IntentMaster(
    val intentData:  IntentData,
    val sessionId:   String,
    val certifiedAt: Long
)

// ─────────────────────────────────────────────────────────────────────────────
// IntentResult — controlled output boundary of IntentModule
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Terminal output of [IntentModule.processIntent].
 *
 * Exactly one of the three variants is returned per invocation:
 *  - [Certified]           — intent passed all IRS stages; [IntentMaster] is ready for execution.
 *  - [NeedsClarification]  — one or more mandatory fields lack sufficient evidence.
 *  - [Rejected]            — IRS pipeline rejected the intent at a specific stage.
 *
 * Only [Certified] may flow downstream into CoreBridge / execution.
 */
sealed class IntentResult {

    /** Intent is fully certified and safe to pass to CoreBridge. */
    data class Certified(val master: IntentMaster) : IntentResult()

    /**
     * One or more mandatory fields are missing or insufficiently evidenced.
     *
     * @property gaps Human-readable descriptions of each gap that must be resolved.
     */
    data class NeedsClarification(val gaps: List<String>) : IntentResult()

    /**
     * IRS pipeline rejected the intent.
     *
     * @property reason  Machine-readable rejection code; maps to [FailureType.name].
     * @property details Human-readable explanations of the rejection.
     */
    data class Rejected(val reason: String, val details: List<String> = emptyList()) : IntentResult()
}

// ─────────────────────────────────────────────────────────────────────────────
// IntentModule — IRS-backed intent construction authority
// ─────────────────────────────────────────────────────────────────────────────

class IntentModule(
    private val irsOrchestrator: IrsOrchestrator
) {

    /**
     * Execute full IRS lifecycle and return controlled intent output.
     *
     * This method is PURE:
     *  - No ledger interaction
     *  - No execution triggering
     *  - No external mutation
     *
     * @param sessionId        Unique identifier for this IRS session.
     * @param rawFields        Raw field values keyed by field name.
     * @param evidence         Evidence refs keyed by field name (pre-scouting).
     * @param swarmConfig      Swarm parameters; [SwarmConfig.agentCount] must be ≥ 2.
     * @param availableEvidence Supplementary evidence pool passed to the ScoutOrchestrator.
     * @return [IntentResult] — exactly one of Certified, NeedsClarification, or Rejected.
     */
    fun processIntent(
        sessionId:         String,
        rawFields:         Map<String, String>,
        evidence:          Map<String, List<EvidenceRef>>,
        swarmConfig:       SwarmConfig,
        availableEvidence: Map<String, List<EvidenceRef>> = emptyMap()
    ): IntentResult {
        irsOrchestrator.createSession(
            sessionId         = sessionId,
            rawFields         = rawFields,
            evidence          = evidence,
            swarmConfig       = swarmConfig,
            availableEvidence = availableEvidence
        )

        var stepResult: StepResult
        do {
            stepResult = irsOrchestrator.step(sessionId)
        } while (!stepResult.terminal)

        return when (val outcome = stepResult.orchestratorResult) {
            is OrchestratorResult.Certified -> IntentResult.Certified(
                IntentMaster(
                    intentData  = stepResult.session.intentData,
                    sessionId   = sessionId,
                    certifiedAt = System.currentTimeMillis()
                )
            )
            is OrchestratorResult.NeedsClarification -> IntentResult.NeedsClarification(outcome.gaps)
            is OrchestratorResult.Rejected           -> IntentResult.Rejected(outcome.reason, outcome.details)
            null -> IntentResult.Rejected(
                reason  = FailureType.PCCV_FAIL.name,
                details = listOf("IRS returned terminal step with no result")
            )
        }
    }
}
