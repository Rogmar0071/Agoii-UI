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
        // STEP 1 — LOAD
        val contractors = registry.getAll()
        if (contractors.isEmpty()) {
            return ResolutionResult.Blocked(
                reason = "REGISTRY_EMPTY",
                trace = ResolutionTrace(evaluated = 0, matched = 0, rejected = emptyList())
            )
        }

        // STEP 2 — INITIALIZE
        val rejected = mutableListOf<RejectedContractor>()
        val validCandidates = mutableListOf<ContractorProfile>()

        // STEP 3 — FEASIBILITY
        for (contractor in contractors) {
            var feasible = true
            for (requirement in requirements) {
                val capability = contractor.capabilities.find { it.name == requirement.capability }
                if (capability == null) {
                    rejected.add(
                        RejectedContractor(
                            contractorId = contractor.contractorId,
                            reason = "MISSING_CAPABILITY:${requirement.capability}"
                        )
                    )
                    feasible = false
                    break
                }
            }
            if (feasible) {
                validCandidates.add(contractor)
            }
        }

        if (validCandidates.isEmpty()) {
            return ResolutionResult.Blocked(
                reason = "NO_FEASIBLE_CONTRACTORS",
                trace = ResolutionTrace(
                    evaluated = contractors.size,
                    matched = 0,
                    rejected = rejected
                )
            )
        }

        return ResolutionResult.Blocked(
            reason = "UNIMPLEMENTED",
            trace = ResolutionTrace(
                evaluated = contractors.size,
                matched = 0,
                rejected = rejected
            )
        )
    }
}
