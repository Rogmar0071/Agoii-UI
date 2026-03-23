package com.agoii.mobile.simulation

/**
 * Immutable output produced by the [SimulationEngine].
 *
 * Rules:
 *  - Never written to the Event Ledger.
 *  - Never triggers execution or phase advancement.
 *  - [confidence] is always in [0.0, 1.0].
 *
 * @property contractId    Mirrors [SimulationContract.contractId] for traceability.
 * @property mode          The [SimulationMode] that was applied.
 * @property feasible      true when no blocking failure points were detected.
 * @property confidence    Confidence in the result; 0.0 = unknown, 1.0 = fully certain.
 * @property findings      Ordered observations produced by the analysis.
 * @property failurePoints Conditions that prevent feasible execution (empty when [feasible]).
 * @property scenarios     Alternative execution paths generated in [SimulationMode.SCENARIO].
 */
data class SimulationResult(
    val contractId:    String,
    val mode:          SimulationMode,
    val feasible:      Boolean,
    val confidence:    Double,
    val findings:      List<String>,
    val failurePoints: List<String>,
    val scenarios:     List<String>
) {
    init {
        require(confidence in 0.0..1.0) { "confidence must be in [0.0, 1.0], got $confidence" }
    }
}
