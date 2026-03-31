package com.agoii.mobile.contractor

import com.agoii.mobile.contracts.ContractCapability

// ─── DeterministicMatchingEngine ─────────────────────────────────────────────

/**
 * DeterministicMatchingEngine — resolves contractor assignments from the registry.
 *
 * RESOLUTION MODEL (CLOSED — 4 BRANCHES, NO EMPTY ASSIGNMENT POSSIBLE):
 *
 *  STEP 0 — BLOCKED      : registry is empty; no contractors available.
 *  STEP 1 — DIRECT_MATCH : at least one contractor satisfies all required capabilities.
 *                          Selection: reliabilityRatio DESC, id ASC (tiebreaker).
 *  STEP 2 — SWARM        : no single contractor covers all requirements; compose.
 *  STEP 3 — FALLBACK     : swarm exhausted all options but registry is non-empty.
 *                          Mandatory execution attempt with best available contractor.
 *                          Selection: reliabilityRatio DESC, id ASC (tiebreaker).
 *
 * Rules:
 *  - Works directly with [ContractorProfile]; no adapter or internal translation layer.
 *  - [ContractorRegistry] is the sole contractor source — no synthetic generation.
 *  - Every call with the same inputs produces the same result (deterministic).
 *  - A non-empty registry always produces a non-BLOCKED outcome.
 *
 * CONTRACT: CONTRACTOR_MODULE_ALIGNMENT_V2
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

        // ─── STEP 0 — BLOCKED ────────────────────────────────────────────────
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

        // ─── STEP 1 — DIRECT MATCH ───────────────────────────────────────────
        if (valid.isNotEmpty()) {
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

        // ─── STEP 2 — SWARM ──────────────────────────────────────────────────
        val swarmResult = SwarmCompositionEngine().compose(requirements, contractors, evaluated, rejected)
        if (swarmResult is ResolutionResult.Swarm) {
            return TaskAssignedContract(
                contractId      = contract.contractId,
                reportReference = contract.reportReference,
                position        = contract.position,
                assignment      = Assignment(
                    contractorIds = swarmResult.contractors.map { it.id },
                    mode          = AssignmentMode.SWARM
                ),
                trace = swarmResult.trace
            )
        }

        // ─── STEP 3 — FALLBACK ───────────────────────────────────────────────
        // Swarm exhausted all options but the registry is non-empty — always attempt
        // execution with the most reliable available contractor.
        val fallback = contractors.maxWithOrNull(
            compareBy<ContractorProfile> { it.reliabilityRatio }
                .thenByDescending { it.id }
        )!!
        return TaskAssignedContract(
            contractId      = contract.contractId,
            reportReference = contract.reportReference,
            position        = contract.position,
            assignment      = Assignment(contractorIds = listOf(fallback.id), mode = AssignmentMode.MATCHED),
            trace           = ResolutionTrace(evaluated = evaluated, matched = listOf(fallback.id), rejected = rejected)
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

// ─── SwarmCompositionEngine ───────────────────────────────────────────────────

/**
 * Composes a multi-contractor assignment by iteratively selecting contractors
 * that cover the most outstanding requirements.
 *
 * Tie-breaking within a coverage tier: contractor id ASC.
 */
class SwarmCompositionEngine {

    fun compose(
        requirements: List<ContractRequirement>,
        candidates:   List<ContractorProfile>,
        evaluated:    List<String>,
        rejected:     List<RejectedContractor>
    ): ResolutionResult {

        val remaining = requirements.toMutableList()
        val selected  = mutableListOf<ContractorProfile>()

        while (remaining.isNotEmpty()) {
            val available    = candidates.filter { it !in selected }
            val coverageMap  = mutableMapOf<String, Int>()

            for (contractor in available) {
                var count = 0
                for (req in remaining) {
                    val level = capabilityLevel(contractor.capabilities, req.capability)
                    if (level != null && meetsRequirement(req.capability, level, req.requiredLevel)) {
                        count++
                    }
                }
                coverageMap[contractor.id] = count
            }

            val maxCoverage = coverageMap.values.maxOrNull() ?: 0
            if (maxCoverage == 0) {
                return ResolutionResult.Blocked(
                    reason = "NO_FEASIBLE_CONTRACTOR",
                    trace  = ResolutionTrace(
                        evaluated = candidates.map { it.id },
                        matched   = selected.map { it.id },
                        rejected  = rejected
                    )
                )
            }

            val topCandidates = available.filter { coverageMap[it.id] == maxCoverage }
            val best          = topCandidates.sortedBy { it.id }.first()

            val covered = remaining.filter { req ->
                val level = capabilityLevel(best.capabilities, req.capability)
                level != null && meetsRequirement(req.capability, level, req.requiredLevel)
            }
            selected += best
            remaining.removeAll(covered)
        }

        return ResolutionResult.Swarm(
            contractors = selected,
            trace       = ResolutionTrace(
                evaluated = candidates.map { it.id },
                matched   = selected.map { it.id },
                rejected  = rejected
            )
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
