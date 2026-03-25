package com.agoii.mobile.simulation

import com.agoii.mobile.core.ReplayStructuralState

/**
 * SimulationEngine — top-level entry point for the Simulation System.
 *
 * Invariants (non-negotiable):
 *  1. READ-ONLY  — does not write to the Event Ledger, does not call
 *                  eventStore.appendEvent, and does not mutate [ReplayStructuralState].
 *  2. PURE       — same [ReplayStructuralState] + [SimulationContract] always produces
 *                  the same [SimulationResult].
 *  3. NO GOVERNOR BYPASS — cannot trigger execution or advance phases.
 *  4. MODULE ISOLATION   — no dependency on ui, ingress, or interaction packages.
 *
 * Processing pipeline:
 *  1. [SimulationMapper]   derives a read-only [SimulationSnapshot] from [ReplayStructuralState].
 *  2. [SimulationAnalyzer] analyses the snapshot according to [SimulationContract.mode].
 *  3. Results are assembled into an immutable [SimulationResult].
 */
class SimulationEngine {

    private val mapper   = SimulationMapper()
    private val analyzer = SimulationAnalyzer()

    /**
     * Simulate possible outcomes for the given [state] and [contract].
     *
     * @param state    Read-only structural state derived from the event ledger.
     * @param contract Describes the kind of simulation to perform.
     * @return         Immutable [SimulationResult] — no side effects produced.
     */
    fun simulate(state: ReplayStructuralState, contract: SimulationContract): SimulationResult {
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

    /**
     * Pure transformation of a [SimulationResult] into a [SimulationView].
     *
     * Rules:
     *  - No formatting, no external dependencies.
     *  - [SimulationView] is the only object allowed to leave the Simulation layer
     *    carrying derived meaning.
     *
     * @param result Immutable output from [simulate].
     * @return       [SimulationView] ready for cross-layer consumption.
     */
    fun toView(result: SimulationResult): SimulationView {
        return SimulationView(
            summary    = result.findings.first(),
            details    = result.findings,
            confidence = result.confidence,
            mode       = result.mode.name
        )
    }
}
