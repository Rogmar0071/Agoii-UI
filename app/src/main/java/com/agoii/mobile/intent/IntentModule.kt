package com.agoii.mobile.intent

import com.agoii.mobile.irs.EvidenceRef
import com.agoii.mobile.irs.IrsOrchestrator
import com.agoii.mobile.irs.OrchestratorResult
import com.agoii.mobile.irs.StepResult
import com.agoii.mobile.irs.SwarmConfig

/**
 * IntentModule — deterministic Intent Engine (IRS-backed).
 *
 * Responsibilities:
 *  - Execute full IRS lifecycle
 *  - Validate and complete intent structure
 *  - Produce certified IntentMaster
 *
 * Authority:
 *  - NO ledger access
 *  - NO execution authority
 *  - NO governor interaction
 */
class IntentModule(
    private val irsOrchestrator: IrsOrchestrator = IrsOrchestrator()
) {

    /**
     * Process a submitted intent into a certified IntentMaster.
     *
     * This method executes the FULL IRS lifecycle internally.
     *
     * @param sessionId         Unique session identifier for this intent run.
     * @param rawFields         Raw field values keyed by field name.
     * @param evidence          Evidence refs keyed by field name (pre-scouting).
     * @param swarmConfig       Swarm parameters; [SwarmConfig.agentCount] must be ≥ 2.
     * @param availableEvidence Supplementary evidence pool passed to the ScoutOrchestrator.
     * @return [IntentResult.Accepted] with a certified [IntentMaster],
     *         [IntentResult.NeedsClarification] with missing field names, or
     *         [IntentResult.Rejected] with a structured failure reason.
     */
    fun processIntent(
        sessionId:         String,
        rawFields:         Map<String, String>,
        evidence:          Map<String, List<EvidenceRef>>,
        swarmConfig:       SwarmConfig,
        availableEvidence: Map<String, List<EvidenceRef>> = emptyMap()
    ): IntentResult {
        irsOrchestrator.createSession(
            sessionId,
            rawFields,
            evidence,
            swarmConfig,
            availableEvidence
        )

        var stepResult: StepResult
        do {
            stepResult = irsOrchestrator.step(sessionId)
        } while (!stepResult.terminal)

        return when (val result = stepResult.orchestratorResult) {
            is OrchestratorResult.Certified -> {
                val session = stepResult.session
                IntentResult.Accepted(
                    IntentMaster(
                        sessionId  = session.sessionId,
                        intentData = session.intentData,
                        irsSession = session
                    )
                )
            }
            is OrchestratorResult.NeedsClarification ->
                IntentResult.NeedsClarification(result.gaps)
            is OrchestratorResult.Rejected ->
                IntentResult.Rejected(result.reason, result.details)
            null ->
                IntentResult.Rejected("INTERNAL_ERROR", listOf("IRS returned no result after terminal step for session: $sessionId"))
        }
    }
}
