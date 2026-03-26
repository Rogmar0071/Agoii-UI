package com.agoii.mobile.contractors

data class Capability(
    val name: String,
    val level: Int
) {
    init {
        require(level in 0..5) { "level must be in range 0..5" }
    }
}

data class ContractRequirement(
    val capability: String,
    val requiredLevel: Int,
    val weight: Double
) {
    init {
        require(requiredLevel in 0..5) { "requiredLevel must be in range 0..5" }
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
        require(reliabilityScore in 0.0..1.0)
        require(costScore in 0.0..1.0)
        require(availabilityScore in 0.0..1.0)
    }
}

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
                "REGISTRY_EMPTY",
                ResolutionTrace(0, 0, emptyList())
            )
        }

        val rejected = mutableListOf<RejectedContractor>()
        val validCandidates = mutableListOf<ContractorProfile>()

        for (contractor in contractors) {

            var feasible = true

            for (req in requirements) {

                val cap = contractor.capabilities.find { it.name == req.capability }

                if (cap == null) {
                    rejected.add(
                        RejectedContractor(contractor.contractorId, "MISSING_CAPABILITY:${req.capability}")
                    )
                    feasible = false
                    break
                }

                if (cap.level < req.requiredLevel) {
                    rejected.add(
                        RejectedContractor(contractor.contractorId, "INSUFFICIENT_LEVEL:${req.capability}")
                    )
                    feasible = false
                    break
                }
            }

            if (feasible) {
                validCandidates.add(contractor)
            }
        }

        if (validCandidates.isNotEmpty()) {

            var best: ContractorProfile? = null
            var bestScore = Double.NEGATIVE_INFINITY

            for (contractor in validCandidates) {

                var score = 0.0

                for (req in requirements) {
                    val cap = contractor.capabilities.first { it.name == req.capability }
                    score += req.weight * cap.level
                }

                score *= contractor.reliabilityScore
                score *= contractor.availabilityScore
                score *= (1 - contractor.costScore)

                if (best == null ||
                    score > bestScore ||
                    (score == bestScore && contractor.contractorId < best!!.contractorId)
                ) {
                    best = contractor
                    bestScore = score
                }
            }

            return ResolutionResult.Matched(
                best!!,
                bestScore,
                ResolutionTrace(contractors.size, 1, rejected)
            )
        }

        val uncovered = requirements.toMutableSet()
        val selected = mutableListOf<ContractorProfile>()

        while (uncovered.isNotEmpty()) {

            val best = contractors.maxByOrNull { contractor ->
                uncovered.count { req ->
                    contractor.capabilities.any {
                        it.name == req.capability && it.level >= req.requiredLevel
                    }
                }
            } ?: break

            val covered = uncovered.filter { req ->
                best.capabilities.any {
                    it.name == req.capability && it.level >= req.requiredLevel
                }
            }

            if (covered.isEmpty()) {
                break
            }

            selected.add(best)
            uncovered.removeAll(covered)
        }

        if (uncovered.isEmpty() && selected.isNotEmpty()) {

            val scores = selected.map { contractor ->

                var score = 0.0

                for (req in requirements) {
                    val cap = contractor.capabilities.find { it.name == req.capability }
                    if (cap != null) {
                        score += req.weight * cap.level
                    }
                }

                score *= contractor.reliabilityScore
                score *= contractor.availabilityScore
                score *= (1 - contractor.costScore)

                score
            }

            val combinedScore = scores.average()

            return ResolutionResult.Swarm(
                selected,
                combinedScore,
                ResolutionTrace(contractors.size, selected.size, rejected)
            )
        }

        return ResolutionResult.Blocked(
            "NO_FEASIBLE_CONTRACTOR",
            ResolutionTrace(contractors.size, 0, rejected)
        )
    }

}
