package com.agoii.mobile.governance

/**
 * StructuralStateAwareness (SSA) — deterministic, static map of all system states.
 *
 * Rules (C6 — STATIC AWARENESS ONLY):
 *  - All state sets are declared explicitly at compile time.
 *  - No reflection, runtime discovery, or file scanning.
 *  - SSA is read-only; it never modifies any state.
 *  - SSA is independent: no imports from outside the governance package.
 */
class StructuralStateAwareness {

    /**
     * Complete explicit set of all possible Governor result states.
     *
     * These string values are the authoritative SSA declarations (deterministic).
     * They must match the names of Governor.GovernorResult enum constants.
     * SSA does not import that enum; it owns its structural map independently.
     */
    fun governorStates(): Set<String> = setOf(
        "ADVANCED",
        "WAITING_FOR_APPROVAL",
        "WAITING",
        "COMPLETED",
        "NO_EVENT",
        "DRIFT"
    )

    /** Complete explicit set of all active surface types in the governance model. */
    fun surfaceStates(): Set<SurfaceType> = setOf(
        SurfaceType.LG
    )

    /** Complete explicit set of all contract evaluation outcomes. */
    fun outcomeStates(): Set<Outcome> = setOf(
        Outcome.ALLOWED,
        Outcome.REJECTED
    )
}
