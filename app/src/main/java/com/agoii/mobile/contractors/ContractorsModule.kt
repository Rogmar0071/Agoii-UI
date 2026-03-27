package com.agoii.mobile.contractors

data class Capability(
    val name: String,
    val level: Int
) {
    init {
        require(level in 0..5)
    }
}

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
    val evaluated: Int,
    val matched: Int,
    val rejected: List<RejectedContractor>
)

sealed class ResolutionResult {
    data class Matched(
        val contractor: ContractorProfile,
        val score: Double,
        val trace: ResolutionTrace
    ) : ResolutionResult()

    data class Swarm(
        val contractors: List<ContractorProfile>,
        val combinedScore: Double,
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
        val contractors = registry.getAll()

        if (contractors.isEmpty()) {
            return ResolutionResult.Blocked(
                reason = "REGISTRY_EMPTY",
                trace = ResolutionTrace(evaluated = 0, matched = 0, rejected = emptyList())
            )
        }

        val evaluated = contractors.size
        val rejected = mutableListOf<RejectedContractor>()
        val validCandidates = mutableListOf<ContractorProfile>()

        for (contractor in contractors) {
            var passed = true
            for (requirement in requirements) {
                val capability = contractor.capabilities.find { it.name == requirement.capability }
                if (capability == null) {
                    rejected.add(RejectedContractor(contractor.contractorId, "MISSING_CAPABILITY:${requirement.capability}"))
                    passed = false
                    break
                }
                if (capability.level < requirement.requiredLevel) {
                    rejected.add(RejectedContractor(contractor.contractorId, "INSUFFICIENT_LEVEL:${requirement.capability}"))
                    passed = false
                    break
                }
            }
            if (passed) {
                validCandidates.add(contractor)
            }
        }

        if (validCandidates.isEmpty()) {
            return SwarmCompositionEngine().resolveSwarm(requirements, registry, evaluated, rejected)
        }

        val scored = validCandidates.map { contractor ->
            val capabilityScore = requirements.sumOf { req ->
                val cap = contractor.capabilities.find { it.name == req.capability }!!
                req.weight * cap.level.toDouble()
            }
            val score = capabilityScore * contractor.reliabilityScore * contractor.availabilityScore * (1.0 - contractor.costScore)
            Pair(contractor, score)
        }

        val best = scored.sortedWith(
            compareByDescending<Pair<ContractorProfile, Double>> { it.second }
                .thenBy { it.first.contractorId }
        ).first()

        val trace = ResolutionTrace(
            evaluated = evaluated,
            matched = validCandidates.size,
            rejected = rejected
        )

        return ResolutionResult.Matched(
            contractor = best.first,
            score = best.second,
            trace = trace
        )
    }
}

class SwarmCompositionEngine {

    fun resolveSwarm(
        requirements: List<ContractRequirement>,
        registry: ContractorRegistry,
        evaluated: Int,
        rejected: List<RejectedContractor>
    ): ResolutionResult {
        val allContractors = registry.getAll()
        val uncovered = requirements.map { it.capability }.toMutableSet()
        val selectedContractors = mutableListOf<ContractorProfile>()

        while (uncovered.isNotEmpty()) {
            val best = allContractors
                .filter { it !in selectedContractors }
                .map { contractor ->
                    val covered = uncovered.count { reqCap ->
                        val cap = contractor.capabilities.find { it.name == reqCap }
                        val req = requirements.find { it.capability == reqCap }
                        cap != null && req != null && cap.level >= req.requiredLevel
                    }
                    Pair(contractor, covered)
                }
                .filter { it.second > 0 }
                .sortedWith(compareByDescending<Pair<ContractorProfile, Int>> { it.second }.thenBy { it.first.contractorId })
                .firstOrNull()

            if (best == null) {
                return ResolutionResult.Blocked(
                    reason = "NO_FEASIBLE_CONTRACTOR",
                    trace = ResolutionTrace(evaluated = evaluated, matched = 0, rejected = rejected)
                )
            }

            selectedContractors.add(best.first)
            for (reqCap in uncovered.toList()) {
                val cap = best.first.capabilities.find { it.name == reqCap }
                val req = requirements.find { it.capability == reqCap }
                if (cap != null && req != null && cap.level >= req.requiredLevel) {
                    uncovered.remove(reqCap)
                }
            }
        }

        val combinedScore = selectedContractors.map { contractor ->
            val capabilityScore = requirements.sumOf { req ->
                val cap = contractor.capabilities.find { it.name == req.capability }
                if (cap != null) req.weight * cap.level.toDouble() else 0.0
            }
            capabilityScore * contractor.reliabilityScore * contractor.availabilityScore * (1.0 - contractor.costScore)
        }.average()

        val trace = ResolutionTrace(
            evaluated = evaluated,
            matched = selectedContractors.size,
            rejected = rejected
        )

        return ResolutionResult.Swarm(
            contractors = selectedContractors,
            combinedScore = combinedScore,
            trace = trace
        )
    }
}
