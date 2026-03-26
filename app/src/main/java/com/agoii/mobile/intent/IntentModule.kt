package com.agoii.mobile.intent

import com.agoii.mobile.irs.*

/**
 * IntentModule — the ONLY entry point for raw input → certified intent.
 *
 * CONTRACT: INTENT_MODULE_V1_TIGHT
 *   Class:            Structural
 *   Reversibility:    Forward-only
 *   Invariant Surface: Intent → Derivation boundary
 *   Execution Scope:  Both (SIM + REAL)
 *
 * GOVERNING RULES:
 *   - NO ledger writes
 *   - NO execution triggering
 *   - NO contract derivation
 *   - NO IRS internal leakage (no session, no timestamps, no internal state exposure)
 *   - OUTPUT ONLY: structured [IntentMaster] (via [IntentResult.Accepted]) OR rejection
 *
 * AUTHORITY:
 *   IntentModule sits BETWEEN Ingress and CoreBridge.
 *   CoreBridge may use the [IntentResult.Accepted.master] to write INTENT_SUBMITTED,
 *   but that decision belongs entirely to CoreBridge — not here.
 *
 * @param irsOrchestrator The IRS orchestrator that owns the session lifecycle.
 */
class IntentModule(
    private val irsOrchestrator: IrsOrchestrator
) {

    /**
     * Process raw input through the full IRS pipeline and return a certified intent
     * or a structured rejection.
     *
     * The method drives the IRS do-while loop internally; callers receive a single,
     * terminal [IntentResult] with no knowledge of IRS stage internals.
     *
     * @param sessionId         Unique session identifier (supplied by caller).
     * @param rawFields         Raw intent field values keyed by field name.
     * @param evidence          Evidence refs keyed by field name (pre-scouting).
     * @param swarmConfig       Swarm parameters; [SwarmConfig.agentCount] must be ≥ 2 (enforced by [IrsOrchestrator]).
     * @param availableEvidence Supplementary evidence pool forwarded to the ScoutOrchestrator.
     * @return [IntentResult.Accepted] when IRS certifies, or a rejection subtype otherwise.
     */
    fun processIntent(
        sessionId:         String,
        rawFields:         Map<String, String>,
        evidence:          Map<String, List<EvidenceRef>>,
        swarmConfig:       SwarmConfig,
        availableEvidence: Map<String, List<EvidenceRef>> = emptyMap()
    ): IntentResult {

        irsOrchestrator.createSession(sessionId, rawFields, evidence, swarmConfig, availableEvidence)

        var stepResult: StepResult
        do {
            stepResult = irsOrchestrator.step(sessionId)
        } while (!stepResult.terminal)

        return when (val result = stepResult.orchestratorResult) {
            is OrchestratorResult.Certified -> IntentResult.Accepted(
                IntentMaster(
                    sessionId  = sessionId,
                    intentData = stepResult.session.intentData
                )
            )
            is OrchestratorResult.NeedsClarification -> IntentResult.NeedsClarification(result.gaps)
            is OrchestratorResult.Rejected           -> IntentResult.Rejected(result.reason, result.details)
            null                                     -> IntentResult.Rejected("IRS_RESULT_NULL", emptyList())
        }
    }
}
