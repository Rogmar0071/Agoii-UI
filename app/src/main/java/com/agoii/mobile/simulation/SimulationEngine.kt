package com.agoii.mobile.simulation

import com.agoii.mobile.core.ReplayState

/**
 * SimulationEngine — top-level entry point for the Simulation System.
 *
 * Invariants (non-negotiable):
 *  1. READ-ONLY  — does not write to the Event Ledger, does not call
 *                  eventStore.appendEvent, and does not mutate [ReplayState].
 *  2. PURE       — same [ReplayState] + [SimulationContract] always produces
 *                  the same [SimulationResult].
 *  3. NO GOVERNOR BYPASS — cannot trigger execution or advance phases.
 *  4. MODULE ISOLATION   — no dependency on ui, ingress, or interaction packages.
 *
 * Processing pipeline:
 *  1. [SimulationMapper]   derives a read-only [SimulationSnapshot] from [ReplayState].
 *  2. [SimulationAnalyzer] analyses the snapshot according to [SimulationContract.mode].
 *  3. Results are assembled into an immutable [SimulationResult].
 */
class SimulationEngine {

    private val mapper   = SimulationMapper()
    private val analyzer = SimulationAnalyzer()

    /**
     * Simulate possible outcomes for the given [state] and [contract].
     *
     * @param state    Read-only replay state derived from the event ledger.
     * @param contract Describes the kind of simulation to perform.
     * @return         Immutable [SimulationResult] — no side effects produced.
     */
    fun simulate(state: ReplayState, contract: SimulationContract): SimulationResult {
        val snapshot = mapper.map(state)
        val analysis = analyzer.analyze(snapshot, contract)

        return SimulationResult(
            contractId    = contract.contractId,
            mode          = contract.mode,
            feasible      = analysis.feasible,
            confidence    = analysis.confidence,
            findings      = analysis.findings,
            failurePoints = analysis.failurePoints,
            scenarios     = analysis.scenarios
        )
    }
}
