package com.agoii.mobile.simulation

/**
 * Neutral, read-only representation of a [SimulationResult] intended for
 * consumption outside the Simulation layer.
 *
 * Rules:
 *  - Derived ONLY inside the Simulation layer via [SimulationEngine.toView].
 *  - Represents final meaning; no further interpretation is required or allowed.
 *  - No UI formatting.
 *  - No external dependencies.
 *
 * @property summary    Primary conclusion of the simulation.
 * @property details    Supporting observations from the simulation.
 * @property confidence Confidence in the result; 0.0 = unknown, 1.0 = fully certain.
 * @property mode       String name of the [SimulationMode] that was applied.
 */
data class SimulationView(
    val summary:    String,
    val details:    List<String>,
    val confidence: Double,
    val mode:       String
)
