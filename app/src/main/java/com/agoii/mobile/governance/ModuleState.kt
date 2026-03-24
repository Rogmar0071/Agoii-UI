package com.agoii.mobile.governance

// ─── ModuleState — Structural State Interface ─────────────────────────────────

/**
 * ModuleState — the structural state contract that every module adapter must satisfy.
 *
 * Rules:
 *  - [getStateSignature] MUST return a FULL structural snapshot, not a partial one.
 *  - [isValidationComplete] reflects whether the module's own declared validation passed.
 *  - [getValidationErrors] enumerates every failure reason when validation is incomplete.
 *  - Implementations are read-only; they never mutate external state.
 */
interface ModuleState {

    /**
     * Returns the full structural snapshot of this module's current state.
     * Must include every field that describes the module's declared structure.
     */
    fun getStateSignature(): Map<String, Any>

    /**
     * Returns true only when the module's declared validation has been completed
     * and no error conditions are present.
     */
    fun isValidationComplete(): Boolean

    /**
     * Returns the ordered list of validation error descriptions.
     * Must be empty when [isValidationComplete] returns true.
     */
    fun getValidationErrors(): List<String>
}
