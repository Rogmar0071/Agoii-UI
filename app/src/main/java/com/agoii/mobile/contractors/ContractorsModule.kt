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
        require(weight >= 0.0) { "weight must be non-negative" }
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
        require(reliabilityScore in 0.0..1.0) { "reliabilityScore must be in 0.0..1.0" }
        require(costScore in 0.0..1.0) { "costScore must be in 0.0..1.0" }
        require(availabilityScore in 0.0..1.0) { "availabilityScore must be in 0.0..1.0" }
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
    val evaluated: List<String>,
    val matched: List<String>,
    val rejected: List<RejectedContractor>
)

sealed class ResolutionResult

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

class DeterministicMatchingEngine(
    private val swarmCompositionEngine: SwarmCompositionEngine = SwarmCompositionEngine()
) {
    fun resolve(
        requirements: List<ContractRequirement>,
        registry: ContractorRegistry
    ): ResolutionResult {
        val contractors = registry.getAll()
        if (contractors.isEmpty()) {
            return Blocked(
                reason = "REGISTRY_EMPTY",
                trace = ResolutionTrace(
                    evaluated = emptyList(),
                    matched = emptyList(),
                    rejected = emptyList()
                )
            )
        }

        val evaluated = contractors.map { it.contractorId }
        val rejected = mutableListOf<RejectedContractor>()
        val feasible = mutableListOf<ContractorProfile>()

        for (contractor in contractors) {
            var rejectionReason: String? = null

            for (requirement in requirements) {
                val capability = contractor.capabilities.firstOrNull {
                    it.name == requirement.capability
                }

                if (capability == null) {
                    rejectionReason = "MISSING_CAPABILITY:${requirement.capability}"
                    break
                }

                if (capability.level < requirement.requiredLevel) {
                    rejectionReason = "INSUFFICIENT_LEVEL:${requirement.capability}"
                    break
                }
            }

            if (rejectionReason == null) {
                feasible += contractor
            } else {
                rejected += RejectedContractor(contractor.contractorId, rejectionReason)
            }
        }

        val matched = feasible.maxWithOrNull(
            compareBy<ContractorProfile>(
                {
                    requirements.sumOf { requirement ->
                        val capability = requireNotNull(it.capabilities.firstOrNull { capability ->
                            capability.name == requirement.capability
                        })
                        requirement.weight *
                            capability.level *
                            it.reliabilityScore *
                            it.availabilityScore *
                            (1.0 - it.costScore)
                    }
                }
            ).thenByDescending { it.contractorId }
        )

        if (matched != null) {
            return Matched(
                contractor = matched,
                trace = ResolutionTrace(
                    evaluated = evaluated,
                    matched = listOf(matched.contractorId),
                    rejected = rejected
                )
            )
        }

        return swarmCompositionEngine.compose(
            requirements = requirements,
            candidates = contractors,
            evaluated = evaluated,
            rejected = rejected
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
        val requirementMap = requirements.associateBy { it.capability }
        val selected = mutableListOf<ContractorProfile>()
        val remaining = requirementMap.keys.toMutableSet()

        while (remaining.isNotEmpty()) {
            val selectedIds = selected.mapTo(mutableSetOf()) { it.contractorId }
            val next = candidates
                .asSequence()
                .filter { candidate -> candidate.contractorId !in selectedIds }
                .map { candidate ->
                    val covered = remaining.filter { capabilityName ->
                        val requirement = requirementMap.getValue(capabilityName)
                        candidate.capabilities.any {
                            it.name == capabilityName && it.level >= requirement.requiredLevel
                        }
                    }
                    candidate to covered
                }
                .filter { (_, covered) -> covered.isNotEmpty() }
                .maxWithOrNull(
                    compareBy<Pair<ContractorProfile, List<String>>>(
                        { it.second.size },
                        {
                            it.second.sumOf { capabilityName ->
                                val requirement = requirementMap.getValue(capabilityName)
                                val capability = requireNotNull(it.first.capabilities.firstOrNull { capability ->
                                    capability.name == capabilityName
                                })
                                requirement.weight *
                                    capability.level *
                                    it.first.reliabilityScore *
                                    it.first.availabilityScore *
                                    (1.0 - it.first.costScore)
                            }
                        }
                    ).thenByDescending { it.first.contractorId }
                )
                ?: return Blocked(
                    reason = "NO_FEASIBLE_CONTRACTOR",
                    trace = ResolutionTrace(
                        evaluated = evaluated,
                        matched = selected.map { it.contractorId },
                        rejected = rejected
                    )
                )

            selected += next.first
            remaining.removeAll(next.second)
        }

        return Swarm(
            contractors = selected,
            trace = ResolutionTrace(
                evaluated = evaluated,
                matched = selected.map { it.contractorId },
                rejected = rejected
            )
        )
    }
}
