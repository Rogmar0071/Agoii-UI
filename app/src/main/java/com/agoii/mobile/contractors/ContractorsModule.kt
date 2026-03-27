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

class SwarmCompositionEngine {
    fun compose(
        requirements: List<ContractRequirement>,
        candidates: List<ContractorProfile>,
        evaluated: List<ContractorProfile>,
        rejected: List<RejectedContractor>
    ): ResolutionResult {
        val requirementMap = requirements.associateBy { it.capability }
        val selected = mutableListOf<ContractorProfile>()
        val uncovered = requirements.map { it.capability }.toMutableSet()

        while (uncovered.isNotEmpty()) {
            val next = candidates
                .filter { it !in selected }
                .filter { candidate ->
                    candidate.capabilities.any { cap ->
                        cap.name in uncovered &&
                            cap.level >= (requirementMap[cap.name]?.requiredLevel ?: Int.MAX_VALUE)
                    }
                }
                .maxWithOrNull(
                    compareBy<ContractorProfile> { candidate ->
                        requirements.filter { req ->
                            candidate.capabilities.any { cap ->
                                cap.name == req.capability &&
                                    cap.level >= req.requiredLevel &&
                                    req.capability in uncovered
                            }
                        }.sumOf { req ->
                            val cap = candidate.capabilities.first { it.name == req.capability }
                            req.weight * cap.level * candidate.reliabilityScore *
                                candidate.availabilityScore * (1.0 - candidate.costScore)
                        }
                    }.thenByDescending { it.contractorId }
                ) ?: break

            val nowCovered = next.capabilities
                .filter { cap ->
                    cap.name in uncovered &&
                        cap.level >= (requirementMap[cap.name]?.requiredLevel ?: Int.MAX_VALUE)
                }
                .map { it.name }
                .toSet()

            selected.add(next)
            uncovered.removeAll(nowCovered)
        }

        val trace = ResolutionTrace(evaluated, selected, rejected)
        return if (uncovered.isEmpty()) {
            ResolutionResult.Swarm(selected, trace)
        } else {
            ResolutionResult.Blocked("NO_FEASIBLE_CONTRACTOR", trace)
        }
    }
}

class DeterministicMatchingEngine(private val registry: ContractorRegistry) {
    fun resolve(requirements: List<ContractRequirement>): ResolutionResult {
        val all = registry.getAll()
        if (all.isEmpty()) {
            return ResolutionResult.Blocked(
                "REGISTRY_EMPTY",
                ResolutionTrace(emptyList(), emptyList(), emptyList())
            )
        }

        val rejected = mutableListOf<RejectedContractor>()
        val validCandidates = mutableListOf<ContractorProfile>()

        for (candidate in all) {
            var isValid = true
            for (req in requirements) {
                val cap = candidate.capabilities.find { it.name == req.capability }
                if (cap == null) {
                    rejected.add(RejectedContractor(candidate.contractorId, "MISSING_CAPABILITY:${req.capability}"))
                    isValid = false
                    break
                }
                if (cap.level < req.requiredLevel) {
                    rejected.add(RejectedContractor(candidate.contractorId, "INSUFFICIENT_LEVEL:${req.capability}"))
                    isValid = false
                    break
                }
            }
            if (isValid) validCandidates.add(candidate)
        }

        val best = validCandidates.maxWithOrNull(
            compareBy<ContractorProfile> { candidate ->
                requirements.sumOf { req ->
                    val cap = candidate.capabilities.first { it.name == req.capability }
                    req.weight * cap.level * candidate.reliabilityScore *
                        candidate.availabilityScore * (1.0 - candidate.costScore)
                }
            }.thenByDescending { it.contractorId }
        )

        if (best != null) {
            return ResolutionResult.Matched(
                best,
                ResolutionTrace(all, listOf(best), rejected)
            )
        }

        return SwarmCompositionEngine().compose(requirements, all, all, rejected)
    }
}
