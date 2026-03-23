package com.agoii.mobile.contracts

// ─── Step 1 — Structural Surface Mapping ──────────────────────────────────────

/**
 * SurfaceMapper — Step 1 of the Contract Engine.
 *
 * Converts a [ContractIntent] into its exact mutation footprint ([ContractSurface]).
 *
 * Algorithm:
 *  - CORE is always included; every contract mutation touches the core event model.
 *  - Additional modules are added deterministically when their identifying keywords
 *    appear in the intent's objective, environment, constraints, or resources fields.
 *  - The resulting module list is sorted by [ContractModule] ordinal for
 *    reproducible ordering across equal inputs.
 *
 * Rules:
 *  - Pure function: no state, no side effects, no I/O.
 *  - Equal inputs always produce equal outputs.
 */
class SurfaceMapper {

    /**
     * Map [intent] to its exact [ContractSurface].
     *
     * @param intent The raw contract intent to evaluate.
     * @return [ContractSurface] with totalWeight pre-computed.
     */
    fun map(intent: ContractIntent): ContractSurface {
        val modules = mutableListOf<MappedModule>()

        // CORE is always part of any contract's mutation footprint.
        modules.add(
            MappedModule(ContractModule.CORE, "every contract mutation touches the core event model")
        )

        val objectiveEnv = (intent.objective + " " + intent.environment).lowercase()
        val resources    = intent.resources.lowercase()
        val constraints  = intent.constraints.lowercase()

        if (objectiveEnv.containsAny("ui", "screen", "display", "view", "layout", "compose")) {
            modules.add(
                MappedModule(ContractModule.UI, "UI surface referenced in objective or environment")
            )
        }

        if (objectiveEnv.containsAny("bridge", "interop", "native", "platform")) {
            modules.add(
                MappedModule(ContractModule.BRIDGE, "bridge interop referenced in objective or environment")
            )
        }

        if (objectiveEnv.containsAny("orchestrat", "sequenc", "pipeline", "step", "stage")) {
            modules.add(
                MappedModule(ContractModule.ORCHESTRATION, "orchestration pipeline referenced in objective or environment")
            )
        }

        if (objectiveEnv.containsAny("irs", "intent resolution", "scout", "swarm", "reconstruction", "evidence")) {
            modules.add(
                MappedModule(ContractModule.IRS, "IRS resolution pipeline referenced in objective or environment")
            )
        }

        if (constraints.containsAny("governance", "contract", "approval", "surface", "gate") ||
            resources.containsAny("governance", "contract")
        ) {
            modules.add(
                MappedModule(ContractModule.GOVERNANCE, "governance constraints or resources referenced")
            )
        }

        // Sort by enum ordinal for deterministic ordering.
        val sorted = modules.sortedBy { it.module.ordinal }
        return ContractSurface.from(sorted)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private fun String.containsAny(vararg keywords: String): Boolean =
        keywords.any { this.contains(it) }
}
