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
    val evaluated: List<String>,
    val matched: List<String>,
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
        val evaluated = contractors.map { it.contractorId }
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
        val scores = mutableMapOf<String, Double>()
        for (contractor in valid) {
            var capabilityScore = 0.0
            for (req in requirements) {
                val cap = contractor.capabilities.find { it.name == req.capability }!!
                capabilityScore += req.weight * cap.level
            }
            val score = capabilityScore * contractor.reliabilityScore * contractor.availabilityScore * (1.0 - contractor.costScore)
            scores[contractor.contractorId] = score
        }
        val maxScore = scores.values.maxOrNull()!!
        val topCandidates = valid.filter { scores[it.contractorId] == maxScore }
        val best = topCandidates.sortedBy { it.contractorId }.first()

        // Step 7 — Return
        return ResolutionResult.Matched(
            contractor = best,
            trace = ResolutionTrace(
                evaluated = evaluated,
                matched = listOf(best.contractorId),
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
            val available = candidates.filter { it !in selected }
            val coverageMap = mutableMapOf<String, Int>()
            for (contractor in available) {
                var count = 0
                for (req in remaining) {
                    val cap = contractor.capabilities.find { it.name == req.capability }
                    if (cap != null && cap.level >= req.requiredLevel) {
                        count++
                    }
                }
                coverageMap[contractor.contractorId] = count
            }
            val maxCoverage = coverageMap.values.maxOrNull() ?: 0
            if (maxCoverage == 0) {
                return ResolutionResult.Blocked(
                    reason = "NO_FEASIBLE_CONTRACTOR",
                    trace = ResolutionTrace(
                        evaluated = candidates.map { it.contractorId },
                        matched = selected.map { it.contractorId },
                        rejected = rejected
                    )
                )
            }
            val topCandidates = available.filter { coverageMap[it.contractorId] == maxCoverage }
            val best = topCandidates.sortedBy { it.contractorId }.first()
            val covered = mutableListOf<ContractRequirement>()
            for (req in remaining) {
                val cap = best.capabilities.find { it.name == req.capability }
                if (cap != null && cap.level >= req.requiredLevel) {
                    covered += req
                }
            }
            selected += best
            remaining.removeAll(covered)
        }

        // Step 3 — Return
        return ResolutionResult.Swarm(
            contractors = selected,
            trace = ResolutionTrace(
                evaluated = candidates.map { it.contractorId },
                matched = selected.map { it.contractorId },
                rejected = rejected
            )
        )
    }
}
