package com.agoii.mobile.simulation

/**
 * Describes a simulation request submitted to the [SimulationEngine].
 *
 * @property contractId  Unique identifier for this simulation request.
 * @property mode        The kind of analysis to perform (see [SimulationMode]).
 * @property referenceId Optional binding to an existing contract or prior simulation.
 * @property parameters  Freeform key-value hints that guide analysis (e.g. depth, scope).
 */
data class SimulationContract(
    val contractId:  String,
    val mode:        SimulationMode,
    val referenceId: String?,
    val parameters:  Map<String, String>
)
