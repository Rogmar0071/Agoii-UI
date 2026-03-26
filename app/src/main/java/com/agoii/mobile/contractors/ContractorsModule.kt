package com.agoii.mobile.contractors

private const val MAX_CAPABILITY_LEVEL = 5
private const val MIN_CAPABILITY_LEVEL = 0
private const val MAX_SCORE = 1.0
private const val MIN_SCORE = 0.0

data class Capability(val name: String, val level: Int) {
    init {
        require(level in MIN_CAPABILITY_LEVEL..MAX_CAPABILITY_LEVEL) {
            "Capability level must be between $MIN_CAPABILITY_LEVEL and $MAX_CAPABILITY_LEVEL, got $level"
        }
    }
}

data class ContractRequirement(
    val capability: String,
    val requiredLevel: Int,
    val weight: Double
) {
    init {
        require(requiredLevel in MIN_CAPABILITY_LEVEL..MAX_CAPABILITY_LEVEL) {
            "Required level must be between $MIN_CAPABILITY_LEVEL and $MAX_CAPABILITY_LEVEL, got $requiredLevel"
        }
    }
}

data class ContractorProfile(
    val contractorId: String,
    val capabilities: List<Capability>,
    val reliabilityScore: Double,
    val costScore: Double,
    val availabilityScore: Double
) {
    init {
        require(reliabilityScore in MIN_SCORE..MAX_SCORE) {
            "reliabilityScore must be between $MIN_SCORE and $MAX_SCORE, got $reliabilityScore"
        }
        require(costScore in MIN_SCORE..MAX_SCORE) {
            "costScore must be between $MIN_SCORE and $MAX_SCORE, got $costScore"
        }
        require(availabilityScore in MIN_SCORE..MAX_SCORE) {
            "availabilityScore must be between $MIN_SCORE and $MAX_SCORE, got $availabilityScore"
        }
    }
}

interface ContractorRegistry {
    fun getAll(): List<ContractorProfile>
}

data class RejectedContractor(val contractorId: String, val reason: String)

data class ResolutionTrace(
    val evaluated: List<String>,
    val matched: List<String>,
    val rejected: List<RejectedContractor>
)

sealed class ResolutionResult {
    data class Matched(val contractor: ContractorProfile, val trace: ResolutionTrace) : ResolutionResult()
    data class Swarm(val contractors: List<ContractorProfile>, val trace: ResolutionTrace) : ResolutionResult()
    data class Blocked(val reason: String, val trace: ResolutionTrace) : ResolutionResult()
}

class DeterministicMatchingEngine {

    fun resolve(requirements: List<ContractRequirement>, registry: ContractorRegistry): ResolutionResult {
        val allContractors = registry.getAll()
        val evaluated = mutableListOf<String>()
        val rejected = mutableListOf<RejectedContractor>()
        val matched = mutableListOf<String>()

        if (allContractors.isEmpty()) {
            return ResolutionResult.Blocked(
                reason = "REGISTRY_EMPTY",
                trace = ResolutionTrace(evaluated, matched, rejected)
            )
        }

        val feasible = mutableListOf<ContractorProfile>()
        for (contractor in allContractors.sortedBy { it.contractorId }) {
            evaluated.add(contractor.contractorId)
            val rejection = checkFeasibility(contractor, requirements)
            if (rejection == null) {
                feasible.add(contractor)
                matched.add(contractor.contractorId)
            } else {
                rejected.add(RejectedContractor(contractor.contractorId, rejection))
            }
        }

        if (feasible.isNotEmpty()) {
            val best = feasible.maxWithOrNull(
                compareBy<ContractorProfile> { computeScore(it, requirements) }
                    .thenByDescending { it.contractorId }
            )!!
            return ResolutionResult.Matched(
                contractor = best,
                trace = ResolutionTrace(evaluated.toList(), matched.toList(), rejected.toList())
            )
        }

        val swarm = buildSwarm(requirements, allContractors.sortedBy { it.contractorId })
        if (swarm != null) {
            return ResolutionResult.Swarm(
                contractors = swarm,
                trace = ResolutionTrace(evaluated.toList(), swarm.map { it.contractorId }, rejected.toList())
            )
        }

        return ResolutionResult.Blocked(
            reason = "NO_FEASIBLE_CONTRACTOR",
            trace = ResolutionTrace(evaluated.toList(), matched.toList(), rejected.toList())
        )
    }

    private fun checkFeasibility(contractor: ContractorProfile, requirements: List<ContractRequirement>): String? {
        for (req in requirements) {
            val cap = contractor.capabilities.find { it.name == req.capability }
                ?: return "MISSING_CAPABILITY:${req.capability}"
            if (cap.level < req.requiredLevel) {
                return "INSUFFICIENT_LEVEL:${req.capability}"
            }
        }
        return null
    }

    private fun computeScore(contractor: ContractorProfile, requirements: List<ContractRequirement>): Double {
        val capabilityScore = requirements.sumOf { req ->
            val level = contractor.capabilities.find { it.name == req.capability }?.level?.toDouble() ?: 0.0
            req.weight * level
        }
        val costFactor = 1.0 - contractor.costScore
        return capabilityScore * contractor.reliabilityScore * contractor.availabilityScore * costFactor
    }

    private fun buildSwarm(
        requirements: List<ContractRequirement>,
        candidates: List<ContractorProfile>
    ): List<ContractorProfile>? {
        val uncovered = requirements.toMutableList()
        val swarm = mutableListOf<ContractorProfile>()
        val remaining = candidates.toMutableList()

        while (uncovered.isNotEmpty()) {
            val best = remaining.maxWithOrNull(
                compareBy<ContractorProfile> { c ->
                    uncovered.count { req ->
                        c.capabilities.any { it.name == req.capability && it.level >= req.requiredLevel }
                    }
                }.thenByDescending { it.contractorId }
            ) ?: return null

            val covers = uncovered.filter { req ->
                best.capabilities.any { it.name == req.capability && it.level >= req.requiredLevel }
            }
            if (covers.isEmpty()) return null

            swarm.add(best)
            remaining.remove(best)
            uncovered.removeAll(covers.toSet())
        }

        return swarm.takeIf { it.isNotEmpty() }
    }
}
