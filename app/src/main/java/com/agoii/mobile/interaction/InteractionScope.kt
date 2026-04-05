package com.agoii.mobile.interaction

/**
 * Defines the structural scope of an [InteractionContract].
 *
 * Scope determines which fields of [ReplayStructuralState] are visible inside
 * the interaction boundary.  Every scope maps to a deterministic, named subset
 * of fields — no derived logic, no hidden fallback.
 *
 * ARCH-02: state extraction is bounded to this layer.
 */
enum class InteractionScope {
    /** Full system view: execution flags + assembly flags. */
    FULL_SYSTEM,

    /** Contract-only view: execution and assembly flags are hidden. */
    CONTRACT,

    /** Task-only view: assembly flags are hidden; executionCompleted is suppressed. */
    TASK,

    /** Execution-only view: assembly flags are hidden. */
    EXECUTION,

    /** Simulation path: no structural flags; all booleans suppressed. */
    SIMULATION
}

/**
 * Identifies the data source that backs the [InteractionContract].
 *
 * LEDGER  — state is sourced from [ReplayStructuralState] (the ledger replay path).
 * SIMULATION — state is sourced from a [com.agoii.mobile.simulation.SimulationView].
 */
enum class SourceType {
    LEDGER,
    SIMULATION
}
