package com.agoii.mobile.contracts

// ─── Step 2 — Failure Mapping ─────────────────────────────────────────────────

/**
 * FailureMapper — Step 2 of the Contract Engine.
 *
 * Derives the full failure landscape from a [ContractSurface] and the originating
 * [ContractIntent].
 *
 * Failure rules (boolean rule table; no thresholds except [CRITICAL_LOAD_THRESHOLD]):
 *  1. LOAD_EXCEEDED   (critical)     — when totalWeight > [CRITICAL_LOAD_THRESHOLD].
 *  2. DEPENDENCY_MISSING (critical)  — when IRS is in the surface but CORE is absent
 *                                       (structural invariant: IRS requires CORE).
 *  3. MISSING_RESOURCE (non-critical) — when the intent declares no resources.
 *  4. CONSTRAINT_VIOLATED (critical)  — when GOVERNANCE is in the surface but the
 *                                       intent declares no constraints.
 *
 * Rules:
 *  - Pure function: no state, no side effects.
 *  - [FailureMap.hasCritical] is true when at least one [ContractFailure.critical] entry exists.
 */
class FailureMapper {

    /**
     * Derive the [FailureMap] for [intent] given its [surface].
     *
     * @param intent  The raw contract intent.
     * @param surface The mutation footprint produced by [SurfaceMapper].
     * @return [FailureMap] with all detected failure modes and the [FailureMap.hasCritical] flag.
     */
    fun map(intent: ContractIntent, surface: ContractSurface): FailureMap {
        val failures   = mutableListOf<ContractFailure>()
        val moduleSet  = surface.modules.map { it.module }.toSet()

        // Rule 1 — load check
        if (surface.totalWeight > CRITICAL_LOAD_THRESHOLD) {
            failures.add(
                ContractFailure(
                    module      = ContractModule.CORE,
                    type        = ContractFailureType.LOAD_EXCEEDED,
                    description = "total surface weight ${surface.totalWeight} exceeds critical " +
                                  "threshold $CRITICAL_LOAD_THRESHOLD",
                    critical    = true
                )
            )
        }

        // Rule 2 — IRS dependency on CORE (defensive: guards standalone usage of FailureMapper
        //           with a custom surface that may omit CORE; never fires through ContractEngine
        //           because SurfaceMapper always includes CORE).
        if (ContractModule.IRS in moduleSet && ContractModule.CORE !in moduleSet) {
            failures.add(
                ContractFailure(
                    module      = ContractModule.IRS,
                    type        = ContractFailureType.DEPENDENCY_MISSING,
                    description = "IRS requires CORE but CORE is absent from the surface",
                    critical    = true
                )
            )
        }

        // Rule 3 — missing resources declaration (non-critical)
        if (intent.resources.isBlank()) {
            failures.add(
                ContractFailure(
                    module      = ContractModule.CORE,
                    type        = ContractFailureType.MISSING_RESOURCE,
                    description = "no resources declared for execution",
                    critical    = false
                )
            )
        }

        // Rule 4 — governance without constraints
        if (ContractModule.GOVERNANCE in moduleSet && intent.constraints.isBlank()) {
            failures.add(
                ContractFailure(
                    module      = ContractModule.GOVERNANCE,
                    type        = ContractFailureType.CONSTRAINT_VIOLATED,
                    description = "GOVERNANCE module is in the surface but no constraints are declared",
                    critical    = true
                )
            )
        }

        return FailureMap(
            failures    = failures,
            hasCritical = failures.any { it.critical }
        )
    }

    companion object {
        /** Total surface weight above which LOAD_EXCEEDED is reported. */
        const val CRITICAL_LOAD_THRESHOLD = 10
    }
}
