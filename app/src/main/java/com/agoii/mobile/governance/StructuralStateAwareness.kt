package com.agoii.mobile.governance

import com.agoii.mobile.core.Governor

/**
 * StructuralStateAwareness (SSA) — deterministic, static map of all system states.
 *
 * Rules (C6 — STATIC AWARENESS ONLY):
 *  - All state sets are declared explicitly at compile time.
 *  - No reflection, runtime discovery, or file scanning.
 *  - SSA is read-only; it never modifies any state.
 */
class StructuralStateAwareness {

    /** Complete explicit set of all possible Governor result states. */
    fun governorStates(): Set<Governor.GovernorResult> = setOf(
        Governor.GovernorResult.ADVANCED,
        Governor.GovernorResult.WAITING_FOR_APPROVAL,
        Governor.GovernorResult.COMPLETED,
        Governor.GovernorResult.NO_EVENT,
        Governor.GovernorResult.DRIFT
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
