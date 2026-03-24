package com.agoii.mobile.governance

// ─── ModuleState — Structural State Interface ─────────────────────────────────

/**
 * ModuleState — the structural state contract that every module adapter must satisfy.
 *
 * Rules:
 *  - [getStateSignature] MUST return a FULL structural snapshot, not a partial one.
 *  - Implementations are read-only; they never mutate external state.
 *  - Adapters expose state only — they do NOT perform validation or make decisions.
 *  - The Governor is the sole authority that reads the state and decides what to do.
 */
interface ModuleState {

    /**
     * Returns the full structural snapshot of this module's current state.
     * Must include every field that describes the module's declared structure.
     * The Governor reads these fields to make write decisions; adapters do not decide.
     */
    fun getStateSignature(): Map<String, Any>
}
