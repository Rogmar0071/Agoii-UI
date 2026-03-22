package com.agoii.mobile.governance

/**
 * SSM — State Surface Mirror
 *
 * Provides internal, closed-loop state awareness of the governance system.
 * Replaces the previous open-loop / external state model.
 *
 * Lifecycle:
 *   UNINITIALIZED → (initialize) → INITIALIZED → (activate) → ACTIVE
 *
 * Rules:
 *  - Must be [initialize]d before the first contract can be issued.
 *  - [activateSurface] registers surfaces that are currently governed.
 *  - All state reads are non-destructive; no side effects.
 *  - The mirror is not persisted; it is rebuilt when the Governor processes
 *    an execution_started event (deterministic re-initialisation on replay).
 */
class StateSurfaceMirror {

    enum class SsmState { UNINITIALIZED, INITIALIZED, ACTIVE }

    private var state: SsmState = SsmState.UNINITIALIZED
    private val activeSurfaces: MutableSet<ContractSurface> = mutableSetOf()

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Transition from UNINITIALIZED → INITIALIZED.
     * Idempotent: calling [initialize] on an already-initialized mirror is a no-op.
     */
    fun initialize() {
        if (state == SsmState.UNINITIALIZED) {
            state = SsmState.INITIALIZED
        }
    }

    /**
     * Register a surface and transition to ACTIVE.
     * Must be called after [initialize]; silently ignored otherwise.
     */
    fun activateSurface(surface: ContractSurface) {
        if (state != SsmState.UNINITIALIZED) {
            activeSurfaces.add(surface)
            state = SsmState.ACTIVE
        }
    }

    // ─── Queries ──────────────────────────────────────────────────────────────

    /** true when the mirror has been initialized (INITIALIZED or ACTIVE). */
    fun isInitialized(): Boolean = state != SsmState.UNINITIALIZED

    /** Current SSM lifecycle state. */
    fun currentState(): SsmState = state

    /** Snapshot of all surfaces currently registered in the mirror. */
    fun getActiveSurfaces(): Set<ContractSurface> = activeSurfaces.toSet()
}
