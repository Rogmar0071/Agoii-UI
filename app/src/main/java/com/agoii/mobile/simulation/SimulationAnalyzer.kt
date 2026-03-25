package com.agoii.mobile.simulation

/**
 * Analyses a [SimulationSnapshot] according to the [SimulationContract.mode] and
 * produces a [SimulationAnalysisResult] for the [SimulationEngine].
 *
 * Rules:
 *  - Pure function: no state, no I/O, no side effects.
 *  - Deterministic: same inputs always yield the same output.
 *  - Does not reference the Event Ledger or any event emitter.
 *  - Internal to the simulation package.
 */
internal class SimulationAnalyzer {

    fun analyze(
        snapshot: SimulationSnapshot,
        contract: SimulationContract
    ): SimulationAnalysisResult = when (contract.mode) {
        SimulationMode.UNDERSTAND  -> analyzeUnderstand(snapshot)
        SimulationMode.FEASIBILITY -> analyzeFeasibility(snapshot)
        SimulationMode.SCENARIO    -> analyzeScenario(snapshot)
    }

    // ── UNDERSTAND ────────────────────────────────────────────────────────────

    /**
     * Interprets the current state structure and produces human-readable findings.
     * Always feasible — UNDERSTAND is a read-only interpretation pass.
     */
    private fun analyzeUnderstand(snapshot: SimulationSnapshot): SimulationAnalysisResult {
        val findings = mutableListOf<String>()

        if (!snapshot.executionStarted && !snapshot.assemblyCompleted) {
            findings += "system is awaiting execution"
        }

        if (snapshot.executionStarted)   findings += "execution has been initiated"
        if (snapshot.executionCompleted) findings += "execution phase is complete"
        if (snapshot.assemblyStarted)    findings += "assembly phase has begun"
        if (snapshot.assemblyValidated)  findings += "assembly has been validated"
        if (snapshot.assemblyCompleted)  findings += "assembly is complete"

        val confidence = when {
            snapshot.assemblyCompleted  -> 0.95
            snapshot.executionCompleted -> 0.85
            snapshot.executionStarted   -> 0.7
            else                        -> 0.5
        }

        return SimulationAnalysisResult(
            findings      = findings,
            failurePoints = emptyList(),
            scenarios     = emptyList(),
            feasible      = true,
            confidence    = confidence
        )
    }

    // ── FEASIBILITY ───────────────────────────────────────────────────────────

    /**
     * Evaluates whether execution can proceed from the current state.
     *
     * Blocking conditions (any one makes [SimulationAnalysisResult.feasible] = false):
     *  1. Execution phase already completed — cannot re-execute without replay reset.
     *  2. Assembly phase already completed — system lifecycle is closed.
     */
    private fun analyzeFeasibility(snapshot: SimulationSnapshot): SimulationAnalysisResult {
        val findings      = mutableListOf<String>()
        val failurePoints = mutableListOf<String>()

        if (snapshot.executionCompleted) {
            failurePoints += "execution already completed — cannot re-execute without replay reset"
        }

        if (snapshot.assemblyCompleted) {
            failurePoints += "assembly already completed — system lifecycle is closed"
        }

        if (!snapshot.executionStarted && !snapshot.executionCompleted) {
            findings += "execution not yet started — feasibility conditional on execution phase"
        }

        val feasible   = failurePoints.isEmpty()
        val confidence = when {
            !feasible                    -> 0.2
            snapshot.executionStarted    -> 0.7
            else                         -> 0.5
        }

        return SimulationAnalysisResult(
            findings      = findings,
            failurePoints = failurePoints,
            scenarios     = emptyList(),
            feasible      = feasible,
            confidence    = confidence
        )
    }

    // ── SCENARIO ──────────────────────────────────────────────────────────────

    /**
     * Generates alternative execution paths based on the current lifecycle state.
     *
     * Scenarios are derived deterministically from state flags; no randomness is used.
     */
    private fun analyzeScenario(snapshot: SimulationSnapshot): SimulationAnalysisResult {
        val scenarios = mutableListOf<String>()

        if (!snapshot.executionStarted) {
            scenarios += "fast-track: approve contracts immediately and begin execution"
            scenarios += "extended-validation: add an additional contract review before execution"
        }

        if (snapshot.executionStarted && !snapshot.executionCompleted) {
            scenarios += "complete current execution before initiating assembly"
            scenarios +=
                "abort and replay from contract generation to recover from execution drift"
        }

        if (snapshot.executionCompleted && !snapshot.assemblyStarted) {
            scenarios += "standard assembly: proceed to assembly validation now"
            scenarios +=
                "deferred assembly: hold execution state and delay assembly for external confirmation"
        }

        if (snapshot.assemblyStarted && !snapshot.assemblyCompleted) {
            scenarios += "validation-first: complete assembly validation before marking assembly done"
            scenarios +=
                "expedite: mark assembly complete if validation confidence exceeds threshold"
        }

        if (scenarios.isEmpty()) {
            scenarios += "no alternative scenarios available from current state"
        }

        val feasible = !snapshot.assemblyCompleted

        return SimulationAnalysisResult(
            findings = listOf(
                "scenarios generated: ${scenarios.size}"
            ),
            failurePoints = if (feasible) emptyList()
                           else listOf("lifecycle is closed — scenarios are not applicable"),
            scenarios     = scenarios,
            feasible      = feasible,
            confidence    = if (feasible) 0.75 else 0.3
        )
    }
}

/** Internal transfer object from [SimulationAnalyzer] to [SimulationEngine]. */
internal data class SimulationAnalysisResult(
    val findings:      List<String>,
    val failurePoints: List<String>,
    val scenarios:     List<String>,
    val feasible:      Boolean,
    val confidence:    Double
)
