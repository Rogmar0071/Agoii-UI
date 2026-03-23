package com.agoii.mobile.interaction

import com.agoii.mobile.simulation.SimulationView

/**
 * Cross-layer boundary between the Simulation layer and the Interaction layer.
 *
 * Rules:
 *  - Pass-through only — no mapping logic, no transformation, no interpretation.
 *  - [SimulationView] is treated as final, structured truth.
 *  - No mutation of the incoming [SimulationView].
 */
class SimulationInteractionBridge {

    fun map(view: SimulationView): SimulationView {
        return view
    }
}
