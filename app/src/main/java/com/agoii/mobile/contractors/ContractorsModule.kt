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
    ): ResolutionResult = kotlin.run { throw IllegalStateException() }
}

class SwarmCompositionEngine {
    fun compose(
        requirements: List<ContractRequirement>,
        candidates: List<ContractorProfile>,
        evaluated: List<String>,
        rejected: List<RejectedContractor>
    ): ResolutionResult = kotlin.run { throw IllegalStateException() }
}
