package com.agoii.mobile.contractors

data class Capability(
    val name: String,
    val level: Int
) {
    init {
        require(level in 0..5) { "level must be in 0..5" }
    }
}

data class ContractRequirement(
    val capability: String,
    val requiredLevel: Int,
    val weight: Double
) {
    init {
        require(requiredLevel in 0..5) { "requiredLevel must be in 0..5" }
    }
}

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
    val evaluated: List<String>,
    val matched: List<String>,
    val rejected: List<RejectedContractor>
)

sealed class ResolutionResult {
    data class Matched(
        val contractorId: String,
        val score: Double,
        val trace: ResolutionTrace
    ) : ResolutionResult()

    data class Swarm(
        val contractorIds: List<String>,
        val trace: ResolutionTrace
    ) : ResolutionResult()

    data class Blocked(
        val reason: String,
        val trace: ResolutionTrace
    ) : ResolutionResult()
}

class DeterministicMatchingEngine {

    fun resolve(requirements: List<ContractRequirement>, registry: ContractorRegistry): ResolutionResult {
        val allContractors = registry.getAll()

        if (allContractors.isEmpty()) {
            return ResolutionResult.Blocked(
                reason = "REGISTRY_EMPTY",
                trace = ResolutionTrace(
                    evaluated = emptyList(),
                    matched = emptyList(),
                    rejected = emptyList()
                )
            )
        }

        val rejected = mutableListOf<RejectedContractor>()
        val validCandidates = mutableListOf<ContractorProfile>()

        for (contractor in allContractors) {
            var feasible = true
            var rejectionReason = ""

            for (req in requirements) {
                val cap = contractor.capabilities.firstOrNull { it.name == req.capability }
                if (cap == null) {
                    feasible = false
                    rejectionReason = "MISSING_CAPABILITY:${req.capability}"
                    break
                }
                if (cap.level < req.requiredLevel) {
                    feasible = false
                    rejectionReason = "INSUFFICIENT_LEVEL:${req.capability}"
                    break
                }
            }

            if (feasible) {
                validCandidates.add(contractor)
            } else {
                rejected.add(RejectedContractor(contractor.contractorId, rejectionReason))
            }
        }

        if (validCandidates.isNotEmpty()) {
            val best = validCandidates.maxWithOrNull(
                compareBy<ContractorProfile> { contractor ->
                    requirements.sumOf { req ->
                        val cap = contractor.capabilities.first { it.name == req.capability }
                        req.weight * cap.level * contractor.reliabilityScore * contractor.availabilityScore * (1.0 - contractor.costScore)
                    }
                }.thenByDescending { it.contractorId }
            )!!

            val bestScore = requirements.sumOf { req ->
                val cap = best.capabilities.first { it.name == req.capability }
                req.weight * cap.level * best.reliabilityScore * best.availabilityScore * (1.0 - best.costScore)
            }

            return ResolutionResult.Matched(
                contractorId = best.contractorId,
                score = bestScore,
                trace = ResolutionTrace(
                    evaluated = allContractors.map { it.contractorId },
                    matched = listOf(best.contractorId),
                    rejected = rejected
                )
            )
        }

        val remainingRequirements = requirements.toMutableList()
        val swarmIds = mutableListOf<String>()
        val available = allContractors.toMutableList()

        while (remainingRequirements.isNotEmpty() && available.isNotEmpty()) {
            val best = available.maxWithOrNull(
                compareBy<ContractorProfile> { contractor ->
                    remainingRequirements.count { req ->
                        contractor.capabilities.any { it.name == req.capability && it.level >= req.requiredLevel }
                    }.toDouble()
                }.thenByDescending { it.contractorId }
            ) ?: break

            val covered = remainingRequirements.filter { req ->
                best.capabilities.any { it.name == req.capability && it.level >= req.requiredLevel }
            }

            if (covered.isEmpty()) break

            swarmIds.add(best.contractorId)
            available.remove(best)
            remainingRequirements.removeAll(covered.toSet())
        }

        if (remainingRequirements.isEmpty()) {
            return ResolutionResult.Swarm(
                contractorIds = swarmIds,
                trace = ResolutionTrace(
                    evaluated = allContractors.map { it.contractorId },
                    matched = swarmIds,
                    rejected = rejected
                )
            )
        }

        return ResolutionResult.Blocked(
            reason = "NO_FEASIBLE_CONTRACTOR",
            trace = ResolutionTrace(
                evaluated = allContractors.map { it.contractorId },
                matched = emptyList(),
                rejected = rejected
            )
        )
    }
}
