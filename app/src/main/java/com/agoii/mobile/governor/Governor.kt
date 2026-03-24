package com.agoii.mobile.governor

import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.core.Replay
import com.agoii.mobile.governance.GovernanceGate

/**
 * Governor — the ONLY progression decision authority in the system.
 *
 * Contract (CR 1.6 — REPLAY-ANCHORED GOVERNOR REALIGNMENT):
 *  - ALL decisions are derived exclusively from [Replay.replayStructuralState].
 *  - ALL event writes go exclusively through [GovernanceGate.appendEvent].
 *  - NO validation logic, NO helper methods, NO adapter usage, NO payload inspection.
 *  - ONE source of truth: Replay. ONE decision engine: Governor. ONE write path: GovernanceGate.
 */
class Governor(
    private val replay: Replay,
    private val gate: GovernanceGate
) {

    /** The only two outcomes Governor may return. */
    enum class GovernorResult { ADVANCED, NO_EVENT }

    /**
     * Evaluates the current structural state and drives the next governance step.
     *
     * Decision table (applied in order; each matching branch appends one event):
     *  1. intent.structurallyComplete == false  → return NO_EVENT
     *  2. contracts.valid == false               → append CONTRACTS_GENERATED
     *  3. contracts.valid && !execution.fullyExecuted → append EXECUTION_STARTED
     *  4. execution.fullyExecuted && !assembly.assemblyValid → append ASSEMBLY_STARTED
     *  5. assembly.assemblyValid                → append ASSEMBLY_COMPLETED
     */
    fun process(projectId: String): GovernorResult {
        val state = replay.replayStructuralState(projectId)

        if (!state.intent.structurallyComplete) return GovernorResult.NO_EVENT

        if (!state.contracts.valid) {
            gate.appendEvent(projectId, EventTypes.CONTRACTS_GENERATED, emptyMap())
        }

        if (state.contracts.valid && !state.execution.fullyExecuted) {
            gate.appendEvent(projectId, EventTypes.EXECUTION_STARTED, emptyMap())
        }

        if (state.execution.fullyExecuted && !state.assembly.assemblyValid) {
            gate.appendEvent(projectId, EventTypes.ASSEMBLY_STARTED, emptyMap())
        }

        if (state.assembly.assemblyValid) {
            gate.appendEvent(projectId, EventTypes.ASSEMBLY_COMPLETED, emptyMap())
        }

        return GovernorResult.ADVANCED
    }
}
