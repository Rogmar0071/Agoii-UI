package com.agoii.mobile.contractors

data class Capability(
    val name: String,
    val level: Int
)

data class ContractRequirement(
    val capability: String,
    val requiredLevel: Int,
    val weight: Double
)

data class ContractorProfile(
    val contractorId: String,
    val capabilities: List<Capability>,
    val reliabilityScore: Double,
    val costScore: Double,
    val availabilityScore: Double
)

interface ContractorRegistry {
    fun getAll(): List<ContractorProfile>
}

data class RejectedContractor(
    val contractorId: String,
    val reason: String
)

data class ResolutionTrace(
    val evaluated: List<ContractorProfile>,
    val matched: List<ContractorProfile>,
    val rejected: List<RejectedContractor>
)

sealed class ResolutionResult {
    data class Matched(
        val contractor: ContractorProfile,
        val trace: ResolutionTrace
    ) : ResolutionResult()

    data class Swarm(
        val contractors: List<ContractorProfile>,
        val trace: ResolutionTrace
    ) : ResolutionResult()

    data class Blocked(
        val reason: String,
        val trace: ResolutionTrace
    ) : ResolutionResult()
}

class DeterministicMatchingEngine {
    fun resolve(
        requirements: List<ContractRequirement>,
        registry: ContractorRegistry
    ): ResolutionResult {
        // Step 1 — Load
        val contractors = registry.getAll()
        if (contractors.isEmpty()) {
            return ResolutionResult.Blocked(
                reason = "REGISTRY_EMPTY",
                trace = ResolutionTrace(
                    evaluated = emptyList(),
                    matched = emptyList(),
                    rejected = emptyList()
                )
            )
        }

        // Step 2 — Initialize
        val evaluated = contractors
        val rejected = mutableListOf<RejectedContractor>()
        val valid = mutableListOf<ContractorProfile>()

        // Step 3 — Feasibility
        outer@ for (contractor in contractors) {
            for (requirement in requirements) {
                val cap = contractor.capabilities.find { it.name == requirement.capability }
                if (cap == null) {
                    rejected += RejectedContractor(contractor.contractorId, "MISSING_CAPABILITY:${requirement.capability}")
                    continue@outer
                }
                if (cap.level < requirement.requiredLevel) {
                    rejected += RejectedContractor(contractor.contractorId, "INSUFFICIENT_LEVEL:${requirement.capability}")
                    continue@outer
                }
            }
            valid += contractor
        }

        // Step 4 — No Valid
        if (valid.isEmpty()) {
            return SwarmCompositionEngine().compose(
                requirements,
                contractors,
                contractors.map { it.contractorId },
                rejected
            )
        }

        // Steps 5 & 6 — Scoring and Selection
        val best = valid.maxWithOrNull(
            compareBy<ContractorProfile> { contractor ->
                requirements.sumOf { req ->
                    val cap = contractor.capabilities.find { it.name == req.capability }!!
                    req.weight * cap.level
                } * contractor.reliabilityScore * contractor.availabilityScore * (1.0 - contractor.costScore)
            }.thenByDescending { it.contractorId }
        )!!

        // Step 7 — Return
        return ResolutionResult.Matched(
            contractor = best,
            trace = ResolutionTrace(
                evaluated = evaluated,
                matched = listOf(best),
                rejected = rejected
            )
        )
    }
}

class SwarmCompositionEngine {
    fun compose(
        requirements: List<ContractRequirement>,
        candidates: List<ContractorProfile>,
        evaluated: List<String>,
        rejected: List<RejectedContractor>
    ): ResolutionResult {
        // Step 1 — Init
        val remaining = requirements.toMutableList()
        val selected = mutableListOf<ContractorProfile>()

        // Step 2 — Loop
        while (remaining.isNotEmpty()) {
            val best = candidates.maxWithOrNull(
                compareBy<ContractorProfile> { contractor ->
                    remaining.count { req ->
                        val cap = contractor.capabilities.find { it.name == req.capability }
                        cap != null && cap.level >= req.requiredLevel
                    }
                }.thenByDescending { it.contractorId }
            )
            val covered = if (best != null) {
                remaining.filter { req ->
                    val cap = best.capabilities.find { it.name == req.capability }
                    cap != null && cap.level >= req.requiredLevel
                }
            } else emptyList()

            if (best == null || covered.isEmpty()) {
                return ResolutionResult.Blocked(
                    reason = "NO_FEASIBLE_CONTRACTOR",
                    trace = ResolutionTrace(
                        evaluated = candidates,
                        matched = selected,
                        rejected = rejected
                    )
                )
            }

            selected += best
            remaining.removeAll(covered)
        }

        // Step 3 — Return
        return ResolutionResult.Swarm(
            contractors = selected,
            trace = ResolutionTrace(
                evaluated = candidates,
                matched = selected,
                rejected = rejected
            )
        )
    }
}
