package com.agoii.mobile.contractor

import com.agoii.mobile.contracts.ContractCapability

// ─── DeterministicMatchingEngine ─────────────────────────────────────────────

/**
 * DeterministicMatchingEngine — resolves contractor assignments from the registry.
 *
 * RESOLUTION MODEL (CLOSED — 2 BRANCHES, SINGLE-PATH SELECTION):
 *
 *  STEP 0 — BLOCKED      : registry is empty OR no contractor satisfies all required
 *                          capabilities; execution cannot proceed.
 *  STEP 1 — DIRECT_MATCH : exactly one path — select the single best contractor that
 *                          satisfies all requirements.
 *                          Selection: reliabilityRatio DESC, id ASC (tiebreaker).
 *
 * Rules:
 *  - Works directly with [ContractorProfile]; no adapter or internal translation layer.
 *  - [ContractorRegistry] is the sole contractor source — no synthetic generation.
 *  - Every call with the same inputs produces the same result (deterministic).
 *  - No fallback. No swarm. One path only.
 *
 * CONTRACT: RECOVERY_MATCHING_ENGINE_V1
 */
class DeterministicMatchingEngine {

    /**
     * Resolve a contractor assignment for [contract] given [requiredCapabilities]
     * from the [registry].
     *
     * @param contract             Lightweight contract descriptor carrying id, reference, position.
     * @param requiredCapabilities Capability requirements derived from the active contract.
     * @param registry             Single authoritative contractor source.
     * @return [TaskAssignedContract] with a fully resolved [Assignment].
     */
    fun resolve(
        contract:             ExecutionContract,
        requiredCapabilities: List<ContractCapability>,
        registry:             ContractorRegistry
    ): TaskAssignedContract {

        val requirements = requiredCapabilities.map { cap ->
            ContractRequirement(
                capability    = cap.dimensionName,
                requiredLevel = cap.requiredLevel,
                weight        = 1.0
            )
        }

        val contractors = registry.getAll()

        // ─── STEP 0 — BLOCKED (empty registry) ───────────────────────────────
        if (contractors.isEmpty()) {
            return TaskAssignedContract(
                contractId      = contract.contractId,
                reportReference = contract.reportReference,
                position        = contract.position,
                assignment      = Assignment(contractorIds = emptyList(), mode = AssignmentMode.BLOCKED),
                trace           = ResolutionTrace(evaluated = emptyList(), matched = emptyList(), rejected = emptyList())
            )
        }

        val evaluated = contractors.map { it.id }
        val rejected  = mutableListOf<RejectedContractor>()
        val valid     = mutableListOf<ContractorProfile>()

        // Feasibility check — classify each contractor as valid or rejected.
        outer@ for (contractor in contractors) {
            for (req in requirements) {
                val level = capabilityLevel(contractor.capabilities, req.capability)
                if (level == null) {
                    rejected += RejectedContractor(contractor.id, "MISSING_CAPABILITY:${req.capability}")
                    continue@outer
                }
                if (!meetsRequirement(req.capability, level, req.requiredLevel)) {
                    rejected += RejectedContractor(contractor.id, "INSUFFICIENT_LEVEL:${req.capability}")
                    continue@outer
                }
            }
            valid += contractor
        }

        // ─── STEP 0 — BLOCKED (no qualifying contractor) ─────────────────────
        if (valid.isEmpty()) {
            return TaskAssignedContract(
                contractId      = contract.contractId,
                reportReference = contract.reportReference,
                position        = contract.position,
                assignment      = Assignment(contractorIds = emptyList(), mode = AssignmentMode.BLOCKED),
                trace           = ResolutionTrace(evaluated = evaluated, matched = emptyList(), rejected = rejected)
            )
        }

        // ─── STEP 1 — DIRECT MATCH ───────────────────────────────────────────
        val best = valid.maxWithOrNull(
            compareBy<ContractorProfile> { it.reliabilityRatio }
                .thenByDescending { it.id }
        )!!
        return TaskAssignedContract(
            contractId      = contract.contractId,
            reportReference = contract.reportReference,
            position        = contract.position,
            assignment      = Assignment(contractorIds = listOf(best.id), mode = AssignmentMode.MATCHED),
            trace           = ResolutionTrace(evaluated = evaluated, matched = listOf(best.id), rejected = rejected)
        )
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun capabilityLevel(cap: ContractorCapabilityVector, dimension: String): Int? = when (dimension) {
        "constraintObedience" -> cap.constraintObedience
        "structuralAccuracy"  -> cap.structuralAccuracy
        "complexityCapacity"  -> cap.complexityCapacity
        "reliability"         -> cap.reliability
        "driftScore"          -> cap.driftScore
        else                  -> null
    }

    private fun meetsRequirement(dimension: String, actual: Int, required: Int): Boolean =
        if (dimension == "driftScore") actual <= required else actual >= required
}
